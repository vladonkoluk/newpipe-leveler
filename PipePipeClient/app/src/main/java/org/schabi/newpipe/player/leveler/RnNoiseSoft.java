package org.schabi.newpipe.player.leveler;

/**
 * JNI-доступ к библиотеке {@code librnnoise_soft.so} (см. {@code app/src/main/cpp}).
 * Имена методов связаны с функциями в {@code rnnoise_jni.c}.
 */
final class RnNoiseSoft {
    static {
        System.loadLibrary("rnnoise_soft");
    }

    private RnNoiseSoft() {
    }

    static native long nativeCreate();

    static native void nativeDestroy(long handle);

    /** Обрабатывает 480 сэмплов (48 кГц, шкала 16-битного звука) на месте. */
    static native float nativeProcess(long handle, float[] frame);
}
