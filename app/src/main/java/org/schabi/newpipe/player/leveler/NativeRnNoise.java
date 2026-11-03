package org.schabi.newpipe.player.leveler;

import android.util.Log;

import androidx.annotation.Nullable;

/** Нейросеть RNNoise для {@link NoiseSuppressor}: «Мягкое» (0.1.1) или «Сильное» (новая). */
final class NativeRnNoise implements NoiseSuppressor.Engine {
    private static final String TAG = "NativeRnNoise";
    /** Почему не удалось загрузить нейросеть (для строки состояния), или null. */
    @Nullable
    private static volatile String loadError;

    private final boolean strong;
    private long handle;

    private NativeRnNoise(final boolean strong, final long handle) {
        this.strong = strong;
        this.handle = handle;
    }

    /**
     * @param level {@link LoudnessLeveler.Params#NOISE_SOFT} или
     *              {@link LoudnessLeveler.Params#NOISE_STRONG}
     * @return нейросеть или null, если библиотеку загрузить не удалось
     */
    @Nullable
    static NativeRnNoise create(final int level) {
        final boolean strong = level == LoudnessLeveler.Params.NOISE_STRONG;
        try {
            final long h = strong ? RnNoiseStrong.nativeCreate() : RnNoiseSoft.nativeCreate();
            if (h == 0) {
                loadError = "rnnoise_create вернул 0";
                return null;
            }
            loadError = null;
            return new NativeRnNoise(strong, h);
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
        return strong ? RnNoiseStrong.nativeProcess(handle, frame)
                : RnNoiseSoft.nativeProcess(handle, frame);
    }

    @Override
    public void release() {
        if (handle != 0) {
            if (strong) {
                RnNoiseStrong.nativeDestroy(handle);
            } else {
                RnNoiseSoft.nativeDestroy(handle);
            }
            handle = 0;
        }
    }
}
