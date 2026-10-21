/**
 * @file llama_jni.cpp
 * @brief オリジナル llama.cpp (ggerganov/llama.cpp) と Android IME (JinenEngine / Karukan) を接続する JNI ネイティブブリッジ実装です。
 */

#include <jni.h>
#include <string>
#include <vector>
#include <memory>
#include <mutex>
#include <algorithm>
#include <android/log.h>
#include "llama.h"
#include "ggml-backend.h"
#include "ggml-cpu.h"

namespace llama_jni_internal {

/**
 * @brief ネイティブ層で保持する llama.cpp 用コンテキスト構造体です。
 */
struct LlamaContext {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    std::mutex mtx;
};

static LlamaContext g_llama;

static void llama_log_callback(enum ggml_log_level level, const char *text, void * /* user_data */) {
    if (!text || text[0] == '\0') {
        return;
    }
    int android_level = ANDROID_LOG_INFO;
    if (level == GGML_LOG_LEVEL_ERROR) {
        android_level = ANDROID_LOG_ERROR;
    } else if (level == GGML_LOG_LEVEL_WARN) {
        android_level = ANDROID_LOG_WARN;
    }
    __android_log_print(android_level, "LlamaCPP", "%s", text);
}

} // namespace llama_jni_internal

using namespace llama_jni_internal;

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    llama_backend_init();
    ggml_backend_register(ggml_backend_cpu_reg());
    llama_log_set(llama_log_callback, nullptr);
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNI_OnUnload(JavaVM *vm, void *reserved) {
    llama_backend_free();
}

extern "C" {

/**
 * オリジナル llama.cpp を使用してモデルファイルを読み込み、ネイティブエンジンを初期化します。
 */
JNIEXPORT jboolean JNICALL
Java_io_github_kachaya_ime_jinen_JinenEngine_nativeInit(
        JNIEnv *env, jobject /* this */, jstring jmodelPath, jint nThreads) {
    std::lock_guard<std::mutex> lock(g_llama.mtx);
    if (!jmodelPath) {
        return JNI_FALSE;
    }

    const char *model_path = env->GetStringUTFChars(jmodelPath, nullptr);
    if (!model_path) {
        return JNI_FALSE;
    }

    if (g_llama.ctx) {
        llama_free(g_llama.ctx);
        g_llama.ctx = nullptr;
    }
    if (g_llama.model) {
        llama_model_free(g_llama.model);
        g_llama.model = nullptr;
    }

    // 静的リンクされた CPU バックエンドを登録
    ggml_backend_register(ggml_backend_cpu_reg());

    llama_model_params model_params = llama_model_default_params();
    g_llama.model = llama_model_load_from_file(model_path, model_params);
    env->ReleaseStringUTFChars(jmodelPath, model_path);

    if (!g_llama.model) {
        __android_log_print(ANDROID_LOG_ERROR, "LlamaJNI", "Failed to load model file from path: %s", model_path);
        return JNI_FALSE;
    }

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = 512;
    ctx_params.n_threads = nThreads > 0 ? nThreads : 4;
    ctx_params.n_threads_batch = ctx_params.n_threads;

    g_llama.ctx = llama_init_from_model(g_llama.model, ctx_params);
    if (!g_llama.ctx) {
        __android_log_print(ANDROID_LOG_ERROR, "LlamaJNI", "Failed to create context with model.");
        llama_model_free(g_llama.model);
        g_llama.model = nullptr;
        return JNI_FALSE;
    }

    __android_log_print(ANDROID_LOG_INFO, "LlamaJNI", "Successfully initialized llama.cpp engine.");
    return JNI_TRUE;
}

/**
 * モデルが読み込み済みかを確認します。
 */
JNIEXPORT jboolean JNICALL
Java_io_github_kachaya_ime_jinen_JinenEngine_nativeIsLoaded(JNIEnv *env, jobject /* this */) {
    std::lock_guard<std::mutex> lock(g_llama.mtx);
    return (g_llama.model != nullptr && g_llama.ctx != nullptr) ? JNI_TRUE : JNI_FALSE;
}

/**
 * llama.cpp ネイティブエンジンのリソースを解放します。
 */
JNIEXPORT void JNICALL
Java_io_github_kachaya_ime_jinen_JinenEngine_nativeClose(JNIEnv *env, jobject /* this */) {
    std::lock_guard<std::mutex> lock(g_llama.mtx);
    if (g_llama.ctx) {
        llama_free(g_llama.ctx);
        g_llama.ctx = nullptr;
    }
    if (g_llama.model) {
        llama_model_free(g_llama.model);
        g_llama.model = nullptr;
    }
}

/**
 * NFKC 正規化されたプロンプト文字列から karukan / jinen v2 モデルで Greedy かな漢字変換結果を生成します。
 */
JNIEXPORT jobjectArray JNICALL
Java_io_github_kachaya_ime_jinen_JinenEngine_nativeConvert(
        JNIEnv *env, jobject /* this */, jstring jprompt, jint maxCandidates, jfloat temperature) {
    std::lock_guard<std::mutex> lock(g_llama.mtx);
    if (!g_llama.model || !g_llama.ctx || !jprompt) {
        return nullptr;
    }

    const char *prompt_str = env->GetStringUTFChars(jprompt, nullptr);
    if (!prompt_str) {
        return nullptr;
    }
    std::string prompt(prompt_str);
    env->ReleaseStringUTFChars(jprompt, prompt_str);

    const struct llama_vocab * vocab = llama_model_get_vocab(g_llama.model);
    if (!vocab) {
        return nullptr;
    }

    // jinen v2 用トークナイズ (add_special = false, parse_special = true)
    int n_tokens = -llama_tokenize(vocab, prompt.c_str(), prompt.length(), nullptr, 0, false, true);
    if (n_tokens <= 0) {
        jclass stringClass = env->FindClass("java/lang/String");
        return env->NewObjectArray(0, stringClass, nullptr);
    }

    std::vector<llama_token> tokens(n_tokens);
    llama_tokenize(vocab, prompt.c_str(), prompt.length(), tokens.data(), tokens.size(), false, true);

    __android_log_print(ANDROID_LOG_INFO, "LlamaJNI", "Prompt tokenized into %d tokens. Prompt: '%s'", (int)tokens.size(), prompt.c_str());

    llama_memory_t mem = llama_get_memory(g_llama.ctx);
    if (mem) {
        llama_memory_clear(mem, true);
    }

    // プロンプト評価 (Decode)
    llama_batch batch = llama_batch_init(tokens.size(), 0, 1);
    for (size_t i = 0; i < tokens.size(); ++i) {
        batch.token[i] = tokens[i];
        batch.pos[i] = i;
        batch.n_seq_id[i] = 1;
        if (batch.seq_id && batch.seq_id[i]) {
            batch.seq_id[i][0] = 0;
        }
        batch.logits[i] = (i == tokens.size() - 1);
    }
    batch.n_tokens = tokens.size();

    if (llama_decode(g_llama.ctx, batch) != 0) {
        __android_log_print(ANDROID_LOG_ERROR, "LlamaJNI", "Failed to decode prompt batch.");
        llama_batch_free(batch);
        jclass stringClass = env->FindClass("java/lang/String");
        return env->NewObjectArray(0, stringClass, nullptr);
    }
    llama_batch_free(batch);

    // 必ず greedy (--temp 0 --top-k 1) でトークン生成
    struct llama_sampler * smpl = llama_sampler_init_greedy();
    std::string generated_text = "";
    int max_tokens = 32;
    llama_token eos_token = llama_vocab_eos(vocab);

    for (int i = 0; i < max_tokens; ++i) {
        llama_token new_token_id = llama_sampler_sample(smpl, g_llama.ctx, -1);
        llama_sampler_accept(smpl, new_token_id);

        if (new_token_id == eos_token) {
            __android_log_print(ANDROID_LOG_INFO, "LlamaJNI", "Reached EOS token at step %d", i);
            break;
        }

        std::vector<char> piece(32);
        int n_piece = llama_token_to_piece(vocab, new_token_id, piece.data(), piece.size(), 0, false);
        if (n_piece > 0) {
            generated_text.append(piece.data(), n_piece);
        }

        // 次のトークンをデコード
        llama_batch next_batch = llama_batch_init(1, 0, 1);
        next_batch.token[0] = new_token_id;
        next_batch.pos[0] = tokens.size() + i;
        next_batch.n_seq_id[0] = 1;
        if (next_batch.seq_id && next_batch.seq_id[0]) {
            next_batch.seq_id[0][0] = 0;
        }
        next_batch.logits[0] = true;
        next_batch.n_tokens = 1;

        if (llama_decode(g_llama.ctx, next_batch) != 0) {
            llama_batch_free(next_batch);
            break;
        }
        llama_batch_free(next_batch);
    }

    llama_sampler_free(smpl);

    // 改行等の整形
    size_t newline_pos = generated_text.find('\n');
    if (newline_pos != std::string::npos) {
        generated_text = generated_text.substr(0, newline_pos);
    }

    __android_log_print(ANDROID_LOG_INFO, "LlamaJNI", "Jinen v2 Output Result: '%s'", generated_text.c_str());

    jclass stringClass = env->FindClass("java/lang/String");
    if (generated_text.empty()) {
        return env->NewObjectArray(0, stringClass, nullptr);
    }

    jobjectArray resultArray = env->NewObjectArray(1, stringClass, nullptr);
    jstring jcand = env->NewStringUTF(generated_text.c_str());
    env->SetObjectArrayElement(resultArray, 0, jcand);
    env->DeleteLocalRef(jcand);

    return resultArray;
}

} // extern "C"
