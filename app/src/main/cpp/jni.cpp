// Ponte JNI minimale verso whisper.cpp
#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <atomic>
#include "whisper.h"

#define TAG "VidScript"

struct Ctx {
    whisper_context *wctx = nullptr;
    std::atomic<bool> cancel{false};
};

static bool abort_cb(void *user) {
    return static_cast<Ctx *>(user)->cancel.load();
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_roboek_vidscript_engine_WhisperEngine_nativeInit(JNIEnv *env, jobject, jstring path) {
    const char *p = env->GetStringUTFChars(path, nullptr);
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    whisper_context *w = whisper_init_from_file_with_params(p, cp);
    env->ReleaseStringUTFChars(path, p);
    if (!w) return 0;
    auto *c = new Ctx();
    c->wctx = w;
    return reinterpret_cast<jlong>(c);
}

extern "C" JNIEXPORT void JNICALL
Java_com_roboek_vidscript_engine_WhisperEngine_nativeFree(JNIEnv *, jobject, jlong h) {
    auto *c = reinterpret_cast<Ctx *>(h);
    if (!c) return;
    whisper_free(c->wctx);
    delete c;
}

extern "C" JNIEXPORT void JNICALL
Java_com_roboek_vidscript_engine_WhisperEngine_nativeCancel(JNIEnv *, jobject, jlong h) {
    auto *c = reinterpret_cast<Ctx *>(h);
    if (c) c->cancel = true;
}

// Ritorna il testo trascritto (stringa vuota in caso di errore/annullamento).
extern "C" JNIEXPORT jstring JNICALL
Java_com_roboek_vidscript_engine_WhisperEngine_nativeTranscribe(
        JNIEnv *env, jobject, jlong h, jfloatArray samples, jstring lang, jint threads, jboolean translate) {
    auto *c = reinterpret_cast<Ctx *>(h);
    if (!c) return env->NewStringUTF("");
    c->cancel = false;

    jsize n = env->GetArrayLength(samples);
    std::vector<float> pcm(n);
    env->GetFloatArrayRegion(samples, 0, n, pcm.data());

    const char *l = env->GetStringUTFChars(lang, nullptr);
    std::string language = l;
    env->ReleaseStringUTFChars(lang, l);

    whisper_full_params wp = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wp.print_realtime = false;
    wp.print_progress = false;
    wp.print_timestamps = false;
    wp.print_special = false;
    wp.translate = translate;
    wp.language = language.c_str();      // "auto" per il rilevamento automatico
    wp.detect_language = (language == "auto");
    wp.n_threads = threads;
    wp.no_context = true;
    wp.single_segment = false;
    wp.suppress_blank = true;
    wp.abort_callback = abort_cb;
    wp.abort_callback_user_data = c;

    if (whisper_full(c->wctx, wp, pcm.data(), (int) pcm.size()) != 0) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "whisper_full ha fallito o e' stato annullato");
        return env->NewStringUTF("");
    }
    std::string out;
    int segs = whisper_full_n_segments(c->wctx);
    for (int i = 0; i < segs; ++i) {
        const char *t = whisper_full_get_segment_text(c->wctx, i);
        if (t) out += t;
    }
    return env->NewStringUTF(out.c_str());
}
