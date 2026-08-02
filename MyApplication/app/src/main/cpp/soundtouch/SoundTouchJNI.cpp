#include <jni.h>
#include <android/log.h>
#include "SoundTouch.h"

using namespace soundtouch;

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_example_bourtsadoros_audio_SoundTouchProcessor_createInstance(JNIEnv *env, jobject /* this */) {
    auto *st = new SoundTouch();
    return reinterpret_cast<jlong>(st);
}

JNIEXPORT void JNICALL
Java_com_example_bourtsadoros_audio_SoundTouchProcessor_destroyInstance(JNIEnv *env, jobject /* this */, jlong handle) {
    auto *st = reinterpret_cast<SoundTouch *>(handle);
    delete st;
}

JNIEXPORT void JNICALL
Java_com_example_bourtsadoros_audio_SoundTouchProcessor_setTempoChange(JNIEnv *env, jobject /* this */, jlong handle, jfloat tempoChange) {
    auto *st = reinterpret_cast<SoundTouch *>(handle);
    st->setTempoChange(tempoChange);
}

JNIEXPORT void JNICALL
Java_com_example_bourtsadoros_audio_SoundTouchProcessor_putSamples(JNIEnv *env, jobject /* this */, jlong handle,
                                                                   jfloatArray samples, jint count) {
    auto *st = reinterpret_cast<SoundTouch *>(handle);
    jfloat *data = env->GetFloatArrayElements(samples, nullptr);
    st->putSamples(data, count);
    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);
}

JNIEXPORT jint JNICALL
Java_com_example_bourtsadoros_audio_SoundTouchProcessor_receiveSamples(JNIEnv *env, jobject /* this */, jlong handle,
                                                                       jfloatArray output, jint maxSamples) {
    auto *st = reinterpret_cast<SoundTouch *>(handle);
    auto *buffer = new float[maxSamples];
    jint received = st->receiveSamples(buffer, maxSamples);
    if (received > 0) {
        env->SetFloatArrayRegion(output, 0, received, buffer);
    }
    delete[] buffer;
    return received;
}

}