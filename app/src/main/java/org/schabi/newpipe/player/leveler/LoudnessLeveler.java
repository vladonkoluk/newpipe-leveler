package org.schabi.newpipe.player.leveler;

/**
 * Выравнивание громкости («ночной режим»): тихое громче, громкое тише.
 *
 * <p>Цепочка, как в вещательных процессорах:</p>
 * <ol>
 *     <li>AGC — медленное автоусиление: меряет громкость (LUFS, K-взвешивание BS.1770) и
 *     подтягивает её к цели. Громче — убавляет быстро, тише — прибавляет плавно.</li>
 *     <li>Выравнивание внутри ролика по окну 0.2 с: шёпот вверх, громкие сцены вниз.
 *     Паузы между фразами и ровный фон не вытягивает.</li>
 *     <li>Компрессор — быстро прижимает внезапные громкие места.</li>
 *     <li>Лимитер с заглядыванием вперёд на 5 мс — пики не выше потолка.</li>
 * </ol>
 * <p>Громкость меряется по звуку на 20 мс раньше, чем он звучит: так усиление успевает
 * убавиться к началу выкрика, хлопка или удара, а не через 20–30 мс после.</p>
 *
 * <p>Чистая Java без зависимостей от Android, чтобы проверять алгоритм обычными тестами.
 * Задержка звука — 25 мс (20 мс заглядывания + 5 мс лимитера).</p>
 */
// CHECKSTYLE:OFF — числовые константы алгоритма понятнее на месте, чем вынесенные в поля
public final class LoudnessLeveler {

    /** Настройки. Поля volatile: их меняют из настроек во время воспроизведения. */
    public static final class Params {
        public volatile boolean enabled = true;
        /** Целевая громкость, LUFS. */
        public volatile double target = -16;
        /** Максимум усиления тихого, dB. */
        public volatile double maxBoost = 24;
        /** Максимум ослабления, dB. */
        public volatile double maxCut = 30;
        /** Скорость медленного автоусиления, сек. */
        public volatile double agcTime = 3;
        /** Сила выравнивания внутри ролика: 0 — выкл, 1 — всё к одному уровню. */
        public volatile double strength = 1;
        /** Как быстро убавляет при громком, сек. */
        public volatile double levAtt = 0.05;
        /** Как быстро прибавляет при тихом, сек. */
        public volatile double levRel = 0.2;
        /** Окно измерения для выравнивания внутри ролика, сек. */
        public volatile double levTime = 0.2;
        /** Тише этого (LUFS на входе) — тишина, усиление не трогаем. */
        public volatile double gate = -52;
        /** Тише недавней речи на столько dB (и без слогов) — пауза: усиление почти не растёт. */
        public volatile double pauseDb = 10;
        /** Как медленно прибавляет в паузах, сек. */
        public volatile double pauseRel = 3;
        /** Как быстро «забывается» громкость недавней речи, dB/сек. */
        public volatile double speechFall = 10;
        /** Порог компрессора над целью, dB. */
        public volatile double compAbove = 4;
        /** Степень сжатия компрессора. */
        public volatile double ratio = 4;
        /** Потолок лимитера, dBFS. */
        public volatile double ceiling = -1;
        /** Как быстро компрессор отпускает после громкого места, сек. */
        public volatile double compRel = 0.25;
        /** Время реакции детектора компрессора, сек (меньше — ловит более короткие звуки). */
        public volatile double envTime = 0.02;
        /** Сколько dB сверх ослабления компрессором можно вернуть подтяжкой уровня. */
        public volatile double makeupExtra = 1;
        /** «Разборчивая речь»: голос громче музыки и фона (см. {@link SpeechClarity}). */
        public volatile boolean clarity = false;
    }

    /** Шаг управляющих вычислений, сэмплов. */
    private static final int CTRL = 16;

    static double toDb(final double power) {
        return 10 * Math.log10(power + 1e-20);
    }

    static double fromDb(final double db) {
        return Math.pow(10, db / 20);
    }

    static double coef(final double sec, final double rate) {
        return 1 - Math.exp(-1 / (sec * rate));
    }

    static double clamp(final double x, final double lo, final double hi) {
        return Math.min(hi, Math.max(lo, x));
    }

    private static final class Biquad {
        private final double b0;
        private final double b1;
        private final double b2;
        private final double a1;
        private final double a2;
        private double z1;
        private double z2;

        Biquad(final double nb0, final double nb1, final double nb2,
               final double a0, final double na1, final double na2) {
            b0 = nb0 / a0;
            b1 = nb1 / a0;
            b2 = nb2 / a0;
            a1 = na1 / a0;
            a2 = na2 / a0;
        }

        double run(final double x) {
            final double y = b0 * x + z1;
            z1 = b1 * x - a1 * y + z2;
            z2 = b2 * x - a2 * y;
            return y;
        }
    }

    /** K-взвешивание ITU-R BS.1770: полка +4 dB от 1.5 кГц и срез ниже 38 Гц. */
    private static Biquad[] kWeighting(final double sr) {
        double w = 2 * Math.PI * 1500 / sr;
        double cw = Math.cos(w);
        final double a = Math.pow(10, 4.0 / 40);
        double al = Math.sin(w) / (2 * Math.sqrt(0.5));
        final double sA = 2 * Math.sqrt(a) * al;
        final Biquad shelf = new Biquad(
                a * ((a + 1) + (a - 1) * cw + sA), -2 * a * ((a - 1) + (a + 1) * cw),
                a * ((a + 1) + (a - 1) * cw - sA),
                (a + 1) - (a - 1) * cw + sA, 2 * ((a - 1) - (a + 1) * cw),
                (a + 1) - (a - 1) * cw - sA);
        w = 2 * Math.PI * 38 / sr;
        cw = Math.cos(w);
        al = Math.sin(w) / (2 * 0.5);
        final Biquad hp = new Biquad((1 + cw) / 2, -(1 + cw), (1 + cw) / 2,
                1 + al, -2 * cw, 1 - al);
        return new Biquad[]{shelf, hp};
    }

    /**
     * «Разборчивая речь», как «улучшение диалогов» в телевизорах:
     * <ul>
     *     <li>стерео-фон по краям тише на 12 dB — голос почти всегда в центре, а музыка и шум
     *     часто разнесены по краям;</li>
     *     <li>срез ниже 150 Гц — гул и басы, в которых почти нет речи, но которые её забивают;</li>
     *     <li>+6 dB около 2.5 кГц — частоты, от которых зависит разборчивость.</li>
     * </ul>
     * Работает до выравнивания, так что общая громкость остаётся прежней.
     * Включается и выключается плавно, за ~10 мс.
     */
    static final class SpeechClarity {
        /** Во сколько раз тише стерео-фон по краям (−12 dB). */
        static final double SIDE_GAIN = 0.25;

        private final int channels;
        private final Biquad[] hp;
        private final Biquad[] presence;
        private final double cMix;
        private final double[] wet;
        private double mix;

        SpeechClarity(final double sr, final int channelCount) {
            channels = channelCount;
            hp = new Biquad[channelCount];
            presence = new Biquad[channelCount];
            for (int c = 0; c < channelCount; c++) {
                double w = 2 * Math.PI * 150 / sr;
                double cw = Math.cos(w);
                double al = Math.sin(w) / (2 * Math.sqrt(0.5));
                hp[c] = new Biquad((1 + cw) / 2, -(1 + cw), (1 + cw) / 2, 1 + al, -2 * cw, 1 - al);
                w = 2 * Math.PI * 2500 / sr;
                cw = Math.cos(w);
                al = Math.sin(w) / (2 * 0.9);
                final double a = Math.pow(10, 6.0 / 40);
                presence[c] = new Biquad(1 + al * a, -2 * cw, 1 - al * a,
                        1 + al / a, -2 * cw, 1 - al / a);
            }
            cMix = coef(0.01, sr);
            wet = new double[channelCount];
        }

        /** Обрабатывает один кадр (все каналы) на месте. */
        void process(final double[] frame, final boolean on) {
            mix += ((on ? 1 : 0) - mix) * cMix;
            if (channels == 2) {
                final double m = (frame[0] + frame[1]) / 2;
                final double side = (frame[0] - frame[1]) / 2 * SIDE_GAIN;
                wet[0] = m + side;
                wet[1] = m - side;
            } else {
                System.arraycopy(frame, 0, wet, 0, channels);
            }
            // фильтры работают всегда, чтобы при включении не было щелчка от пустого состояния
            for (int c = 0; c < channels; c++) {
                final double y = presence[c].run(hp[c].run(wet[c]));
                frame[c] += (y - frame[c]) * mix;
            }
        }
    }

    /** Считает нужное усиление (dB) по громкости. */
    static final class GainComputer {
        private final Params p;
        private double longDb = -70;
        private double active;
        private double gatedFor;
        private double agcDb;
        private double levDb;
        private double speechDb = -70;
        // короткие блоки по 25 мс за последние 0.15 с — отличать речь (скачет по слогам) от фона.
        // Окно короткое, чтобы тихое слово сразу после громкого не принималось за паузу.
        private final double[] blk = new double[6];
        private int blkPos;
        private double blkAcc;
        private double blkT;

        GainComputer(final Params params) {
            p = params;
            java.util.Arrays.fill(blk, -100);
        }

        /**
         * @param mom громкость за 0.4 с, LUFS
         * @param lev громкость за окно 0.2 с, LUFS
         * @param dt  шаг, сек
         * @param pow средняя мощность за этот шаг
         * @return нужное усиление, dB
         */
        double step(final double mom, final double lev, final double dt, final double pow) {
            final double rate = 1 / dt;
            // гейт: тишину и паузы не учитываем; долгое тихое (не тишина) — новая сцена
            boolean use = false;
            if (mom > -50) {
                if (active <= 0 || mom > longDb - 20) {
                    use = true;
                } else if ((gatedFor += dt) > 4) {
                    use = true;
                }
            } else {
                gatedFor = 0;
            }
            if (use) {
                gatedFor = 0;
                if (active <= 0) {
                    longDb = mom;
                }
                // громче — оценка растёт быстро, тише — падает плавно
                final double tau = mom > longDb ? p.agcTime / 4 : p.agcTime;
                final double t = clamp(active, 0.4, tau);
                longDb += (mom - longDb) * coef(t, rate);
                active += dt;
            }
            final double want = active > 0 ? clamp(p.target - longDb, -p.maxCut, p.maxBoost) : 0;
            agcDb += (want - agcDb) * coef(0.05, rate);

            blkAcc += pow * dt;
            blkT += dt;
            if (blkT >= 0.025) {
                blk[blkPos] = -0.691 + toDb(blkAcc / blkT);
                blkPos = (blkPos + 1) % blk.length;
                blkAcc = 0;
                blkT = 0;
            }
            double bMax = -100;
            double bMin = 100;
            for (final double b : blk) {
                bMax = Math.max(bMax, b);
                bMin = Math.min(bMin, b);
            }
            // похоже на речь: громкость скачет (слоги) и сейчас звучит не хвост громкой фразы
            final boolean speechLike = bMax - bMin > 8 && lev > bMax - 10;

            // громкость недавней речи: громкое догоняет быстро, забывается плавно
            if (lev > speechDb) {
                speechDb += (lev - speechDb) * coef(0.05, rate);
            } else {
                speechDb = Math.max(lev, speechDb - p.speechFall * dt);
            }

            // выравнивание внутри ролика: тихое вверх, громкое вниз
            if (lev > p.gate && active > 0) {
                final double wantL = clamp(p.strength * (p.target - (lev + agcDb)),
                        -p.maxCut - agcDb, p.maxBoost - agcDb);
                // в паузе между фразами прибавляем очень медленно, чтобы не вытягивать фон
                final boolean pause = !speechLike && lev < speechDb - p.pauseDb;
                final double tau = wantL < levDb ? p.levAtt : pause ? p.pauseRel : p.levRel;
                levDb += (wantL - levDb) * coef(tau, rate);
            } // в тишине держим усиление как есть
            return agcDb + levDb;
        }
    }

    private final Params p;
    private final int sampleRate;
    private final int channels;
    private final Biquad[][] kw;
    private final Biquad[][] kwOut; // K-взвешивание итогового звука — для подтяжки уровня
    private final double cMom;
    private double cEnv;
    private double cEnvFor = -1;
    private final double cGain;
    private final double cMix;
    private final double cLimRel;
    private final double cCompAtt;
    private final GainComputer gc;

    private double momPow;
    private double envPow;
    private double levBlock;
    private double[] levRing;
    private final double ctrlRate;
    private double levSum;
    private int levPos;
    private double compDb;
    // громкость на выходе (до лимитера) за 0.4 с и подтяжка к цели после сжатия
    private double outPow;
    private double makeupDb;
    private double compAvgDb; // сколько в среднем убирает компрессор на речи
    private double outLongDb = Double.NaN; // громкость итогового звука за ~3 с
    private double gTarget = 1;
    private double g = 1;
    private double mix = 1;
    private int tick;

    // заглядывание вперёд: громкость меряется по текущему звуку, а применяется к звуку 20 мс назад
    private final int preDelay;
    private final float[][] preRing;
    private int prePos;
    private final double[] xs;
    private final double[] frame;
    private final SpeechClarity clarity;

    // лимитер: задержка L, скользящий минимум по L+1, скользящее среднее по L
    private final int lookahead;
    private final float[][] yRing;
    private final float[][] xRing;
    private final double[] dqV;
    private final long[] dqI;
    private int dqHead;
    private int dqLen;
    private final double[] avgRing;
    private double avgSum;
    private int pos;
    private long n;
    private double h2 = 1;

    /** Текущая поправка громкости, dB (для показа). */
    private volatile double gainDb;

    public LoudnessLeveler(final int sampleRate, final int channelCount, final Params params) {
        p = params;
        this.sampleRate = sampleRate;
        channels = channelCount;
        kw = new Biquad[channelCount][];
        kwOut = new Biquad[channelCount][];
        for (int c = 0; c < channelCount; c++) {
            kw[c] = kWeighting(sampleRate);
            kwOut[c] = kWeighting(sampleRate);
        }
        final double cr = (double) sampleRate / CTRL;
        cMom = coef(0.4, sampleRate);
        cGain = coef(0.001, sampleRate);
        cMix = coef(0.01, sampleRate);
        cLimRel = coef(0.08, sampleRate);
        cCompAtt = coef(0.005, cr);
        gc = new GainComputer(params);
        ctrlRate = cr;
        levRing = new double[levRingSize()];

        preDelay = Math.max(1, (int) Math.round(0.02 * sampleRate));
        preRing = new float[channelCount][preDelay];
        xs = new double[channelCount];
        frame = new double[channelCount];
        clarity = new SpeechClarity(sampleRate, channelCount);
        lookahead = Math.max(1, (int) Math.round(0.005 * sampleRate));
        yRing = new float[channelCount][lookahead];
        xRing = new float[channelCount][lookahead];
        dqV = new double[lookahead + 2];
        dqI = new long[lookahead + 2];
        avgRing = new double[lookahead];
        clearDelay();
    }

    /** Сброс линии задержки (после перемотки), усиление при этом сохраняется. */
    public void clearDelay() {
        for (int c = 0; c < channels; c++) {
            java.util.Arrays.fill(preRing[c], 0);
            java.util.Arrays.fill(yRing[c], 0);
            java.util.Arrays.fill(xRing[c], 0);
        }
        prePos = 0;
        java.util.Arrays.fill(avgRing, 1);
        avgSum = lookahead;
        dqHead = 0;
        dqLen = 0;
        pos = 0;
        h2 = 1;
    }

    public int getSampleRate() {
        return sampleRate;
    }

    public int getChannelCount() {
        return channels;
    }

    /** Текущая поправка громкости, dB. */
    public double getGainDb() {
        return gainDb;
    }

    /** Скользящий минимум (монотонная очередь). */
    private double slidingMin(final double v) {
        final int cap = lookahead + 2;
        final int window = lookahead + 1;
        while (dqLen > 0) {
            final int back = (dqHead + dqLen - 1) % cap;
            if (dqV[back] >= v) {
                dqLen--;
            } else {
                break;
            }
        }
        final int t = (dqHead + dqLen) % cap;
        dqV[t] = v;
        dqI[t] = n;
        dqLen++;
        if (dqI[dqHead] <= n - window) {
            dqHead = (dqHead + 1) % cap;
            dqLen--;
        }
        return dqV[dqHead];
    }

    private int levRingSize() {
        return Math.max(1, (int) Math.round(p.levTime * ctrlRate));
    }

    private void control(final double sampleRate) {
        final double dt = CTRL / sampleRate;
        if (levRing.length != levRingSize()) { // окно поменяли в настройках
            final double avg = levSum / levRing.length;
            levRing = new double[levRingSize()];
            java.util.Arrays.fill(levRing, avg);
            levSum = avg * levRing.length;
            levPos = 0;
        }
        final double mom = -0.691 + toDb(momPow);
        final double bp = levBlock / CTRL; // мощность за этот шаг
        levBlock = 0;
        levSum += bp - levRing[levPos];
        levRing[levPos] = bp;
        if (++levPos == levRing.length) { // пересчёт суммы против накопления ошибки
            levPos = 0;
            double s = 0;
            for (final double v : levRing) {
                s += v;
            }
            levSum = s;
        }
        final double lev = -0.691 + toDb(Math.max(0, levSum) / levRing.length);
        final double levelDb = gc.step(mom, lev, dt, bp);

        // компрессор с мягким коленом
        final double lvl = -0.691 + toDb(envPow) + levelDb;
        final double over = lvl - (p.target + p.compAbove);
        final double knee = 6;
        final double s = 1 / Math.max(1, p.ratio) - 1;
        double red = 0;
        if (over >= knee / 2) {
            red = s * over;
        } else if (over > -knee / 2) {
            red = s * (over + knee / 2) * (over + knee / 2) / (2 * knee);
        }
        compDb += (red - compDb) * (red < compDb ? cCompAtt : coef(p.compRel, ctrlRate));
        // В сильных режимах сжатие и быстрое выравнивание делали звук тише выбранного уровня
        // на 2–6 dB. Меряем среднюю (за ~3 с) громкость итогового звука и медленно подтягиваем
        // её к цели. Подтяжка не больше того, что в среднем убрал компрессор, плюс запас режима
        // (makeupExtra): поэтому одинакова в тихих и громких сценах и не раздувает тихие ролики.
        // Короткие хлопки на среднюю громкость почти не влияют; паузы и тишину не меряем.
        final double outDb = -0.691 + toDb(outPow);
        // резкие звуки (компрессор сильно прижимает или на выходе намного громче цели)
        // не учитываем, иначе они раскачивают подтяжку
        if (lev > p.gate && mom > -50 && compDb > -6 && outDb < p.target + 6) {
            compAvgDb += (compDb - compAvgDb) * coef(2, ctrlRate);
            outLongDb = Double.isNaN(outLongDb) ? outDb
                    : outLongDb + (outDb - outLongDb) * coef(3, ctrlRate);
            makeupDb = clamp(makeupDb + (p.target - outLongDb) * coef(3, ctrlRate),
                    0, Math.min(12, Math.max(0, -compAvgDb) + p.makeupExtra));
        }
        gTarget = fromDb(levelDb + compDb + makeupDb);
        gainDb = levelDb + compDb + makeupDb;
    }

    /**
     * Обрабатывает звук на месте.
     *
     * @param buf        чередующиеся 16-битные сэмплы (кадр = все каналы подряд)
     * @param frames     число кадров
     * @param sampleRate частота дискретизации
     */
    public void process(final short[] buf, final int frames, final int sampleRate) {
        final int ch = channels;
        final double ceil = fromDb(p.ceiling);
        final double mixTarget = p.enabled ? 1 : 0;
        final int len = lookahead;
        for (int f = 0; f < frames; f++) {
            final int base = f * ch;
            for (int c = 0; c < ch; c++) {
                frame[c] = buf[base + c] / 32768.0;
            }
            // «Ровная громкость» выключена — выключается и «Разборчивая речь»: звук как в оригинале
            clarity.process(frame, p.clarity && p.enabled);
            // xs — звук на 20 мс позже, к нему и применяется усиление
            for (int c = 0; c < ch; c++) {
                xs[c] = preRing[c][prePos];
                preRing[c][prePos] = (float) frame[c];
            }
            prePos = prePos + 1 == preDelay ? 0 : prePos + 1;
            // громкость меряется по звуку на 20 мс раньше, чем он прозвучит
            double pk = 0;
            for (int c = 0; c < ch; c++) {
                final double k = kw[c][1].run(kw[c][0].run(frame[c]));
                pk += k * k;
            }
            momPow += (pk - momPow) * cMom;
            if (p.envTime != cEnvFor) { // время детектора меняют в настройках
                cEnvFor = p.envTime;
                cEnv = coef(cEnvFor, sampleRate);
            }
            envPow += (pk - envPow) * cEnv;
            levBlock += pk;
            if (++tick >= CTRL) {
                tick = 0;
                control(sampleRate);
            }
            g += (gTarget - g) * cGain;

            double peak = 0;
            for (int c = 0; c < ch; c++) {
                peak = Math.max(peak, Math.abs(xs[c] * g));
            }
            final double req = peak > ceil ? ceil / peak : 1;
            final double h = slidingMin(req);
            h2 = Math.min(h, h2 + (1 - h2) * cLimRel);
            avgSum += h2 - avgRing[pos];
            avgRing[pos] = h2;
            final double lim = avgSum / len;

            mix += (mixTarget - mix) * cMix;
            final double m = mix;
            double outPk = 0;
            for (int c = 0; c < ch; c++) {
                final double x = xs[c];
                final double yd = yRing[c][pos];
                final double xd = xRing[c][pos];
                yRing[c][pos] = (float) (x * g);
                xRing[c][pos] = (float) x;
                double y = m * yd * lim + (1 - m) * xd;
                if (m > 0.999) {
                    y = clamp(y, -ceil, ceil);
                }
                buf[base + c] = (short) Math.round(clamp(y * 32768, -32768, 32767));
                final double k = kwOut[c][1].run(kwOut[c][0].run(y));
                outPk += k * k;
            }
            outPow += (outPk - outPow) * cMom;
            pos = pos + 1 == len ? 0 : pos + 1;
            if (pos == 0) { // пересчёт суммы против накопления ошибки
                double s = 0;
                for (final double v : avgRing) {
                    s += v;
                }
                avgSum = s;
            }
            n++;
        }
    }
}
// CHECKSTYLE:ON
