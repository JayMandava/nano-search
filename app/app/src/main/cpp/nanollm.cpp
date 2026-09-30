// Thin JNI wrapper over llama.cpp for on-device inference.
//
// One Engine = one loaded model + one context. The fixed prompt prefix (system prompt) is
// evaluated once in setPrefix() and its sequence state is saved; every complete() restores
// that state instead of re-processing the prefix. Restoring saved state (rather than
// truncating the KV cache) works for hybrid/recurrent architectures such as Qwen 3.5 too.
#include <jni.h>
#include <android/log.h>
#include <sched.h>
#include <unistd.h>

#include <chrono>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "ggml-backend.h"
#include "ggml-cpu.h"
#include "llama.h"

// The same source builds two libraries: the CPU one (NativeLlm) and an optional GPU one (NativeLlmGpu, with the Vulkan backend
// compiled in). They are separate so that the GPU driver is never touched unless the user turns the GPU on.
#ifndef NANO_JNI_CLASS
#define NANO_JNI_CLASS NativeLlm
#endif
#define NANO_JNI_JOIN(cls, fn) Java_ai_nanosearch_launcher_##cls##_##fn
#define NANO_JNI_EXPAND(cls, fn) NANO_JNI_JOIN(cls, fn)
#define NANO_JNI(fn) NANO_JNI_EXPAND(NANO_JNI_CLASS, fn)

#define TAG "nanollm"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct Engine {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    ggml_threadpool_t pool = nullptr;
    std::vector<uint8_t> prefixState;
    int prefixTokens = 0;
    bool prefixFromCache = false;
    int batch = 512;
    long cpuMask = 0;
    // stats from the last complete()
    long promptMs = 0, genMs = 0;
    int promptTokens = 0, genTokens = 0;
    long sampleMs = 0, decodeMs = 0;
};

using Clock = std::chrono::steady_clock;
long msSince(Clock::time_point t) {
    return (long) std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now() - t).count();
}

// Pin the calling thread; ggml's worker threads inherit the mask when they are created.
void pin(long mask) {
    if (mask == 0) return;
    cpu_set_t set;
    CPU_ZERO(&set);
    for (int i = 0; i < 64; i++) if (mask & (1L << i)) CPU_SET(i, &set);
    sched_setaffinity(0, sizeof(set), &set);
}

std::vector<llama_token> tokenize(const llama_vocab *vocab, const std::string &text, bool addSpecial) {
    int n = -llama_tokenize(vocab, text.c_str(), (int) text.size(), nullptr, 0, addSpecial, true);
    std::vector<llama_token> toks(n);
    if (llama_tokenize(vocab, text.c_str(), (int) text.size(), toks.data(), n, addSpecial, true) < 0) toks.clear();
    return toks;
}

bool decodeAll(Engine *e, std::vector<llama_token> &toks) {
    for (size_t i = 0; i < toks.size(); i += e->batch) {
        int n = (int) std::min<size_t>(e->batch, toks.size() - i);
        llama_batch b = llama_batch_get_one(toks.data() + i, n);
        if (llama_decode(e->ctx, b) != 0) return false;
    }
    return true;
}

// Length of the longest prefix of s that ends on a UTF-8 character boundary.
size_t validUtf8Prefix(const std::string &s) {
    size_t n = s.size();
    size_t i = n;
    int back = 0;
    while (i > 0 && back < 4 && ((unsigned char) s[i - 1] & 0xC0) == 0x80) { i--; back++; }
    if (i == 0) return back == 0 ? n : 0;
    unsigned char lead = (unsigned char) s[i - 1];
    size_t need = lead >= 0xF0 ? 4 : lead >= 0xE0 ? 3 : lead >= 0xC0 ? 2 : 1;
    size_t have = back + 1;
    return have >= need ? n : i - 1;
}

// Greedy pick. With a grammar, first take the plain argmax and only check that one token against the
// grammar; scanning the whole vocabulary through the grammar every step cost ~65 ms/token on a 248k vocab
// (about 40% of generation time). Only when the argmax is rejected do we apply the grammar to all logits.
llama_token pickToken(Engine *e, llama_sampler *grammar) {
    const int nv = llama_vocab_n_tokens(e->vocab);
    const float *logits = llama_get_logits_ith(e->ctx, -1);
    llama_token best = 0;
    for (int i = 1; i < nv; i++) if (logits[i] > logits[best]) best = i;
    if (!grammar) return best;

    llama_token_data one = {best, logits[best], 0.0f};
    llama_token_data_array oneArr = {&one, 1, -1, false};
    llama_sampler_apply(grammar, &oneArr);
    if (one.logit != -INFINITY) return best;

    std::vector<llama_token_data> all(nv);
    for (int i = 0; i < nv; i++) all[i] = {i, logits[i], 0.0f};
    llama_token_data_array arr = {all.data(), (size_t) nv, -1, false};
    llama_sampler_apply(grammar, &arr);
    llama_token pick = best;
    float top = -INFINITY;
    for (size_t i = 0; i < arr.size; i++) if (arr.data[i].logit > top) { top = arr.data[i].logit; pick = arr.data[i].id; }
    return pick;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
NANO_JNI(load)(JNIEnv *env, jobject, jstring path, jint threads, jint nCtx, jlong cpuMask, jint gpuLayers) {
    static bool inited = false;
    if (!inited) { llama_backend_init(); inited = true; }
    pin(cpuMask);
    const char *p = env->GetStringUTFChars(path, nullptr);
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = gpuLayers;
    llama_model *model = llama_model_load_from_file(p, mp);
    env->ReleaseStringUTFChars(path, p);
    if (!model) { LOGE("model load failed (gpuLayers=%d)", (int) gpuLayers); return 0; }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = nCtx;
    cp.n_batch = 512;
    cp.n_threads = threads;
    cp.n_threads_batch = threads;
    llama_context *ctx = llama_init_from_model(model, cp);
    if (!ctx) { llama_model_free(model); LOGE("context init failed"); return 0; }

    // A persistent pool pinned to the requested cores, so worker threads are not recreated per graph.
    ggml_threadpool_params tpp = ggml_threadpool_params_default(threads);
    if (cpuMask != 0) {
        for (int i = 0; i < 64 && i < GGML_MAX_N_THREADS; i++) tpp.cpumask[i] = (cpuMask >> i) & 1;
        tpp.strict_cpu = true;
    }
    ggml_threadpool_t pool = ggml_threadpool_new(&tpp);
    if (pool) llama_attach_threadpool(ctx, pool, pool);

    auto *e = new Engine();
    e->pool = pool;
    e->model = model;
    e->ctx = ctx;
    e->vocab = llama_model_get_vocab(model);
    e->cpuMask = cpuMask;
    return (jlong) e;
}

// Loads the prefix state from cachePath if present and usable.
static bool loadPrefixCache(Engine *e, const char *cachePath) {
    FILE *f = fopen(cachePath, "rb");
    if (!f) return false;
    int32_t tokens = 0;
    uint64_t size = 0;
    bool ok = fread(&tokens, sizeof(tokens), 1, f) == 1 && fread(&size, sizeof(size), 1, f) == 1 && size > 0 && size < (1ull << 31);
    std::vector<uint8_t> buf;
    if (ok) {
        buf.resize(size);
        ok = fread(buf.data(), 1, size, f) == size;
    }
    fclose(f);
    if (!ok) return false;
    llama_memory_clear(llama_get_memory(e->ctx), false);
    if (llama_state_seq_set_data(e->ctx, buf.data(), buf.size(), 0) == 0) return false;
    e->prefixState = std::move(buf);
    e->prefixTokens = tokens;
    return true;
}

static void savePrefixCache(Engine *e, const char *cachePath) {
    std::string tmp = std::string(cachePath) + ".tmp";
    FILE *f = fopen(tmp.c_str(), "wb");
    if (!f) return;
    int32_t tokens = e->prefixTokens;
    uint64_t size = e->prefixState.size();
    bool ok = fwrite(&tokens, sizeof(tokens), 1, f) == 1 && fwrite(&size, sizeof(size), 1, f) == 1 &&
              fwrite(e->prefixState.data(), 1, size, f) == size;
    fclose(f);
    if (ok) rename(tmp.c_str(), cachePath); else remove(tmp.c_str());
}

// Evaluates the fixed prompt prefix once and saves its state. If cachePath is set, the state is
// restored from that file when it exists (skipping the prompt processing) and written when it does not.
JNIEXPORT jint JNICALL
NANO_JNI(setPrefix)(JNIEnv *env, jobject, jlong h, jstring text, jstring cachePath) {
    auto *e = (Engine *) h;
    pin(e->cpuMask);
    std::string cache;
    if (cachePath) {
        const char *c = env->GetStringUTFChars(cachePath, nullptr);
        cache = c;
        env->ReleaseStringUTFChars(cachePath, c);
        if (loadPrefixCache(e, cache.c_str())) {
            e->prefixFromCache = true;
            return e->prefixTokens;
        }
    }
    const char *t = env->GetStringUTFChars(text, nullptr);
    std::string s(t);
    env->ReleaseStringUTFChars(text, t);

    llama_memory_clear(llama_get_memory(e->ctx), false);
    auto toks = tokenize(e->vocab, s, true);
    if (toks.empty() || !decodeAll(e, toks)) return -1;
    e->prefixTokens = (int) toks.size();
    size_t sz = llama_state_seq_get_size(e->ctx, 0);
    e->prefixState.resize(sz);
    llama_state_seq_get_data(e->ctx, e->prefixState.data(), sz, 0);
    if (!cache.empty()) savePrefixCache(e, cache.c_str());
    return e->prefixTokens;
}

// Returns the generated text (valid UTF-8) or null on failure. listener may be null;
// otherwise listener.onToken(String): Boolean is called per piece and returns false to stop.
JNIEXPORT jstring JNICALL
NANO_JNI(complete)(JNIEnv *env, jobject, jlong h, jstring suffix, jstring grammar,
                                               jint maxTokens, jobject listener) {
    auto *e = (Engine *) h;
    pin(e->cpuMask);
    auto t0 = Clock::now();

    llama_memory_clear(llama_get_memory(e->ctx), false);
    if (!e->prefixState.empty()) {
        if (llama_state_seq_set_data(e->ctx, e->prefixState.data(), e->prefixState.size(), 0) == 0) {
            LOGE("prefix state restore failed");
            return nullptr;
        }
    }

    const char *sx = env->GetStringUTFChars(suffix, nullptr);
    std::string suf(sx);
    env->ReleaseStringUTFChars(suffix, sx);
    auto toks = tokenize(e->vocab, suf, false);
    if (toks.empty() || !decodeAll(e, toks)) return nullptr;
    e->promptTokens = (int) toks.size();
    e->promptMs = msSince(t0);

    llama_sampler *gs = nullptr;
    if (grammar != nullptr) {
        const char *g = env->GetStringUTFChars(grammar, nullptr);
        gs = llama_sampler_init_grammar(e->vocab, g, "root");
        env->ReleaseStringUTFChars(grammar, g);
        if (!gs) { LOGE("grammar parse failed"); return nullptr; }
    }

    jmethodID onToken = nullptr;
    if (listener) onToken = env->GetMethodID(env->GetObjectClass(listener), "onToken", "(Ljava/lang/String;)Z");

    auto t1 = Clock::now();
    std::string out, pending;
    int n = 0;
    long sampleUs = 0, decodeUs = 0;
    auto us = [](Clock::time_point t) { return (long) std::chrono::duration_cast<std::chrono::microseconds>(Clock::now() - t).count(); };
    char buf[256];
    while (n < maxTokens) {
        auto ts = Clock::now();
        llama_token tok = pickToken(e, gs);
        if (gs) llama_sampler_accept(gs, tok);
        sampleUs += us(ts);
        if (llama_vocab_is_eog(e->vocab, tok)) break;
        int len = llama_token_to_piece(e->vocab, tok, buf, sizeof(buf), 0, false);
        if (len > 0) pending.append(buf, len);
        size_t ok = validUtf8Prefix(pending);
        if (ok > 0) {
            std::string piece = pending.substr(0, ok);
            pending.erase(0, ok);
            out += piece;
            if (onToken) {
                jstring js = env->NewStringUTF(piece.c_str());
                jboolean cont = env->CallBooleanMethod(listener, onToken, js);
                env->DeleteLocalRef(js);
                if (!cont) { n++; break; }
            }
        }
        n++;
        llama_batch b = llama_batch_get_one(&tok, 1);
        auto td = Clock::now();
        int rc = llama_decode(e->ctx, b);
        decodeUs += us(td);
        if (rc != 0) break;
    }
    e->sampleMs = sampleUs / 1000;
    e->decodeMs = decodeUs / 1000;
    e->genTokens = n;
    e->genMs = msSince(t1);
    if (gs) llama_sampler_free(gs);
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT jstring JNICALL
NANO_JNI(stats)(JNIEnv *env, jobject, jlong h) {
    auto *e = (Engine *) h;
    char b[240];
    snprintf(b, sizeof(b), "prefix=%d%s prompt=%dtok/%ldms gen=%dtok/%ldms (sample %ldms, decode %ldms)", e->prefixTokens,
             e->prefixFromCache ? "(cached)" : "",
             e->promptTokens, e->promptMs, e->genTokens, e->genMs, e->sampleMs, e->decodeMs);
    return env->NewStringUTF(b);
}

JNIEXPORT void JNICALL
NANO_JNI(free)(JNIEnv *, jobject, jlong h) {
    auto *e = (Engine *) h;
    if (!e) return;
    llama_free(e->ctx);
    if (e->pool) ggml_threadpool_free(e->pool);
    llama_model_free(e->model);
    delete e;
}

#ifdef NANO_GPU
// One line per GPU the backend can see ("name | free/total bytes"); empty when there is none. Does not load a model.
JNIEXPORT jstring JNICALL
NANO_JNI(gpuInfo)(JNIEnv *env, jobject) {
    std::string out;
    for (size_t i = 0; i < ggml_backend_dev_count(); i++) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        auto type = ggml_backend_dev_type(dev);
        if (type != GGML_BACKEND_DEVICE_TYPE_GPU && type != GGML_BACKEND_DEVICE_TYPE_IGPU) continue;
        size_t freeB = 0, totalB = 0;
        ggml_backend_dev_memory(dev, &freeB, &totalB);
        out += std::string(ggml_backend_dev_description(dev)) + " | " + std::to_string(freeB) + "/" + std::to_string(totalB) + "\n";
    }
    return env->NewStringUTF(out.c_str());
}
#endif

}  // extern "C"
