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
    if (strcmp(text, ".") == 0 || strcmp(text, ".\n") == 0 || strcmp(text, "\n") == 0) return;

    int android_level = ANDROID_LOG_INFO;
    if (level == GGML_LOG_LEVEL_ERROR) android_level = ANDROID_LOG_ERROR;
    else if (level == GGML_LOG_LEVEL_WARN) android_level = ANDROID_LOG_WARN;
    else if (level == GGML_LOG_LEVEL_DEBUG) android_level = ANDROID_LOG_DEBUG;

    __android_log_print(android_level, "LlamaCPP", "%s", text);
}

struct ZenzContext {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    std::mutex mtx;
};

static ZenzContext g_zenz;

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    llama_backend_init();
    llama_log_set(zenz_llama_log_callback, nullptr);
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNI_OnUnload(JavaVM *vm, void *reserved) {
    llama_backend_free();
}

extern "C" {

// パッケージ名 io.github.kachaya.ime.zenz.ZenzEngine に対応するJNIエクスポート
// パッケージ名やクラス名自体にアンダースコアは含まれないため、Java_io_github_kachaya_ime_zenz_ZenzEngine_ の形式が正解です。

JNIEXPORT jboolean JNICALL
Java_io_github_kachaya_ime_zenz_ZenzEngine_nativeInit(
        JNIEnv *env, jobject /* this */, jstring jmodelPath, jint nThreads, jint nCtx, jint nGpuLayers) {
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
        if (!jmodelPath) return JNI_FALSE;

        const char *model_path = env->GetStringUTFChars(jmodelPath, nullptr);
        if (!model_path) return JNI_FALSE;

        FILE *f = fopen(model_path, "rb");
        if (!f) {
            env->ReleaseStringUTFChars(jmodelPath, model_path);
            return JNI_FALSE;
        }
        fclose(f);

        llama_model_params model_params = llama_model_default_params();
        model_params.n_gpu_layers = nGpuLayers;
        g_zenz.model = llama_load_model_from_file(model_path, model_params);
        env->ReleaseStringUTFChars(jmodelPath, model_path);

        if (!g_zenz.model) return JNI_FALSE;

        llama_context_params ctx_params = llama_context_default_params();
        ctx_params.n_ctx = nCtx > 0 ? nCtx : 512;
        ctx_params.n_threads = nThreads > 0 ? nThreads : 4;
        ctx_params.n_threads_batch = ctx_params.n_threads;

        g_zenz.ctx = llama_new_context_with_model(g_zenz.model, ctx_params);
        if (!g_zenz.ctx) {
            llama_free_model(g_zenz.model);
            g_zenz.model = nullptr;
            return JNI_FALSE;
        }
        return JNI_TRUE;
    } catch (...) {
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
    if (g_zenz.ctx) {
        llama_free(g_zenz.ctx);
        g_zenz.ctx = nullptr;
    }
    if (g_zenz.model) {
        llama_free_model(g_zenz.model);
        g_zenz.model = nullptr;
    }
}

JNIEXPORT jintArray JNICALL
Java_io_github_kachaya_ime_zenz_ZenzEngine_nativeTokenize(
        JNIEnv *env, jobject /* this */, jstring jtext, jboolean parseSpecial) {
    std::lock_guard<std::mutex> lock(g_zenz.mtx);
    if (!g_zenz.model || !jtext) return nullptr;

    const char *text_str = env->GetStringUTFChars(jtext, nullptr);
    if (!text_str) return nullptr;
    std::string text(text_str);
    env->ReleaseStringUTFChars(jtext, text_str);

    if (text.empty()) return env->NewIntArray(0);

    int n_tokens = -llama_tokenize(g_zenz.model, text.c_str(), text.length(), nullptr, 0, false, parseSpecial);
    if (n_tokens <= 0) return env->NewIntArray(0);

    jintArray result = env->NewIntArray(n_tokens);
    if (!result) return nullptr;

    jint *java_tokens = env->GetIntArrayElements(result, nullptr);
    if (!java_tokens) return nullptr;

    llama_tokenize(g_zenz.model, text.c_str(), text.length(), reinterpret_cast<llama_token *>(java_tokens), n_tokens, false, parseSpecial);
    env->ReleaseIntArrayElements(result, java_tokens, 0);

    return result;
}

JNIEXPORT jint JNICALL
Java_io_github_kachaya_ime_zenz_ZenzEngine_nativeGetCtxSize(JNIEnv *env, jobject /* this */) {
    std::lock_guard<std::mutex> lock(g_zenz.mtx);
    return g_zenz.ctx ? llama_n_ctx(g_zenz.ctx) : 0;
}

JNIEXPORT void JNICALL
Java_io_github_kachaya_ime_zenz_ZenzEngine_nativeClearKvCache(JNIEnv *env, jobject /* this */) {
    std::lock_guard<std::mutex> lock(g_zenz.mtx);
    if (g_zenz.ctx) llama_kv_cache_clear(g_zenz.ctx);
}

JNIEXPORT void JNICALL
Java_io_github_kachaya_ime_zenz_ZenzEngine_nativeClearKvCacheAfter(JNIEnv *env, jobject /* this */, jint pos) {
    std::lock_guard<std::mutex> lock(g_zenz.mtx);
    if (g_zenz.ctx) llama_kv_cache_seq_rm(g_zenz.ctx, 0, pos, -1);
}

JNIEXPORT jboolean JNICALL
Java_io_github_kachaya_ime_zenz_ZenzEngine_nativeEvalTokens(
        JNIEnv *env, jobject /* this */, jintArray jtokens, jint startPos, jint nTokens) {
    std::lock_guard<std::mutex> lock(g_zenz.mtx);
    if (!g_zenz.ctx || !jtokens || nTokens <= 0) return JNI_FALSE;

    jint *tokens = env->GetIntArrayElements(jtokens, nullptr);
    if (!tokens) return JNI_FALSE;

    // 1. Zenzパッチ版の安全な生のCスタイルでバッチを初期化
    llama_batch batch = llama_batch_init(nTokens, 0, 1);

    // 2. ループ内で直接生構造体のポインタの先を安全に埋める
    for (int i = 0; i < nTokens; ++i) {
        batch.token[i] = tokens[i];
        batch.pos[i] = startPos + i;
        batch.n_seq_id[i] = 1;

        // パッチ版特有の2次元配列ポインタを安全にクリア
        if (batch.seq_id && batch.seq_id[i]) {
            batch.seq_id[i][0] = 0;
        }

        batch.logits[i] = (i == nTokens - 1);
    }
    batch.n_tokens = nTokens;

    // 3. デコード実行
    int ret = llama_decode(g_zenz.ctx, batch);

    // 4. JNI要素の解放と、最優先での生バッチの即時解放（リーク防止）
    llama_batch_free(batch);
    env->ReleaseIntArrayElements(jtokens, tokens, JNI_ABORT);

    return ret == 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_io_github_kachaya_ime_zenz_ZenzEngine_nativeSampleToken(
        JNIEnv *env, jobject /* this */, jfloat temperature, jint topK, jfloat topP) {
    std::lock_guard<std::mutex> lock(g_zenz.mtx);
    if (!g_zenz.ctx || !g_zenz.model) return -1;

    float *logits = llama_get_logits(g_zenz.ctx);
    if (!logits) return -1;

    int n_vocab = llama_n_vocab(g_zenz.model);
    std::vector<llama_token_data> candidates;
    candidates.reserve(n_vocab);
    for (int token_id = 0; token_id < n_vocab; token_id++) {
        candidates.push_back(llama_token_data{token_id, logits[token_id], 0.0f});
    }

    llama_token_data_array candidates_p = {candidates.data(), candidates.size(), false};

    if (topK > 0) llama_sample_top_k(g_zenz.ctx, &candidates_p, topK, 1);
    if (temperature > 0.0f && temperature != 1.0f) llama_sample_temp(g_zenz.ctx, &candidates_p, temperature);
    else if (temperature <= 0.0f) return llama_sample_token_greedy(g_zenz.ctx, &candidates_p);
    if (topP > 0.0f && topP < 1.0f) llama_sample_top_p(g_zenz.ctx, &candidates_p, topP, 1);

    return llama_sample_token(g_zenz.ctx, &candidates_p);
}

JNIEXPORT jintArray JNICALL
Java_io_github_kachaya_ime_zenz_ZenzEngine_nativeSampleTopKTokens(JNIEnv *env, jobject /* this */, jint k) {
    std::lock_guard<std::mutex> lock(g_zenz.mtx);
    if (!g_zenz.ctx || !g_zenz.model || k <= 0) return nullptr;

    float *logits = llama_get_logits(g_zenz.ctx);
    if (!logits) return nullptr;

    int n_vocab = llama_n_vocab(g_zenz.model);
    std::vector<llama_token_data> candidates;
    candidates.reserve(n_vocab);
    for (int token_id = 0; token_id < n_vocab; token_id++) {
        candidates.push_back(llama_token_data{token_id, logits[token_id], 0.0f});
    }

    llama_token_data_array candidates_p = {candidates.data(), candidates.size(), false};
    llama_sample_top_k(g_zenz.ctx, &candidates_p, k, 1);

    int result_size = std::min(static_cast<int>(candidates_p.size), static_cast<int>(k));
    jintArray result = env->NewIntArray(result_size);
    if (!result) return nullptr;

    jint *java_tokens = env->GetIntArrayElements(result, nullptr);
    if (!java_tokens) return nullptr;

    for (int i = 0; i < result_size; ++i) {
        java_tokens[i] = candidates_p.data[i].id;
    }

    env->ReleaseIntArrayElements(result, java_tokens, 0);
    return result;
}

JNIEXPORT jbyteArray JNICALL
Java_io_github_kachaya_ime_zenz_ZenzEngine_nativeTokenToPiece(JNIEnv *env, jobject /* this */, jint token) {
    std::lock_guard<std::mutex> lock(g_zenz.mtx);
    if (!g_zenz.model) return nullptr;

    std::vector<char> result(32);
    int n_tokens = llama_token_to_piece(g_zenz.model, token, result.data(), result.size());
    if (n_tokens < 0) {
        result.resize(-n_tokens);
        n_tokens = llama_token_to_piece(g_zenz.model, token, result.data(), result.size());
    }

    if (n_tokens <= 0) return env->NewByteArray(0);

    jbyteArray jbytes = env->NewByteArray(n_tokens);
    if (!jbytes) return nullptr;

    env->SetByteArrayRegion(jbytes, 0, n_tokens, reinterpret_cast<const jbyte *>(result.data()));
    return jbytes;
}

JNIEXPORT jint JNICALL
Java_io_github_kachaya_ime_zenz_ZenzEngine_nativeGetEosToken(JNIEnv *env, jobject /* this */) {
    std::lock_guard<std::mutex> lock(g_zenz.mtx);
    if (!g_zenz.model) return -1;
    return static_cast<jint>(llama_token_eos(g_zenz.model));
}

} // extern "C"
