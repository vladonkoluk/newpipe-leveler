package org.schabi.newpipe.player.leveler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** Проверка выравнивания громкости на синтетических сигналах, похожих на речь. */
// CHECKSTYLE:OFF
public class LoudnessLevelerTest {
    private static final int SR = 48000;

    // ---------- тестовые сигналы ----------

    /** «Речь»: розоватый шум, модулированный слогами 4 Гц, с паузами. */
    private static double[] speech(final double seconds, final double lufs, final long seed) {
        final java.util.Random rnd = new java.util.Random(seed);
        final int n = (int) Math.round(seconds * SR);
        final double[] out = new double[n];
        double b0 = 0;
        double b1 = 0;
        for (int i = 0; i < n; i++) {
            final double w = rnd.nextDouble() * 2 - 1;
            b0 = 0.99 * b0 + 0.1 * w;
            b1 = 0.6 * b1 + 0.4 * w;
            final double t = (double) i / SR;
            final double syll = Math.pow(Math.max(0, Math.sin(2 * Math.PI * 4 * t)), 2);
            final double pause = (t % 3) > 2.6 ? 0 : 1;
            out[i] = (b0 + b1) * syll * pause;
        }
        return scaleTo(out, lufs);
    }

    private static double[] concat(final double[]... parts) {
        int len = 0;
        for (final double[] p : parts) {
            len += p.length;
        }
        final double[] out = new double[len];
        int o = 0;
        for (final double[] p : parts) {
            System.arraycopy(p, 0, out, o, p.length);
            o += p.length;
        }
        return out;
    }

    private static double[] seg(final double[] x, final double from, final double to) {
        return java.util.Arrays.copyOfRange(x, (int) Math.round(from * SR),
                (int) Math.round(to * SR));
    }

    // ---------- независимое измерение громкости (BS.1770, моно дублируется в 2 канала) ----------

    private static double[] filter(final double[] x, final double b0, final double b1,
                                   final double b2, final double a0, final double a1,
                                   final double a2) {
        final double[] y = new double[x.length];
        double x1 = 0;
        double x2 = 0;
        double y1 = 0;
        double y2 = 0;
        for (int i = 0; i < x.length; i++) {
            final double v = (b0 * x[i] + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2) / a0;
            x2 = x1;
            x1 = x[i];
            y2 = y1;
            y1 = v;
            y[i] = v;
        }
        return y;
    }

    private static double[] kFilter(final double[] x) {
        double w = 2 * Math.PI * 1500 / SR;
        double cw = Math.cos(w);
        final double a = Math.pow(10, 4.0 / 40);
        double al = Math.sin(w) / Math.sqrt(2);
        final double sA = 2 * Math.sqrt(a) * al;
        final double a0 = (a + 1) - (a - 1) * cw + sA;
        final double[] s = filter(x, a * ((a + 1) + (a - 1) * cw + sA) * 1,
                -2 * a * ((a - 1) + (a + 1) * cw), a * ((a + 1) + (a - 1) * cw - sA),
                a0, 2 * ((a - 1) - (a + 1) * cw), (a + 1) - (a - 1) * cw - sA);
        w = 2 * Math.PI * 38 / SR;
        cw = Math.cos(w);
        al = Math.sin(w);
        return filter(s, (1 + cw) / 2, -(1 + cw), (1 + cw) / 2, 1 + al, -2 * cw, 1 - al);
    }

    private static List<Double> blocks(final double[] x) {
        final double[] k = kFilter(x);
        final int win = (int) Math.round(0.4 * SR);
        final int hop = (int) Math.round(0.1 * SR);
        final List<Double> res = new ArrayList<>();
        for (int s = 0; s + win <= k.length; s += hop) {
            double p = 0;
            for (int i = s; i < s + win; i++) {
                p += k[i] * k[i];
            }
            res.add(-0.691 + 10 * Math.log10(2 * p / win + 1e-20));
        }
        return res;
    }

    private static double integrated(final double[] x) {
        final List<Double> b = new ArrayList<>();
        for (final double l : blocks(x)) {
            if (l > -70) {
                b.add(l);
            }
        }
        if (b.isEmpty()) {
            return Double.NEGATIVE_INFINITY;
        }
        double sum = 0;
        for (final double l : b) {
            sum += Math.pow(10, (l + 0.691) / 10);
        }
        final double rel = -0.691 + 10 * Math.log10(sum / b.size()) - 10;
        double gs = 0;
        int gn = 0;
        for (final double l : b) {
            if (l > rel) {
                gs += Math.pow(10, (l + 0.691) / 10);
                gn++;
            }
        }
        return -0.691 + 10 * Math.log10(gs / gn);
    }

    private static double[] scaleTo(final double[] x, final double lufs) {
        final double k = Math.pow(10, (lufs - integrated(x)) / 20);
        for (int i = 0; i < x.length; i++) {
            x[i] *= k;
        }
        return x;
    }

    /** Прогоняет моно-сигнал как стерео через {@link LoudnessLeveler} блоками по 1024 кадра. */
    private static double[] process(final double[] x, final LoudnessLeveler.Params params) {
        final LoudnessLeveler lev = new LoudnessLeveler(SR, 2, params);
        final double[] out = new double[x.length];
        final int block = 1024;
        final short[] buf = new short[block * 2];
        for (int s = 0; s < x.length; s += block) {
            final int n = Math.min(block, x.length - s);
            for (int i = 0; i < n; i++) {
                final short v = (short) Math.round(
                        Math.max(-32768, Math.min(32767, x[s + i] * 32768)));
                buf[2 * i] = v;
                buf[2 * i + 1] = v;
            }
            lev.process(buf, n, SR);
            for (int i = 0; i < n; i++) {
                out[s + i] = buf[2 * i] / 32768.0;
            }
        }
        return out;
    }

    private static double mean(final List<Double> v) {
        double s = 0;
        for (final double d : v) {
            s += d;
        }
        return s / v.size();
    }

    /** Настройки режима «Максимум» — как в LevelerSettings. */
    private static LoudnessLeveler.Params maxParams() {
        final LoudnessLeveler.Params p = new LoudnessLeveler.Params();
        p.levTime = 0.1;
        p.levAtt = 0.02;
        p.levRel = 0.1;
        p.compAbove = 0;
        p.ratio = 20;
        return p;
    }

    /** «Живая речь»: громкость скачет от слова к слову (±8 dB) и от сцены к сцене. */
    private static double[] livelySpeech() {
        final java.util.Random rnd = new java.util.Random(3);
        final double[] scenes = {-30, -18, -38, -10};
        final List<double[]> parts = new ArrayList<>();
        for (int k = 0; k < 80; k++) {
            parts.add(speech(0.5, scenes[k / 20] + rnd.nextDouble() * 16 - 8, k + 1));
        }
        return concat(parts.toArray(new double[0][]));
    }

    /** Разброс громкости по 0.4 с между 10-м и 90-м процентилем, dB. */
    private static double spread(final double[] y) {
        final List<Double> m = new ArrayList<>();
        for (final double l : blocks(seg(y, 5, (double) y.length / SR))) {
            if (l > -60) {
                m.add(l);
            }
        }
        java.util.Collections.sort(m);
        return m.get((int) (0.9 * (m.size() - 1))) - m.get((int) (0.1 * (m.size() - 1)));
    }

    /** На сколько dB голос громче фона в паузах (речь -24 LUFS, фон -50, на входе 26). */
    private static double voiceOverNoise(final LoudnessLeveler.Params params) {
        final java.util.Random rnd = new java.util.Random(7);
        final List<double[]> parts = new ArrayList<>();
        for (int k = 0; k < 12; k++) {
            final double[] s = speech(2, -24, k + 1);
            for (int i = (int) (1.9 * SR); i < s.length; i++) {
                s[i] = 0;
            }
            parts.add(s);
            parts.add(new double[SR]);
        }
        final double[] sp = concat(parts.toArray(new double[0][]));
        final double[] hiss = new double[sp.length];
        for (int i = 0; i < hiss.length; i++) {
            hiss[i] = rnd.nextDouble() * 2 - 1;
        }
        scaleTo(hiss, -50);
        final double[] x = new double[sp.length];
        for (int i = 0; i < x.length; i++) {
            x[i] = sp[i] + hiss[i];
        }
        final double[] y = process(x, params);
        final List<Double> voice = new ArrayList<>();
        final List<Double> noise = new ArrayList<>();
        for (int k = 4; k < 12; k++) {
            voice.add(integrated(seg(y, k * 3, k * 3 + 1.9)));
            noise.add(integrated(seg(y, k * 3 + 2.15, k * 3 + 2.95)));
        }
        return mean(voice) - mean(noise);
    }

    // ---------- проверки ----------

    @Test
    public void wordToWordLoudnessIsLeveled() {
        // на входе разброс около 34 dB
        final double normal = spread(process(livelySpeech(), new LoudnessLeveler.Params()));
        final double max = spread(process(livelySpeech(), maxParams()));
        assertTrue("100%: разброс " + normal + " dB", normal <= 7.5);
        assertTrue("Максимум: разброс " + max + " dB", max <= 5.5);
        assertTrue("Максимум ровнее, чем 100%: " + max + " < " + normal, max < normal - 1);
    }

    @Test
    public void maximumKeepsBackgroundNoiseDown() {
        final double snr = voiceOverNoise(maxParams());
        assertTrue("голос громче фона на " + snr + " dB", snr >= 18);
    }

    @Test
    public void quietAndLoudClipsReachTarget() {
        for (final double lvl : new double[]{-34, -26, -16, -8, -4}) {
            final double[] y = process(speech(30, lvl, 7), new LoudnessLeveler.Params());
            final double l = integrated(seg(y, 10, 30));
            assertEquals("ролик " + lvl + " LUFS", -16, l, 2);
        }
    }

    @Test
    public void veryQuietClipLimitedByMaxBoost() {
        final double[] y = process(speech(30, -44, 3), new LoudnessLeveler.Params());
        final double l = integrated(seg(y, 10, 30));
        assertTrue("вышло " + l, l <= -44 + 24 + 1.5 && l >= -44 + 24 - 3);
    }

    @Test
    public void movieScenesComeOutLevel() {
        // диалог -32, громкая сцена -8, шёпот -42 — на входе разброс 34 dB
        final double[][] scenes = {{3, -32}, {1.5, -8}, {2, -42}};
        final List<double[]> parts = new ArrayList<>();
        for (int k = 0; k < 8; k++) {
            for (int s = 0; s < scenes.length; s++) {
                parts.add(speech(scenes[s][0], scenes[s][1], k * 3 + s + 1));
            }
        }
        final double[] y = process(concat(parts.toArray(new double[0][])),
                new LoudnessLeveler.Params());
        final List<List<Double>> acc = new ArrayList<>();
        for (int s = 0; s < scenes.length; s++) {
            acc.add(new ArrayList<>());
        }
        double t = 0;
        for (int k = 0; k < 8; k++) {
            for (int s = 0; s < scenes.length; s++) {
                if (t > 10) {
                    acc.get(s).add(integrated(seg(y, t, t + scenes[s][0])));
                }
                t += scenes[s][0];
            }
        }
        double max = -100;
        double min = 100;
        for (final List<Double> a : acc) {
            max = Math.max(max, mean(a));
            min = Math.min(min, mean(a));
        }
        assertTrue("разброс " + (max - min) + " dB", max - min <= 4);
    }

    @Test
    public void backgroundNoiseInPausesIsNotPumpedUp() {
        final double snr = voiceOverNoise(new LoudnessLeveler.Params());
        assertTrue("голос громче фона на " + snr + " dB (на входе 26)", snr >= 18);
    }

    @Test
    public void noPeaksAboveCeilingAfterSuddenJump() {
        final double[] y = process(concat(speech(15, -32, 1), speech(15, -6, 2)),
                new LoudnessLeveler.Params());
        double peak = 0;
        for (final double v : y) {
            peak = Math.max(peak, Math.abs(v));
        }
        assertTrue("пик " + 20 * Math.log10(peak), 20 * Math.log10(peak) <= -0.95);
        double worst = -100;
        for (final double l : blocks(seg(y, 15, 17))) {
            worst = Math.max(worst, l);
        }
        assertTrue("всплеск " + worst, worst <= -16 + 8);
    }

    @Test
    public void silenceIsNotBoosted() {
        final double[] noise = speech(10, -40, 9);
        for (int i = 0; i < noise.length; i++) {
            noise[i] *= 0.01; // фон около -80 LUFS
        }
        final double[] y = process(concat(speech(10, -20, 6), new double[5 * SR], noise),
                new LoudnessLeveler.Params());
        double worst = -100;
        for (final double l : blocks(seg(y, 16, 25))) {
            worst = Math.max(worst, l);
        }
        assertTrue("фон на выходе " + worst, worst < -55);
    }

    @Test
    public void disabledPassesSignalThrough() {
        final double[] x = speech(5, -20, 8);
        final LoudnessLeveler.Params params = new LoudnessLeveler.Params();
        params.enabled = false;
        final double[] y = process(x, params);
        final int d = (int) Math.round(0.005 * SR);
        double err = 0;
        for (int i = SR; i < x.length; i++) {
            err = Math.max(err, Math.abs(y[i] - Math.round(x[i - d] * 32768) / 32768.0));
        }
        assertTrue("отклонение " + err, err < 2.0 / 32768);
    }
}
// CHECKSTYLE:ON
