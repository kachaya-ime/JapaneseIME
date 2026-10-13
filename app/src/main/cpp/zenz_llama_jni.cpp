#include <jni.h>
#include <string>
#include <vector>
#include <memory>
#include <mutex>
#include <cstring>
#include <android/log.h>

#include "llama.h"

#define LOG_TAG "ZenzLlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

struct ZenzLlamaContext {
    std::string model_path;
    int n_threads;
    int n_ctx;
    bool is_loaded;
    llama_model *model;
    llama_context *ctx;
    std::mutex mtx;

    ZenzLlamaContext(const std::string& path, int threads, int ctx_size)
        : model_path(path), n_threads(threads), n_ctx(ctx_size), is_loaded(false),
          model(nullptr), ctx(nullptr) {}
};

static void llama_log_callback(ggml_log_level level, const char * text, void * user_data) {
    if (!text) return;
    if (level == GGML_LOG_LEVEL_ERROR) {
        LOGE("llama.cpp: %s", text);
    } else if (level == GGML_LOG_LEVEL_WARN) {
        LOGW("llama.cpp: %s", text);
    }
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_io_github_kachaya_ime_engine_LlamaBridge_nativeInit(
        JNIEnv *env, jclass clazz, jstring jmodel_path, jint n_threads, jint n_ctx) {
    if (!jmodel_path) {
        LOGE("Model path is null");
        return 0;
    }

    const char *path_cstr = env->GetStringUTFChars(jmodel_path, nullptr);
    std::string model_path(path_cstr);
    env->ReleaseStringUTFChars(jmodel_path, path_cstr);

    LOGI("Initializing zenz_llama with model path: %s, threads: %d, ctx: %d",
         model_path.c_str(), n_threads, n_ctx);

    llama_log_set(llama_log_callback, nullptr);
    llama_backend_init();

    auto zctx = new ZenzLlamaContext(model_path, n_threads, n_ctx);

    llama_model_params model_params = llama_model_default_params();

    // Override unknown custom pre-tokenizer names (e.g., gpt2-small-japanese-char) to "gpt-2"
    static llama_model_kv_override kv_overrides[2];
    memset(kv_overrides, 0, sizeof(kv_overrides));
    strncpy(kv_overrides[0].key, "tokenizer.ggml.pre", sizeof(kv_overrides[0].key) - 1);
    kv_overrides[0].tag = LLAMA_KV_OVERRIDE_TYPE_STR;
    strncpy(kv_overrides[0].val_str, "gpt-2", sizeof(kv_overrides[0].val_str) - 1);
    kv_overrides[1].key[0] = 0;

    model_params.kv_overrides = kv_overrides;

    zctx->model = llama_model_load_from_file(model_path.c_str(), model_params);

    if (!zctx->model) {
        LOGE("Failed to load llama model from: %s", model_path.c_str());
        delete zctx;
        return 0;
    }

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = n_ctx;
    ctx_params.n_threads = n_threads;
    zctx->ctx = llama_init_from_model(zctx->model, ctx_params);

    if (!zctx->ctx) {
        LOGE("Failed to create llama context");
        llama_model_free(zctx->model);
        delete zctx;
        return 0;
    }

    zctx->is_loaded = true;
    LOGI("Successfully initialized llama model and context!");

    return reinterpret_cast<jlong>(zctx);
}

JNIEXPORT jstring JNICALL
Java_io_github_kachaya_ime_engine_LlamaBridge_nativeGenerate(
        JNIEnv *env, jclass clazz, jlong handle, jstring jprompt, jint max_tokens, jfloat temperature) {
    if (handle == 0) {
        LOGE("Invalid handle in nativeGenerate");
        return env->NewStringUTF("");
    }

    auto zctx = reinterpret_cast<ZenzLlamaContext *>(handle);
    std::lock_guard<std::mutex> lock(zctx->mtx);

    if (!zctx->is_loaded || !zctx->model || !zctx->ctx) {
        LOGE("Context or model not loaded in nativeGenerate");
        return env->NewStringUTF("");
    }

    const char *prompt_cstr = env->GetStringUTFChars(jprompt, nullptr);
    std::string prompt(prompt_cstr ? prompt_cstr : "");
    if (prompt_cstr) {
        env->ReleaseStringUTFChars(jprompt, prompt_cstr);
    }

    LOGI("Generating for prompt: %s (max_tokens=%d, temp=%.2f)",
         prompt.c_str(), max_tokens, temperature);

    // Tokenize prompt
    const llama_vocab *vocab = llama_model_get_vocab(zctx->model);
    int n_prompt_tokens = -llama_tokenize(vocab, prompt.c_str(), prompt.length(), nullptr, 0, true, true);
    if (n_prompt_tokens <= 0) {
        LOGE("Failed to calculate prompt token count");
        return env->NewStringUTF("");
    }

    std::vector<llama_token> prompt_tokens(n_prompt_tokens);
    if (llama_tokenize(vocab, prompt.c_str(), prompt.length(), prompt_tokens.data(), prompt_tokens.size(), true, true) < 0) {
        LOGE("Failed to tokenize prompt");
        return env->NewStringUTF("");
    }

    // Clear previous KV cache memory
    llama_memory_clear(llama_get_memory(zctx->ctx), true);

    // Evaluate prompt
    llama_batch batch = llama_batch_get_one(prompt_tokens.data(), prompt_tokens.size());
    if (llama_decode(zctx->ctx, batch) != 0) {
        LOGE("llama_decode failed for prompt");
        return env->NewStringUTF("");
    }

    // Initialize sampler
    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    llama_sampler *smpl = llama_sampler_chain_init(sparams);
    if (temperature <= 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }

    std::string generated_text;
    int n_cur = prompt_tokens.size();

    for (int i = 0; i < max_tokens; i++) {
        llama_token new_token_id = llama_sampler_sample(smpl, zctx->ctx, -1);
        llama_sampler_accept(smpl, new_token_id);

        if (llama_vocab_is_eog(vocab, new_token_id)) {
            LOGI("EOG token reached");
            break;
        }

        char piece_buf[128];
        int n_piece = llama_token_to_piece(vocab, new_token_id, piece_buf, sizeof(piece_buf), 0, true);
        if (n_piece > 0) {
            generated_text.append(piece_buf, n_piece);
        }

        llama_batch next_batch = llama_batch_get_one(&new_token_id, 1);
        if (llama_decode(zctx->ctx, next_batch) != 0) {
            LOGE("llama_decode failed during generation");
            break;
        }
    }

    llama_sampler_free(smpl);

    LOGI("Generated text: %s", generated_text.c_str());

    return env->NewStringUTF(generated_text.c_str());
}

JNIEXPORT void JNICALL
Java_io_github_kachaya_ime_engine_LlamaBridge_nativeFree(
        JNIEnv *env, jclass clazz, jlong handle) {
    if (handle == 0) return;
    auto zctx = reinterpret_cast<ZenzLlamaContext *>(handle);
    {
        std::lock_guard<std::mutex> lock(zctx->mtx);
        zctx->is_loaded = false;
        if (zctx->ctx) {
            llama_free(zctx->ctx);
            zctx->ctx = nullptr;
        }
        if (zctx->model) {
            llama_model_free(zctx->model);
            zctx->model = nullptr;
        }
    }
    delete zctx;
    LOGI("Freed zenz_llama context and model");
}

JNIEXPORT jboolean JNICALL
Java_io_github_kachaya_ime_engine_LlamaBridge_nativeIsLoaded(
        JNIEnv *env, jclass clazz, jlong handle) {
    if (handle == 0) return JNI_FALSE;
    auto zctx = reinterpret_cast<ZenzLlamaContext *>(handle);
    return (zctx->is_loaded && zctx->model && zctx->ctx) ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
