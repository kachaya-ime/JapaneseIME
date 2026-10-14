#include <jni.h>
#include <string>
#include <vector>
#include <memory>
#include <mutex>
#include <cerrno>
#include <cstring>
#include <cstdio>
#include <exception>
#include <algorithm>
#include <android/log.h>
#include "llama.h"

#define TAG "ZenzJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static void zenz_llama_log_callback(enum ggml_log_level level, const char *text, void * /* user_data */) {
    if (!text || text[0] == '\0') return;

    if (strcmp(text, ".") == 0 || strcmp(text, ".\n") == 0 || strcmp(text, "\n") == 0) {
        return;
    }

    int android_level = ANDROID_LOG_INFO;
    if (level == GGML_LOG_LEVEL_ERROR) {
        android_level = ANDROID_LOG_ERROR;
    } else if (level == GGML_LOG_LEVEL_WARN) {
        android_level = ANDROID_LOG_WARN;
    } else if (level == GGML_LOG_LEVEL_DEBUG) {
        android_level = ANDROID_LOG_DEBUG;
    }
    __android_log_print(android_level, "LlamaCPP", "%s", text);
}

static std::string sanitize_utf8(const std::string &str) {
    if (str.empty()) return "";
    std::string clean = str;
    size_t len = clean.length();
    size_t i = len;
    while (i > 0 && (static_cast<unsigned char>(clean[i - 1]) & 0xC0) == 0x80) {
        i--;
    }
    if (i > 0) {
        unsigned char leading_byte = static_cast<unsigned char>(clean[i - 1]);
        size_t expected_len = 0;
        if ((leading_byte & 0xE0) == 0xC0) expected_len = 2;
        else if ((leading_byte & 0xF0) == 0xE0) expected_len = 3;
        else if ((leading_byte & 0xF8) == 0xF0) expected_len = 4;

        if (expected_len > 0 && (len - (i - 1)) < expected_len) {
            clean.resize(i - 1);
        }
    }
    return clean;
}

struct ZenzContext {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    std::mutex mtx;
};

static ZenzContext g_zenz;

// ライフサイクル関数

extern "C" {

JNIEXPORT jboolean JNICALL
Java_io_github_kachaya_ime_zenz_ZenzEngine_nativeInit(
        JNIEnv *env, jobject /* this */, jstring jmodelPath, jint nThreads, jint nCtx, jint nGpuLayers) {
    std::lock_guard<std::mutex> lock(g_zenz.mtx);
    try {
        llama_log_set(zenz_llama_log_callback, nullptr);
        if (g_zenz.ctx) {
            llama_free(g_zenz.ctx);
            g_zenz.ctx = nullptr;
        }
        if (g_zenz.model) {
            llama_free_model(g_zenz.model);
            g_zenz.model = nullptr;
        }
        if (!jmodelPath) {
            LOGE("Model path is null");
            return JNI_FALSE;
        }

        const char *model_path = env->GetStringUTFChars(jmodelPath, nullptr);
        LOGI("Initializing Zenz with model: %s, threads: %d, ctx: %d", model_path, nThreads, nCtx);

        FILE *f = fopen(model_path, "rb");
        if (!f) {
            LOGE("Cannot open model file '%s': %s (errno=%d)", model_path, strerror(errno), errno);
            env->ReleaseStringUTFChars(jmodelPath, model_path);
            return JNI_FALSE;
        }
        fseek(f, 0, SEEK_END);
        long fsize = ftell(f);
        fclose(f);
        if (fsize <= 0) {
            LOGE("Model file is empty or invalid size: %ld", fsize);
            env->ReleaseStringUTFChars(jmodelPath, model_path);
            return JNI_FALSE;
        }

        llama_backend_init();
        llama_model_params model_params = llama_model_default_params();
        model_params.n_gpu_layers = nGpuLayers;
        g_zenz.model = llama_load_model_from_file(model_path, model_params);
        env->ReleaseStringUTFChars(jmodelPath, model_path);

        if (!g_zenz.model) {
            LOGE("Failed to load llama model");
            return JNI_FALSE;
        }

        llama_context_params ctx_params = llama_context_default_params();
        ctx_params.n_ctx = nCtx > 0 ? nCtx : 512;
        ctx_params.n_threads = nThreads > 0 ? nThreads : 4;
        ctx_params.n_threads_batch = ctx_params.n_threads;

        g_zenz.ctx = llama_new_context_with_model(g_zenz.model, ctx_params);
        if (!g_zenz.ctx) {
            LOGE("Failed to create llama context");
            llama_free_model(g_zenz.model);
            g_zenz.model = nullptr;
            return JNI_FALSE;
        }
        LOGI("Zenz model loaded successfully");
        return JNI_TRUE;
    } catch (const std::exception &e) {
        LOGE("Native init exception: %s", e.what());
        return JNI_FALSE;
    } catch (...) {
        LOGE("Unknown native init exception");
        return JNI_FALSE;
    }
}

JNIEXPORT jboolean JNICALL
Java_io_github_kachaya_ime_zenz_ZenzEngine_nativeIsLoaded(JNIEnv *env, jobject /* this */) {
    std::lock_guard<std::mutex> lock(g_zenz.mtx);
    return (g_zenz.model != nullptr && g_zenz.ctx != nullptr) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_io_github_kachaya_ime_zenz_ZenzEngine_nativeClose(JNIEnv *env, jobject /* this */) {
    std::lock_guard<std::mutex> lock(g_zenz.mtx);
    try {
        if (g_zenz.ctx) {
            llama_free(g_zenz.ctx);
            g_zenz.ctx = nullptr;
        }
        if (g_zenz.model) {
            llama_free_model(g_zenz.model);
            g_zenz.model = nullptr;
        }
        llama_backend_free();
        LOGI("Zenz model unloaded");
    } catch (const std::exception &e) { LOGE("Native close exception: %s", e.what()); }
}

// JNI メソッドの実装

JNIEXPORT jintArray JNICALL
Java_io_github_kachaya_ime_zenz_ZenzEngine_nativeTokenize(
        JNIEnv *env, jobject /* this */, jstring jtext, jboolean parseSpecial) {
    std::lock_guard<std::mutex> lock(g_zenz.mtx);
    if (!g_zenz.model) return nullptr;

    const char *text_str = env->GetStringUTFChars(jtext, nullptr);
    std::string text(text_str ? text_str : "");
    if (text_str) env->ReleaseStringUTFChars(jtext, text_str);

    if (text.empty()) return env->NewIntArray(0);

    int n_tokens = -llama_tokenize(g_zenz.model, text.c_str(), text.length(), nullptr, 0, false, parseSpecial);
    if (n_tokens <= 0) n_tokens = text.length() + 32;

    std::vector<llama_token> tokens(n_tokens);
    n_tokens = llama_tokenize(g_zenz.model, text.c_str(), text.length(), tokens.data(), tokens.size(), false, parseSpecial);
    if (n_tokens <= 0) return env->NewIntArray(0);

    jintArray result = env->NewIntArray(n_tokens);
    env->SetIntArrayRegion(result, 0, n_tokens, reinterpret_cast<const jint *>(tokens.data()));
    return result;
}

JNIEXPORT jint JNICALL
Java_io_github_kachaya_ime_zenz_ZenzEngine_nativeGetCtxSize(JNIEnv *env, jobject /* this */) {
    std::lock_guard<std::mutex> lock(g_zenz.mtx);
    if (!g_zenz.ctx) return 0;
    return llama_n_ctx(g_zenz.ctx);
}

JNIEXPORT void JNICALL
Java_io_github_kachaya_ime_zenz_ZenzEngine_nativeClearKvCache(JNIEnv *env, jobject /* this */) {
    std::lock_guard<std::mutex> lock(g_zenz.mtx);
    if (g_zenz.ctx) {
        llama_kv_cache_clear(g_zenz.ctx);
    }
}

JNIEXPORT jboolean JNICALL
Java_io_github_kachaya_ime_zenz_ZenzEngine_nativeEvalTokens(
        JNIEnv *env, jobject /* this */, jintArray jtokens, jint startPos, jint nTokens) {
    std::lock_guard<std::mutex> lock(g_zenz.mtx);
    if (!g_zenz.ctx || !jtokens || nTokens <= 0) return JNI_FALSE;

    jint *tokens = env->GetIntArrayElements(jtokens, nullptr);
    if (!tokens) return JNI_FALSE;

    llama_batch batch = llama_batch_init(nTokens, 0, 1);
    for (int i = 0; i < nTokens; ++i) {
        batch.token[i] = tokens[i];
        batch.pos[i] = startPos + i;
        batch.n_seq_id[i] = 1;
        batch.seq_id[i][0] = 0;
        batch.logits[i] = (i == nTokens - 1);
    }
    batch.n_tokens = nTokens;

    int ret = llama_decode(g_zenz.ctx, batch);
    llama_batch_free(batch);
    env->ReleaseIntArrayElements(jtokens, tokens, JNI_ABORT);

    return ret == 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_io_github_kachaya_ime_zenz_ZenzEngine_nativeSampleToken(
        JNIEnv *env, jobject /* this */, jfloat temperature) {
    std::lock_guard<std::mutex> lock(g_zenz.mtx);
    if (!g_zenz.ctx || !g_zenz.model) return -1;

    auto *logits = llama_get_logits_ith(g_zenz.ctx, -1);
    if (!logits) return -1;

    int curr_token = 0;
    float max_logit = -1e20f;
    int n_vocab = llama_n_vocab(g_zenz.model);
    for (int v = 0; v < n_vocab; ++v) {
        if (logits[v] > max_logit) {
            max_logit = logits[v];
            curr_token = v;
        }
    }
    return curr_token;
}

JNIEXPORT jbyteArray JNICALL
Java_io_github_kachaya_ime_zenz_ZenzEngine_nativeTokenToPiece(
        JNIEnv *env, jobject /* this */, jint token) {
    std::lock_guard<std::mutex> lock(g_zenz.mtx);
    if (!g_zenz.model) return nullptr;

    char buf[128];
    int n = llama_token_to_piece(g_zenz.model, token, buf, sizeof(buf));
    std::string piece = "";
    if (n < 0) {
        std::vector<char> large_buf(-n);
        int n2 = llama_token_to_piece(g_zenz.model, token, large_buf.data(), large_buf.size());
        if (n2 > 0) piece = std::string(large_buf.data(), n2);
    } else if (n > 0) {
        piece = std::string(buf, n);
    }

    if (piece.empty()) return nullptr;

    jbyteArray result = env->NewByteArray(piece.length());
    env->SetByteArrayRegion(result, 0, piece.length(), reinterpret_cast<const jbyte *>(piece.data()));
    return result;
}

JNIEXPORT jint JNICALL
Java_io_github_kachaya_ime_zenz_ZenzEngine_nativeGetEosToken(JNIEnv *env, jobject /* this */) {
    std::lock_guard<std::mutex> lock(g_zenz.mtx);
    if (!g_zenz.model) return -1;
    return llama_token_eos(g_zenz.model);
}

JNIEXPORT jintArray JNICALL
Java_io_github_kachaya_ime_zenz_ZenzEngine_nativeSampleTopKTokens(
        JNIEnv *env, jobject /* this */, jint k) {
    std::lock_guard<std::mutex> lock(g_zenz.mtx);
    if (!g_zenz.ctx || !g_zenz.model || k <= 0) return nullptr;

    const float *logits = llama_get_logits_ith(g_zenz.ctx, -1);
    if (!logits) return nullptr;

    int n_vocab = llama_n_vocab(g_zenz.model);
    if (k > n_vocab) k = n_vocab;

    std::vector<std::pair<float, int>> top_k;
    top_k.reserve(n_vocab);

    for (int v = 0; v < n_vocab; ++v) {
        top_k.push_back({logits[v], v});
    }

    std::partial_sort(top_k.begin(), top_k.begin() + k, top_k.end(),
                      [](const std::pair<float, int> &a, const std::pair<float, int> &b) {
                          return a.first > b.first;
                      });

    jintArray result = env->NewIntArray(k);
    if (!result) return nullptr;

    std::vector<jint> tokens(k);
    for (int i = 0; i < k; ++i) {
        tokens[i] = top_k[i].second;
    }

    env->SetIntArrayRegion(result, 0, k, tokens.data());
    return result;
}

JNIEXPORT void JNICALL
Java_io_github_kachaya_ime_zenz_ZenzEngine_nativeClearKvCacheAfter(
        JNIEnv *env, jobject /* this */, jint pos) {
    std::lock_guard<std::mutex> lock(g_zenz.mtx);
    if (g_zenz.ctx && pos >= 0) {
        llama_kv_cache_seq_rm(g_zenz.ctx, 0, pos, -1);
    }
}

} // extern "C"
