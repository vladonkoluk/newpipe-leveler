package org.schabi.newpipe.player.leveler;

/**
 * JNI-доступ к библиотеке {@code libdfjni.so} — DeepFilterNet3 (см. {@code app/src/main/rust}).
 * Имена методов связаны с функциями в {@code dfjni/src/lib.rs}.
 */
final class DeepFilterNet {
    static {
        System.loadLibrary("dfjni");
    }

    private DeepFilterNet() {
    }

    /** @param attenLimDb предел подавления, dB (100 — без предела); 0 — не удалось */
    static native long nativeCreate(float attenLimDb);

    static native int nativeFrameLength(long handle);

    static native void nativeSetAttenLim(long handle, float attenLimDb);

    /** Обрабатывает кадр (48 кГц, −1..1) на месте; возвращает локальное SNR кадра, dB. */
    static native float nativeProcess(long handle, float[] frame);

    static native void nativeDestroy(long handle);
}
