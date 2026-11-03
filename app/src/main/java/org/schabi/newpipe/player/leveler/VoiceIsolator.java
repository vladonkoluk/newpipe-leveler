package org.schabi.newpipe.player.leveler;

/**
 * «Только голос»: приглушает всё, что не похоже на голос.
 *
 * <p>Звук раскладывается на частоты (окно 1024 сэмпла, шаг 256), и каждая частота получает
 * свой коэффициент — произведение трёх оценок:</p>
 * <ol>
 *     <li>в центре ли она: голос почти всегда в центре стерео, а музыка и окружение — по краям
 *     или вразнобой в двух каналах (сравнивается, насколько совпадают левый и правый каналы);</li>
 *     <li>в полосе ли речи (200–4500 Гц с плавными краями): гул, басы и шипение убираются;</li>
 *     <li>не ровный ли это шум: для каждой частоты отслеживается уровень фона (по минимумам),
 *     и то, что не поднимается над ним, приглушается (фильтр Винера).</li>
 * </ol>
 * <p>Не голос приглушается до «пола» — на {@code suppressDb}. На выходе моно (голос в центре).
 * Отделить голос от музыки, звучащей в том же месте и на тех же частотах (вокал в песне),
 * так нельзя — это умеют только нейросети.</p>
 *
 * <p>Задержка — 1024 сэмпла (~21 мс при 48 кГц). Чистая Java, проверяется обычными тестами.</p>
 */
// CHECKSTYLE:OFF — числовые константы алгоритма понятнее на месте, чем вынесенные в поля
final class VoiceIsolator {
    static final int N = 1024;
    private static final int H = N / 4;

    private final double sampleRate;
    private final int channels;
    private final int shadows;
    // окно: корень из Ханна для анализа и синтеза — вместе дают Ханна, при шаге N/4 сумма = 2
    private final double[] win = new double[N];
    private final double[] cos = new double[N / 2];
    private final double[] sin = new double[N / 2];
    private final int[] rev = new int[N];
    private final double[] bandG = new double[N / 2 + 1];

    // входной буфер и наложение для двух каналов анализа и для «теней» (только в тестах)
    private final double[][] in;
    private final double[][] ola;
    private final double[][] outBlock;
    private int pos;

    // по частотам: сглаженные мощности для оценки «центра», фон, сглаженный коэффициент
    private final double[] crossS = new double[N / 2 + 1];
    private final double[] powS = new double[N / 2 + 1];
    private final double[] sigS = new double[N / 2 + 1];
    private final double[] noise = new double[N / 2 + 1];
    private final double[] sigN = new double[N / 2 + 1];
    private final double[] gainS = new double[N / 2 + 1];
    private final double noiseRise;

    private final double[] reL = new double[N];
    private final double[] imL = new double[N];
    private final double[] reR = new double[N];
    private final double[] imR = new double[N];
    private final double[] reY = new double[N];
    private final double[] imY = new double[N];

    /**
     * @param sampleRate   частота дискретизации
     * @param channelCount сколько каналов в кадре
     * @param shadowCount  сколько дополнительных потоков обрабатывать с теми же коэффициентами
     *                     (для тестов: голос и музыка по отдельности); в плеере 0
     */
    VoiceIsolator(final double sampleRate, final int channelCount, final int shadowCount) {
        this.sampleRate = sampleRate;
        channels = channelCount;
        shadows = shadowCount;
        for (int i = 0; i < N; i++) {
            win[i] = Math.sqrt(0.5 - 0.5 * Math.cos(2 * Math.PI * i / N));
        }
        for (int i = 0; i < N / 2; i++) {
            cos[i] = Math.cos(2 * Math.PI * i / N);
            sin[i] = -Math.sin(2 * Math.PI * i / N);
        }
        final int bits = Integer.numberOfTrailingZeros(N);
        for (int i = 0; i < N; i++) {
            rev[i] = Integer.reverse(i) >>> (32 - bits);
        }
        for (int k = 0; k <= N / 2; k++) {
            final double f = k * sampleRate / N;
            double g;
            if (f < 100) {
                g = 0;
            } else if (f < 200) {
                g = 0.5 - 0.5 * Math.cos(Math.PI * (f - 100) / 100);
            } else if (f <= 4500) {
                g = 1;
            } else if (f < 7000) {
                g = 0.5 + 0.5 * Math.cos(Math.PI * (f - 4500) / 2500);
            } else {
                g = 0;
            }
            bandG[k] = g;
        }
        // фон может подниматься на ~3 dB в секунду (а опускается сразу)
        noiseRise = Math.pow(10, 0.3 * H / sampleRate);
        final int streams = 2 * (1 + shadowCount);
        in = new double[streams][N];
        ola = new double[streams][N];
        outBlock = new double[1 + shadowCount][H];
        java.util.Arrays.fill(noise, Double.NaN);
    }

    /** Сброс (после перемотки): старый звук не должен проскакивать. */
    void clear() {
        for (final double[] b : in) {
            java.util.Arrays.fill(b, 0);
        }
        for (final double[] b : ola) {
            java.util.Arrays.fill(b, 0);
        }
        for (final double[] b : outBlock) {
            java.util.Arrays.fill(b, 0);
        }
        pos = 0;
    }

    /**
     * Обрабатывает один кадр на месте: на выходе голос (моно во всех каналах), задержанный на N.
     *
     * @param frame      кадр, все каналы
     * @param suppressDb насколько приглушать всё, что не голос, dB
     * @param shadowFrames кадры «теней» — обрабатываются с теми же коэффициентами (тесты)
     */
    void process(final double[] frame, final double suppressDb, final double[]... shadowFrames) {
        push(0, frame);
        for (int s = 0; s < shadows; s++) {
            push(1 + s, shadowFrames[s]);
        }
        for (int c = 0; c < channels; c++) {
            frame[c] = outBlock[0][pos];
        }
        for (int s = 0; s < shadows; s++) {
            for (int c = 0; c < shadowFrames[s].length; c++) {
                shadowFrames[s][c] = outBlock[1 + s][pos];
            }
        }
        if (++pos == H) {
            pos = 0;
            hop(Math.pow(10, -Math.max(0, suppressDb) / 20));
        }
    }

    private void push(final int stream, final double[] f) {
        in[2 * stream][N - H + pos] = f[0];
        in[2 * stream + 1][N - H + pos] = f.length > 1 ? f[1] : f[0];
    }

    private void hop(final double floor) {
        // коэффициенты по частотам считаем по основному звуку
        load(in[0], reL, imL);
        load(in[1], reR, imR);
        fft(reL, imL);
        fft(reR, imR);
        final boolean stereo = channels > 1;
        for (int k = 0; k <= N / 2; k++) {
            final double l2 = reL[k] * reL[k] + imL[k] * imL[k];
            final double r2 = reR[k] * reR[k] + imR[k] * imR[k];
            final double cross = reL[k] * reR[k] + imL[k] * imR[k]; // Re(L·R*)
            final double mr = (reL[k] + reR[k]) / 2;
            final double mi = (imL[k] + imR[k]) / 2;
            final double m2 = mr * mr + mi * mi;
            // 1) центр: левый и правый совпадают по фазе и уровню
            double center = 1;
            if (stereo) {
                crossS[k] = 0.8 * crossS[k] + 0.2 * cross;
                powS[k] = 0.8 * powS[k] + 0.2 * (l2 + r2) / 2;
                final double c = Math.max(0, Math.min(1, crossS[k] / (powS[k] + 1e-20)));
                center = c * c * c * c; // строго: даже немного разнесённое по краям — не голос
            }
            // 3) не ровный фон: фильтр Винера над уровнем фона (минимумы с медленным подъёмом)
            sigS[k] = 0.7 * sigS[k] + 0.3 * m2;
            // фон — по минимумам более сглаженной мощности, ×2: минимум шума ниже его среднего,
            // и без поправки фон недооценивается и проходит
            sigN[k] = 0.9 * sigN[k] + 0.1 * m2;
            if (Double.isNaN(noise[k]) || sigN[k] * 2 < noise[k]) {
                noise[k] = sigN[k] * 2;
            } else {
                noise[k] *= noiseRise;
            }
            final double snr = Math.max(0, sigS[k] / (noise[k] + 1e-20) - 1);
            final double wiener = snr / (1 + snr);
            // 2) полоса речи
            final double g = center * bandG[k] * wiener;
            // быстро открываем, плавно закрываем — меньше «булькающих» призвуков
            gainS[k] = g > gainS[k] ? g : 0.6 * gainS[k] + 0.4 * g;
        }
        for (int s = 0; s < 1 + shadows; s++) {
            if (s > 0) {
                load(in[2 * s], reL, imL);
                load(in[2 * s + 1], reR, imR);
                fft(reL, imL);
                fft(reR, imR);
            }
            for (int k = 0; k <= N / 2; k++) {
                final double gk = floor + (1 - floor) * gainS[k];
                reY[k] = (reL[k] + reR[k]) / 2 * gk;
                imY[k] = (imL[k] + imR[k]) / 2 * gk;
                if (k > 0 && k < N / 2) { // сопряжённая половина — для вещественного результата
                    reY[N - k] = reY[k];
                    imY[N - k] = -imY[k];
                }
            }
            // обратное преобразование через прямое: conj(FFT(conj(Y)))
            for (int i = 0; i < N; i++) {
                imY[i] = -imY[i];
            }
            fft(reY, imY);
            final double[] o = ola[2 * s];
            for (int i = 0; i < N; i++) {
                o[i] += reY[i] / N * win[i] * 0.5;
            }
            System.arraycopy(o, 0, outBlock[s], 0, H);
            System.arraycopy(o, H, o, 0, N - H);
            java.util.Arrays.fill(o, N - H, N, 0);
        }
        for (final double[] b : in) {
            System.arraycopy(b, H, b, 0, N - H);
        }
    }

    private void load(final double[] src, final double[] re, final double[] im) {
        for (int i = 0; i < N; i++) {
            re[i] = src[i] * win[i];
            im[i] = 0;
        }
    }

    /** Быстрое преобразование Фурье на месте (радикс-2). */
    private void fft(final double[] re, final double[] im) {
        for (int i = 0; i < N; i++) {
            final int j = rev[i];
            if (j > i) {
                double t = re[i];
                re[i] = re[j];
                re[j] = t;
                t = im[i];
                im[i] = im[j];
                im[j] = t;
            }
        }
        for (int size = 2; size <= N; size <<= 1) {
            final int half = size / 2;
            final int step = N / size;
            for (int start = 0; start < N; start += size) {
                for (int j = 0; j < half; j++) {
                    final double wr = cos[j * step];
                    final double wi = sin[j * step];
                    final int a = start + j;
                    final int b = a + half;
                    final double tr = re[b] * wr - im[b] * wi;
                    final double ti = re[b] * wi + im[b] * wr;
                    re[b] = re[a] - tr;
                    im[b] = im[a] - ti;
                    re[a] += tr;
                    im[a] += ti;
                }
            }
        }
    }
}
// CHECKSTYLE:ON
