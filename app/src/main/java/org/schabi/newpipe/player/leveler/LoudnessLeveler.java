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
 *
 * <p>Чистая Java без зависимостей от Android, чтобы проверять алгоритм обычными тестами.
 * Задержка звука — 5 мс.</p>
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
    private final double cMom;
    private final double cEnv;
    private final double cGain;
    private final double cMix;
    private final double cLimRel;
    private final double cCompAtt;
    private final double cCompRel;
    private final GainComputer gc;

    private double momPow;
    private double envPow;
    private double levBlock;
    private double[] levRing;
    private final double ctrlRate;
    private double levSum;
    private int levPos;
    private double compDb;
    private double gTarget = 1;
    private double g = 1;
    private double mix = 1;
    private int tick;

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
        for (int c = 0; c < channelCount; c++) {
            kw[c] = kWeighting(sampleRate);
        }
        final double cr = (double) sampleRate / CTRL;
        cMom = coef(0.4, sampleRate);
        cEnv = coef(0.02, sampleRate);
        cGain = coef(0.001, sampleRate);
        cMix = coef(0.01, sampleRate);
        cLimRel = coef(0.08, sampleRate);
        cCompAtt = coef(0.005, cr);
        cCompRel = coef(0.25, cr);
        gc = new GainComputer(params);
        ctrlRate = cr;
        levRing = new double[levRingSize()];

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
            java.util.Arrays.fill(yRing[c], 0);
            java.util.Arrays.fill(xRing[c], 0);
        }
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
        compDb += (red - compDb) * (red < compDb ? cCompAtt : cCompRel);
        gTarget = fromDb(levelDb + compDb);
        gainDb = levelDb + compDb;
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
            double pk = 0;
            for (int c = 0; c < ch; c++) {
                final double x = buf[base + c] / 32768.0;
                final double k = kw[c][1].run(kw[c][0].run(x));
                pk += k * k;
            }
            momPow += (pk - momPow) * cMom;
            envPow += (pk - envPow) * cEnv;
            levBlock += pk;
            if (++tick >= CTRL) {
                tick = 0;
                control(sampleRate);
            }
            g += (gTarget - g) * cGain;

            double peak = 0;
            for (int c = 0; c < ch; c++) {
                peak = Math.max(peak, Math.abs(buf[base + c] / 32768.0 * g));
            }
            final double req = peak > ceil ? ceil / peak : 1;
            final double h = slidingMin(req);
            h2 = Math.min(h, h2 + (1 - h2) * cLimRel);
            avgSum += h2 - avgRing[pos];
            avgRing[pos] = h2;
            final double lim = avgSum / len;

            mix += (mixTarget - mix) * cMix;
            final double m = mix;
            for (int c = 0; c < ch; c++) {
                final double x = buf[base + c] / 32768.0;
                final double yd = yRing[c][pos];
                final double xd = xRing[c][pos];
                yRing[c][pos] = (float) (x * g);
                xRing[c][pos] = (float) x;
                double y = m * yd * lim + (1 - m) * xd;
                if (m > 0.999) {
                    y = clamp(y, -ceil, ceil);
                }
                buf[base + c] = (short) Math.round(clamp(y * 32768, -32768, 32767));
            }
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
