// JNI wrapper over whisper.cpp: transcribes a short mono 16 kHz clip (a spoken search request) to text.
#include <jni.h>
#include <android/log.h>
#include <sched.h>

#include <chrono>
#include <string>

#include "whisper.h"

#define TAG "nanowhisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

namespace {
struct Whisper {
    whisper_context *ctx = nullptr;
    int threads = 2;
    long cpuMask = 0;
};

// Pin the calling thread; the worker threads whisper_full() creates inherit the mask.
void pin(long mask) {
    if (mask == 0) return;
    cpu_set_t set;
    CPU_ZERO(&set);
    for (int i = 0; i < 64; i++) if (mask & (1L << i)) CPU_SET(i, &set);
    sched_setaffinity(0, sizeof(set), &set);
}
}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_ai_nanosearch_launcher_NativeWhisper_load(JNIEnv *env, jobject, jstring path, jint threads, jlong cpuMask) {
    pin(cpuMask);
    const char *p = env->GetStringUTFChars(path, nullptr);
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(p, cp);
    env->ReleaseStringUTFChars(path, p);
    if (!ctx) { LOGI("whisper model load failed"); return 0; }
    auto *w = new Whisper();
    w->ctx = ctx;
    w->threads = threads;
    w->cpuMask = cpuMask;
    return (jlong) w;
}

// Returns the transcript (possibly empty) or null on failure. [prompt] biases spelling toward the words in it.
JNIEXPORT jstring JNICALL
Java_ai_nanosearch_launcher_NativeWhisper_transcribe(JNIEnv *env, jobject, jlong h, jfloatArray pcm, jstring lang, jstring prompt, jint audioCtx) {
    auto *w = (Whisper *) h;
    pin(w->cpuMask);
    const jsize n = env->GetArrayLength(pcm);
    jfloat *samples = env->GetFloatArrayElements(pcm, nullptr);
    const char *l = env->GetStringUTFChars(lang, nullptr);
    const char *pr = prompt ? env->GetStringUTFChars(prompt, nullptr) : nullptr;
    std::string language(l);
    std::string initial = pr ? pr : "";

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = w->threads;
    params.language = language.c_str();
    params.translate = false;
    params.no_context = true;
    params.single_segment = true;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_special = false;
    params.print_timestamps = false;
    params.suppress_nst = true;
    params.temperature = 0.0f;
    params.max_tokens = 64; // a spoken query is short; this also stops the occasional runaway repeat
    if (!initial.empty()) params.initial_prompt = initial.c_str();
    // Whisper pads every clip to 30 s; a shorter encoder window is the difference between ~4 s and ~1 s for a spoken query.
    if (audioCtx > 0) params.audio_ctx = audioCtx;

    auto t0 = std::chrono::steady_clock::now();
    int rc = whisper_full(w->ctx, params, samples, n);
    long ms = (long) std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - t0).count();

    std::string out;
    if (rc == 0) {
        int segs = whisper_full_n_segments(w->ctx);
        for (int i = 0; i < segs; i++) out += whisper_full_get_segment_text(w->ctx, i);
    }
    LOGI("whisper: %d samples (%.1fs audio) -> %ld ms, rc=%d", (int) n, n / 16000.0, ms, rc);

    env->ReleaseFloatArrayElements(pcm, samples, JNI_ABORT);
    env->ReleaseStringUTFChars(lang, l);
    if (pr) env->ReleaseStringUTFChars(prompt, pr);
    return rc == 0 ? env->NewStringUTF(out.c_str()) : nullptr;
}

JNIEXPORT void JNICALL
Java_ai_nanosearch_launcher_NativeWhisper_free(JNIEnv *, jobject, jlong h) {
    auto *w = (Whisper *) h;
    if (!w) return;
    whisper_free(w->ctx);
    delete w;
}

}  // extern "C"
