package org.schabi.newpipe.player.leveler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Random;

/**
 * Пересчёт частоты и буферы шумоподавления — без нейросети: вместо неё «пустышка»,
 * которая звук не меняет. Сама нейросеть RNNoise — нативная, её здесь не запустить.
 */
// CHECKSTYLE:OFF
public class NoiseSuppressorTest {

    /** Нейросеть-пустышка: звук не меняет, считает кадры. */
    private static final class Identity implements NoiseSuppressor.Engine {
        int frames;
        boolean released;

        @Override
        public float process(final float[] frame) {
            assertEquals(NoiseSuppressor.FRAME, frame.length);
            frames++;
            return 0.5f;
        }

        @Override
        public void release() {
            released = true;
        }
    }

    private static final double[] FREQS = {180, 440, 1250, 3100, 7000, 12000};

    /**
     * Искажения, dB: всё, что остаётся после вычитания тонов FREQS с подобранными по
     * методу наименьших квадратов амплитудами и фазами. Не зависит от задержки (в том числе
     * дробной, которую даёт пересчёт частоты).
     */
    private static double distortionDb(final double[] y, final int rate, final int from,
                                       final int to) {
        final int k = FREQS.length * 2;
        final double[][] a = new double[k][k + 1];
        final double[] basis = new double[k];
        for (int i = from; i < to; i++) {
            for (int j = 0; j < FREQS.length; j++) {
                basis[2 * j] = Math.sin(2 * Math.PI * FREQS[j] * i / rate);
                basis[2 * j + 1] = Math.cos(2 * Math.PI * FREQS[j] * i / rate);
            }
            for (int r = 0; r < k; r++) {
                for (int c = 0; c < k; c++) {
                    a[r][c] += basis[r] * basis[c];
                }
                a[r][k] += basis[r] * y[i];
            }
        }
        for (int c = 0; c < k; c++) { // метод Гаусса
            for (int r = c + 1; r < k; r++) {
                final double m = a[r][c] / a[c][c];
                for (int q = c; q <= k; q++) {
                    a[r][q] -= m * a[c][q];
                }
            }
        }
        final double[] w = new double[k];
        for (int r = k - 1; r >= 0; r--) {
            double v = a[r][k];
            for (int c = r + 1; c < k; c++) {
                v -= a[r][c] * w[c];
            }
            w[r] = v / a[r][r];
        }
        double sig = 0;
        double err = 0;
        for (int i = from; i < to; i++) {
            double fit = 0;
            for (int j = 0; j < FREQS.length; j++) {
                fit += w[2 * j] * Math.sin(2 * Math.PI * FREQS[j] * i / rate)
                        + w[2 * j + 1] * Math.cos(2 * Math.PI * FREQS[j] * i / rate);
            }
            sig += fit * fit;
            err += (y[i] - fit) * (y[i] - fit);
        }
        return 10 * Math.log10(err / sig);
    }

    /** Сигнал из нескольких тонов в полосе речи и выше — как голос с призвуками. */
    private static double[] tones(final int rate, final double seconds) {
        final double[] x = new double[(int) (rate * seconds)];
        final double[] f = FREQS;
        for (int i = 0; i < x.length; i++) {
            double v = 0;
            for (final double fr : f) {
                v += Math.sin(2 * Math.PI * fr * i / rate + fr);
            }
            x[i] = v / f.length * 8000;
        }
        return x;
    }

    /** Лучшая задержка (сэмплов) и остаток после её вычитания относительно сигнала, dB. */
    private static double[] errorAfterDelay(final double[] in, final double[] out, final int from) {
        int best = 0;
        double bestC = -Double.MAX_VALUE;
        for (int d = 0; d < 3000; d++) {
            double c = 0;
            for (int i = from; i < from + 20000; i++) {
                c += out[i] * in[i - d];
            }
            if (c > bestC) {
                bestC = c;
                best = d;
            }
        }
        double sig = 0;
        double err = 0;
        for (int i = from; i < out.length; i++) {
            sig += in[i - best] * in[i - best];
            err += (out[i] - in[i - best]) * (out[i] - in[i - best]);
        }
        return new double[]{best, 10 * Math.log10(err / sig)};
    }

    private static double[] run(final int rate, final int channels, final double[] mono,
                                final Identity engine, final long seed) {
        final NoiseSuppressor ns = new NoiseSuppressor(rate, channels, engine);
        final Random rnd = new Random(seed);
        final double[] out = new double[mono.length];
        int pos = 0;
        while (pos < mono.length) {
            final int n = Math.min(mono.length - pos, 1 + rnd.nextInt(2000)); // буферы плеера разной длины
            final short[] buf = new short[n * channels];
            for (int i = 0; i < n; i++) {
                for (int c = 0; c < channels; c++) {
                    buf[i * channels + c] = (short) Math.round(mono[pos + i]);
                }
            }
            ns.process(buf, n);
            for (int i = 0; i < n; i++) {
                for (int c = 1; c < channels; c++) {
                    assertEquals("каналы одинаковые (моно)", buf[i * channels], buf[i * channels + c]);
                }
                out[pos + i] = buf[i * channels];
            }
            pos += n;
        }
        return out;
    }

    @Test
    public void resamplerRoundTripIsClean() {
        final double[] x = tones(44100, 3);
        final Resampler up = new Resampler(44100, 48000);
        final Resampler down = new Resampler(48000, 44100);
        final double[] mid = new double[up.maxOutPerIn()];
        final double[] back = new double[down.maxOutPerIn()];
        final double[] y = new double[x.length + 100];
        int n = 0;
        int produced48 = 0;
        for (final double v : x) {
            final int k = up.push(v, mid, 0);
            produced48 += k;
            for (int i = 0; i < k; i++) {
                final int m = down.push(mid[i], back, 0);
                for (int j = 0; j < m && n < y.length; j++) {
                    y[n++] = back[j];
                }
            }
        }
        assertEquals("44.1 -> 48 кГц: число сэмплов", x.length * 48000.0 / 44100, produced48, 2);
        assertEquals("туда-обратно: число сэмплов", x.length, n, 2);
        final double d = distortionDb(y, 44100, 4000, n - 100);
        assertTrue("искажения после пересчёта туда-обратно " + d + " dB", d < -60);
    }

    @Test
    public void passesSoundUnchangedAt44100WithConstantDelay() {
        final double[] x = tones(44100, 4);
        final Identity engine = new Identity();
        final double[] y = run(44100, 2, x, engine, 1);
        final double d = distortionDb(y, 44100, 10000, y.length);
        assertTrue("искажения " + d + " dB", d < -50);
        final double[] r = errorAfterDelay(x, y, 10000);
        assertTrue("задержка " + r[0] + " сэмплов (меньше 15 мс)", r[0] < 0.015 * 44100);
        assertTrue("нейросеть получила кадры", engine.frames > 300);
    }

    @Test
    public void passesSoundExactlyAt48000() {
        final double[] x = tones(48000, 3);
        final Identity engine = new Identity();
        final NoiseSuppressor ns = new NoiseSuppressor(48000, 1, engine);
        final double[] y = run(48000, 1, x, engine, 2);
        final int d = ns.bufferDelay();
        double err = 0;
        for (int i = d; i < x.length; i++) {
            err = Math.max(err, Math.abs(y[i] - Math.round(x[i - d])));
        }
        assertTrue("при 48 кГц звук не меняется, только задержка " + d + "; отклонение " + err,
                err <= 1);
    }

    @Test
    public void noGapsWithAnyBufferSizes() {
        // после начальной задержки на выходе нет «дыр» тишины при любых размерах буферов
        final double[] x = tones(44100, 6);
        for (long seed = 3; seed < 6; seed++) {
            final double[] y = run(44100, 2, x, new Identity(), seed);
            int zeros = 0;
            for (int i = 2000; i < y.length; i++) {
                if (y[i] == 0 && y[i - 1] == 0) {
                    zeros++;
                }
            }
            assertEquals("дыр в звуке нет (seed " + seed + ")", 0, zeros);
        }
    }

    @Test
    public void releaseFreesTheEngine() {
        final Identity engine = new Identity();
        new NoiseSuppressor(44100, 2, engine).release();
        assertTrue(engine.released);
    }
}
// CHECKSTYLE:ON
