package org.schabi.newpipe.player.leveler;

/**
 * Пересчёт частоты дискретизации в рациональное число раз (например, 44100 ↔ 48000 Гц),
 * поток по одному сэмплу. Многофазный фильтр: окно Блэкмана × sinc, 32 отвода на фазу,
 * срез чуть ниже половины меньшей из частот.
 */
// CHECKSTYLE:OFF — числовые константы алгоритма понятнее на месте, чем вынесенные в поля
final class Resampler {
    private static final int TAPS = 32;

    private final int up;
    private final int down;
    private final double[] h; // фазы подряд: h[phase * TAPS + k]
    private final double[] hist = new double[TAPS];
    private int histPos;
    private long inCount;
    private long outCount;

    Resampler(final int fromRate, final int toRate) {
        final int g = gcd(fromRate, toRate);
        up = toRate / g;
        down = fromRate / g;
        final int n = TAPS * up;
        final double fc = 0.5 / Math.max(up, down) * 0.92; // доля частоты «растянутого» потока
        final double[] proto = new double[n];
        final double mid = (n - 1) / 2.0;
        for (int i = 0; i < n; i++) {
            final double t = i - mid;
            final double sinc = t == 0 ? 2 * fc : Math.sin(2 * Math.PI * fc * t) / (Math.PI * t);
            final double w = 0.42 - 0.5 * Math.cos(2 * Math.PI * i / (n - 1))
                    + 0.08 * Math.cos(4 * Math.PI * i / (n - 1));
            proto[i] = sinc * w * up;
        }
        h = new double[n];
        for (int p = 0; p < up; p++) {
            for (int k = 0; k < TAPS; k++) {
                h[p * TAPS + k] = proto[p + k * up];
            }
        }
    }

    private static int gcd(final int a, final int b) {
        return b == 0 ? a : gcd(b, a % b);
    }

    /** Задержка на выходе, в сэмплах выходной частоты. */
    double delay() {
        return (TAPS * up - 1) / 2.0 / down;
    }

    /**
     * Подаёт один входной сэмпл; готовые выходные сэмплы дописываются в {@code out}.
     *
     * @return сколько выходных сэмплов записано
     */
    int push(final double x, final double[] out, final int outPos) {
        hist[histPos] = x;
        int written = 0;
        // выходной сэмпл n готов, когда пришёл самый новый нужный ему вход: floor(n·down/up)
        while (outCount * down / up <= inCount) {
            final long j = outCount * down;
            final int phase = (int) (j % up);
            final int base = phase * TAPS;
            double y = 0;
            int idx = histPos;
            for (int k = 0; k < TAPS; k++) {
                y += h[base + k] * hist[idx];
                idx = idx == 0 ? TAPS - 1 : idx - 1;
            }
            out[outPos + written++] = y;
            outCount++;
        }
        histPos = histPos + 1 == TAPS ? 0 : histPos + 1;
        inCount++;
        return written;
    }

    /** Наибольшее число выходных сэмплов на один входной. */
    int maxOutPerIn() {
        return (up + down - 1) / down;
    }

    void clear() {
        java.util.Arrays.fill(hist, 0);
        histPos = 0;
        inCount = 0;
        outCount = 0;
    }
}
// CHECKSTYLE:ON
