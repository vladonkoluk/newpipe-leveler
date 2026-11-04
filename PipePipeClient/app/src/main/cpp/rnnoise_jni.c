/*
 * JNI-обвязка RNNoise для NewPipe Leveler. Собирается дважды — с разными версиями RNNoise
 * и разными именами Java-классов (JNI_CLASS), каждая в свою библиотеку: внутренние символы
 * обеих версий одинаковые, поэтому они скрыты (-fvisibility=hidden), наружу видны только
 * функции этого файла.
 */
#include <jni.h>
#include <stdint.h>
#include "rnnoise.h"

#ifndef JNI_CLASS
#error "JNI_CLASS must be defined"
#endif

#define PASTE4(a, b, c, d) a##b##c##d
#define XPASTE4(a, b, c, d) PASTE4(a, b, c, d)
#define JNI_FN(name) XPASTE4(Java_org_schabi_newpipe_player_leveler_, JNI_CLASS, _, name)

#define FRAME 480

JNIEXPORT jlong JNICALL JNI_FN(nativeCreate)(JNIEnv *env, jclass cls) {
    (void) env;
    (void) cls;
    return (jlong) (intptr_t) rnnoise_create(NULL);
}

JNIEXPORT void JNICALL JNI_FN(nativeDestroy)(JNIEnv *env, jclass cls, jlong handle) {
    (void) env;
    (void) cls;
    if (handle != 0) {
        rnnoise_destroy((DenoiseState *) (intptr_t) handle);
    }
}

/* Обрабатывает 480 сэмплов (48 кГц, шкала 16-битного звука) на месте; возвращает вероятность речи. */
JNIEXPORT jfloat JNICALL JNI_FN(nativeProcess)(JNIEnv *env, jclass cls, jlong handle,
                                               jfloatArray frame) {
    (void) cls;
    if (handle == 0 || (*env)->GetArrayLength(env, frame) < FRAME) {
        return 0;
    }
    float buf[FRAME];
    (*env)->GetFloatArrayRegion(env, frame, 0, FRAME, buf);
    const float vad = rnnoise_process_frame((DenoiseState *) (intptr_t) handle, buf, buf);
    (*env)->SetFloatArrayRegion(env, frame, 0, FRAME, buf);
    return vad;
}
