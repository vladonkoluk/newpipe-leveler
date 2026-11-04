package org.schabi.newpipe.player.leveler;

import android.util.Log;

import androidx.annotation.Nullable;

/**
 * Нейросеть для {@link NoiseSuppressor}: RNNoise «Мягкое» (0.1.1), «Сильное» (новая)
 * или DeepFilterNet3.
 */
final class NativeRnNoise implements NoiseSuppressor.Engine {
    private static final String TAG = "NativeRnNoise";
    /** Почему не удалось загрузить нейросеть (для строки состояния), или null. */
    @Nullable
    private static volatile String loadError;

    private final int level;
    private long handle;
    // DeepFilterNet: работает со звуком −1..1, а в NoiseSuppressor — шкала 16-битного звука
    private final float[] scaled = new float[NoiseSuppressor.FRAME];

    private NativeRnNoise(final int level, final long handle) {
        this.level = level;
        this.handle = handle;
    }

    /**
     * @param level {@link LoudnessLeveler.Params#NOISE_SOFT},
     *              {@link LoudnessLeveler.Params#NOISE_STRONG}
     *              или {@link LoudnessLeveler.Params#NOISE_DFN}
     * @return нейросеть или null, если библиотеку загрузить не удалось
     */
    @Nullable
    static NativeRnNoise create(final int level) {
        try {
            final long h;
            if (level == LoudnessLeveler.Params.NOISE_DFN) {
                h = DeepFilterNet.nativeCreate(100);
                if (h != 0 && DeepFilterNet.nativeFrameLength(h) != NoiseSuppressor.FRAME) {
                    DeepFilterNet.nativeDestroy(h);
                    loadError = "DeepFilterNet: неожиданная длина кадра";
                    return null;
                }
            } else if (level == LoudnessLeveler.Params.NOISE_STRONG) {
                h = RnNoiseStrong.nativeCreate();
            } else {
                h = RnNoiseSoft.nativeCreate();
            }
            if (h == 0) {
                loadError = "нейросеть не создалась";
                return null;
            }
            loadError = null;
            return new NativeRnNoise(level, h);
        } catch (final Throwable e) { // UnsatisfiedLinkError, ExceptionInInitializerError
            Log.e(TAG, "Не удалось загрузить RNNoise", e);
            loadError = e.getClass().getSimpleName() + ": " + e.getMessage();
            return null;
        }
    }

    @Nullable
    static String getLoadError() {
        return loadError;
    }

    @Override
    public float process(final float[] frame) {
        if (level == LoudnessLeveler.Params.NOISE_DFN) {
            for (int i = 0; i < frame.length; i++) {
                scaled[i] = frame[i] / 32768f;
            }
            final float lsnr = DeepFilterNet.nativeProcess(handle, scaled);
            for (int i = 0; i < frame.length; i++) {
                frame[i] = scaled[i] * 32768f;
            }
            return dfnSpeechProbability(lsnr);
        }
        return level == LoudnessLeveler.Params.NOISE_STRONG
                ? RnNoiseStrong.nativeProcess(handle, frame)
                : RnNoiseSoft.nativeProcess(handle, frame);
    }

    /**
     * DeepFilterNet выдаёт не вероятность речи, а локальное отношение сигнал/шум кадра, dB.
     * Переводим его в «вероятность речи» для «Беречь речь».
     */
    static float dfnSpeechProbability(final float lsnrDb) {
        return Math.max(0f, Math.min(1f, (lsnrDb - DFN_LSNR_ZERO) / DFN_LSNR_RANGE));
    }

    @Override
    public int delayFrames() {
        return level == LoudnessLeveler.Params.NOISE_DFN ? 3
                : level == LoudnessLeveler.Params.NOISE_STRONG ? 2 : 1;
    }

    /** При каком SNR кадра (dB) «вероятность речи» DeepFilterNet = 0 и на сколько dB до 1. */
    static final float DFN_LSNR_ZERO = 0f;
    static final float DFN_LSNR_RANGE = 25f;

    @Override
    public void release() {
        if (handle != 0) {
            if (level == LoudnessLeveler.Params.NOISE_DFN) {
                DeepFilterNet.nativeDestroy(handle);
            } else if (level == LoudnessLeveler.Params.NOISE_STRONG) {
                RnNoiseStrong.nativeDestroy(handle);
            } else {
                RnNoiseSoft.nativeDestroy(handle);
            }
            handle = 0;
        }
    }
}
