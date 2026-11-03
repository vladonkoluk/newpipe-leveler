package org.schabi.newpipe.player.leveler;

/**
 * Шумоподавление нейросетью RNNoise: убирает уличный шум, гул, ветер, шаги и прочие помехи,
 * оставляя голос.
 *
 * <p>Звук сводится в моно (голос почти всегда в центре), пересчитывается в 48 кГц (RNNoise
 * работает только с этой частотой), обрабатывается кадрами по 10 мс (480 сэмплов),
 * пересчитывается обратно и записывается во все каналы. Задержка постоянная (около 30–40 мс
 * вместе с самой нейросетью), чтобы звук шёл без разрывов.</p>
 *
 * <p>«Беречь речь»: в громком шуме нейросеть, сомневаясь, глушит вместе с шумом и куски
 * речи. Поэтому там, где она уверена, что звучит речь (оценка речи 0.6–0.95, удержание 50 мс),
 * к результату подмешивается до {@code keepSpeech} оригинала, выровненного по её задержке.
 * В паузах подмешивания нет — шум остаётся убранным. На тестах с настоящей улицей
 * («Сильное», громкий шум): речи пропадало 20%, с подмешиванием до 70% — 5%,
 * шум в паузах −25 dB вместо −52.</p>
 *
 * <p>Сама нейросеть подключается через {@link Engine}, поэтому этот класс — чистая Java
 * и проверяется обычными тестами.</p>
 */
// CHECKSTYLE:OFF — числовые константы алгоритма понятнее на месте, чем вынесенные в поля
final class NoiseSuppressor {
    /** RNNoise работает с 48 кГц кадрами по 480 сэмплов. */
    static final int RATE = 48000;
    static final int FRAME = 480;

    /** Нейросеть: обрабатывает кадр 480 сэмплов (шкала 16-битного звука) на месте. */
    interface Engine {
        /** @return вероятность речи в кадре, 0..1 */
        float process(float[] frame);

        /** На сколько кадров (по 10 мс) нейросеть задерживает звук: 0.1.1 — 1, новая — 2. */
        int delayFrames();

        void release();
    }

    private final int channels;
    private final Engine engine;
    private final Resampler toModel;   // частота звука -> 48 кГц (null, если уже 48 кГц)
    private final Resampler fromModel; // 48 кГц -> частота звука
    private final float[] frame = new float[FRAME];
    private int framePos;
    private final double[] tmp;
    private final double[] back;
    // очередь готового звука на выходе: заранее заполнена тишиной на величину задержки
    private final double[] fifo;
    private int fifoRead;
    private int fifoWrite;
    private int fifoCount;
    private final int prefill;
    private volatile float speechProbability;

    // «Беречь речь»: оригинал прошлых кадров (выравнивание по задержке нейросети),
    // её оценки речи и плавный коэффициент подмешивания
    private final float[][] dryHist;
    private int dryPos;
    private final float[] vadHist;
    private int vadPos;
    private double keep;
    private double keepPrev;
    private int hold;
    private volatile double keepSpeech;
    NoiseSuppressor(final int sampleRate, final int channelCount, final Engine engine) {
        channels = channelCount;
        this.engine = engine;
        if (sampleRate == RATE) {
            toModel = null;
            fromModel = null;
        } else {
            toModel = new Resampler(sampleRate, RATE);
            fromModel = new Resampler(RATE, sampleRate);
        }
        tmp = new double[toModel == null ? 1 : toModel.maxOutPerIn()];
        back = new double[fromModel == null ? 1 : fromModel.maxOutPerIn()];
        // кадр копится до 480 сэмплов + запас на фильтры пересчёта
        final double perModel = (double) sampleRate / RATE;
        prefill = (int) Math.ceil(FRAME * perModel)
                + (toModel == null ? 0 : (int) Math.ceil(toModel.delay() * perModel + 2))
                + (fromModel == null ? 0 : (int) Math.ceil(fromModel.delay() + 2)) + 4;
        fifo = new double[prefill + (int) Math.ceil(FRAME * perModel) * 2 + 64];
        final int lag = Math.max(0, engine.delayFrames());
        dryHist = new float[lag + 1][FRAME];
        vadHist = new float[Math.max(1, lag - 1)];
        clear();
    }

    /** Задержка звука, в сэмплах исходной частоты (без внутренней задержки нейросети). */
    int bufferDelay() {
        return prefill;
    }

    /** Вероятность речи в последнем кадре, 0..1. */
    float getSpeechProbability() {
        return speechProbability;
    }

    /** Сколько оригинала подмешивать там, где звучит речь: 0 — выкл, 0.7 — до 70%. */
    void setKeepSpeech(final double amount) {
        keepSpeech = Math.max(0, Math.min(1, amount));
    }

    void clear() {
        for (final float[] f : dryHist) {
            java.util.Arrays.fill(f, 0);
        }
        java.util.Arrays.fill(vadHist, 0);
        dryPos = 0;
        vadPos = 0;
        keep = 0;
        keepPrev = 0;
        hold = 0;
        framePos = 0;
        fifoRead = 0;
        fifoWrite = 0;
        fifoCount = 0;
        for (int i = 0; i < prefill; i++) {
            put(0);
        }
        if (toModel != null) {
            toModel.clear();
            fromModel.clear();
        }
    }

    void release() {
        engine.release();
    }

    /**
     * Обрабатывает звук на месте: на выходе очищенный голос (моно во всех каналах),
     * задержанный на постоянную величину.
     */
    void process(final short[] buf, final int frames) {
        for (int f = 0; f < frames; f++) {
            final int base = f * channels;
            double mono = 0;
            for (int c = 0; c < channels; c++) {
                mono += buf[base + c];
            }
            mono /= channels;
            if (toModel == null) {
                feed(mono);
            } else {
                final int n = toModel.push(mono, tmp, 0);
                for (int i = 0; i < n; i++) {
                    feed(tmp[i]);
                }
            }
            final double y = fifoCount > 0 ? take() : 0;
            final short s = (short) Math.max(-32768, Math.min(32767, Math.round(y)));
            for (int c = 0; c < channels; c++) {
                buf[base + c] = s;
            }
        }
    }

    /** Сэмпл 48 кГц на вход нейросети; по готовности кадра — обработка и выдача на выход. */
    private void feed(final double x) {
        frame[framePos++] = (float) x;
        if (framePos < FRAME) {
            return;
        }
        framePos = 0;
        // оригинал этого кадра — в историю; на выход пойдёт оригинал того кадра,
        // который нейросеть отдаёт сейчас (она задерживает звук на delayFrames кадров)
        System.arraycopy(frame, 0, dryHist[dryPos], 0, FRAME);
        dryPos = dryPos + 1 == dryHist.length ? 0 : dryPos + 1;
        final float[] dry = dryHist[dryPos]; // самый старый
        final float vad = engine.process(frame);
        speechProbability = vad;
        // оценка речи для отдаваемого кадра — на кадр раньше текущей (у новой RNNoise)
        final float vadOut = vadHist[vadPos];
        vadHist[vadPos] = vad;
        vadPos = vadPos + 1 == vadHist.length ? 0 : vadPos + 1;
        final double v = engine.delayFrames() >= 2 ? vadOut : vad;
        final double g = Math.max(0, Math.min(1, (v - 0.6) / 0.35));
        keepPrev = keep;
        if (g >= keep) { // при речи — сразу, после неё — удержание 50 мс и плавный спад
            keep = g;
            hold = 5;
        } else if (hold > 0) {
            hold--;
        } else {
            keep += (g - keep) * 0.15;
        }
        final double amount = keepSpeech;
        for (int i = 0; i < FRAME; i++) {
            final double mixDry = amount * (keepPrev + (keep - keepPrev) * (i + 0.5) / FRAME);
            final double y = (1 - mixDry) * frame[i] + mixDry * dry[i];
            if (fromModel == null) {
                put(y);
            } else {
                final int n = fromModel.push(y, back, 0);
                for (int k = 0; k < n; k++) {
                    put(back[k]);
                }
            }
        }
    }

    private void put(final double v) {
        if (fifoCount == fifo.length) { // не должно случаться; на всякий случай — сбросить старое
            take();
        }
        fifo[fifoWrite] = v;
        fifoWrite = fifoWrite + 1 == fifo.length ? 0 : fifoWrite + 1;
        fifoCount++;
    }

    private double take() {
        final double v = fifo[fifoRead];
        fifoRead = fifoRead + 1 == fifo.length ? 0 : fifoRead + 1;
        fifoCount--;
        return v;
    }
}
// CHECKSTYLE:ON
