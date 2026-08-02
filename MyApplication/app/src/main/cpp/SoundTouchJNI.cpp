#include <jni.h>
#include <android/log.h>
#include "soundtouch/SoundTouch.h"

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
Java_com_example_bourtsadoros_audio_SoundTouchProcessor_setSampleRate(JNIEnv *env, jobject /* this */, jlong handle, jint sampleRate) {
    auto *st = reinterpret_cast<SoundTouch *>(handle);
    st->setSampleRate(sampleRate);
}

JNIEXPORT void JNICALL
Java_com_example_bourtsadoros_audio_SoundTouchProcessor_setChannels(JNIEnv *env, jobject /* this */, jlong handle, jint channels) {
    auto *st = reinterpret_cast<SoundTouch *>(handle);
    st->setChannels(channels);
}

JNIEXPORT void JNICALL
Java_com_example_bourtsadoros_audio_SoundTouchProcessor_setTempo(JNIEnv *env, jobject /* this */, jlong handle, jfloat tempo) {
    auto *st = reinterpret_cast<SoundTouch *>(handle);
    st->setTempo(tempo);
}

JNIEXPORT void JNICALL
Java_com_example_bourtsadoros_audio_SoundTouchProcessor_putSamples(JNIEnv *env, jobject /* this */, jlong handle,
                                                                   jfloatArray samples, jint count) {
    auto *st = reinterpret_cast<SoundTouch *>(handle);
    jfloat *data = env->GetFloatArrayElements(samples, nullptr);
    st->putSamples(data, count);
    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_com_example_bourtsadoros_audio_SoundTouchProcessor_flush(JNIEnv *env, jobject /* this */, jlong handle) {
    auto *st = reinterpret_cast<SoundTouch *>(handle);
    st->flush();
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