#include <jni.h>
#include <string>
#include <vector>
#include <memory>
#include <mutex>
#include <cerrno>
#include <cstring>
#include <cstdio>
#include <exception>
#include <android/log.h>
#include "llama.h"

#define TAG "ZenzJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static void zenz_llama_log_callback(enum ggml_log_level level, const char* text, void* /* user_data */) {
    if (!text || text[0] == '\0') return;

    // Filter out dot progress and lone newlines from llama.cpp tensor loading
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

static std::string sanitize_utf8(const std::string& str) {
    if (str.empty()) return "";

    std::string clean = str;
    size_t len = clean.length();

    // Check trailing bytes for incomplete UTF-8 multi-byte character
    size_t i = len;
    while (i > 0 && (static_cast<unsigned char>(clean[i - 1]) & 0xC0) == 0x80) {
        i--; // Skip continuation bytes
    }

    if (i > 0) {
        unsigned char leading_byte = static_cast<unsigned char>(clean[i - 1]);
        size_t expected_len = 0;
        if ((leading_byte & 0xE0) == 0xC0) {
            expected_len = 2;
        } else if ((leading_byte & 0xF0) == 0xE0) {
            expected_len = 3;
        } else if ((leading_byte & 0xF8) == 0xF0) {
            expected_len = 4;
        }

        if (expected_len > 0 && (len - (i - 1)) < expected_len) {
            // Truncate the trailing incomplete multi-byte sequence
            clean.resize(i - 1);
        }
    }

    return clean;
}

struct ZenzContext {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    std::mutex mtx;
};

static ZenzContext g_zenz;

extern "C" {

JNIEXPORT jboolean JNICALL
Java_io_github_kachaya_ime_zenz_ZenzEngine_nativeInit(
        JNIEnv* env,
        jobject /* this */,
        jstring jmodelPath,
        jint nThreads,
        jint nCtx,
        jint nGpuLayers) {
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

        const char* model_path = env->GetStringUTFChars(jmodelPath, nullptr);
        LOGI("Initializing Zenz with model: %s, threads: %d, ctx: %d", model_path, nThreads, nCtx);

        FILE* f = fopen(model_path, "rb");
        if (!f) {
            LOGE("Cannot open model file '%s': %s (errno=%d)", model_path, strerror(errno), errno);
            env->ReleaseStringUTFChars(jmodelPath, model_path);
            return JNI_FALSE;
        }

        fseek(f, 0, SEEK_END);
        long fsize = ftell(f);
        fclose(f);
        LOGI("Model file exists, size: %ld bytes", fsize);

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
            LOGE("Failed to load llama model from file");
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
    } catch (const std::exception& e) {
        LOGE("Native init exception: %s", e.what());
        return JNI_FALSE;
    } catch (...) {
        LOGE("Unknown native init exception");
        return JNI_FALSE;
    }
}

JNIEXPORT jboolean JNICALL
Java_io_github_kachaya_ime_zenz_ZenzEngine_nativeIsLoaded(JNIEnv* env, jobject /* this */) {
    std::lock_guard<std::mutex> lock(g_zenz.mtx);
    return (g_zenz.model != nullptr && g_zenz.ctx != nullptr) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_io_github_kachaya_ime_zenz_ZenzEngine_nativeClose(JNIEnv* env, jobject /* this */) {
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
    } catch (const std::exception& e) {
        LOGE("Native close exception: %s", e.what());
    }
}

JNIEXPORT jstring JNICALL
Java_io_github_kachaya_ime_zenz_ZenzEngine_nativeConvert(
        JNIEnv* env,
        jobject /* this */,
        jstring jprompt,
        jint maxTokens,
        jfloat temperature) {
    std::lock_guard<std::mutex> lock(g_zenz.mtx);

    if (!g_zenz.model || !g_zenz.ctx) {
        LOGE("Zenz model not loaded");
        return env->NewStringUTF("");
    }

    const char* prompt_str = env->GetStringUTFChars(jprompt, nullptr);
    std::string prompt(prompt_str ? prompt_str : "");
    if (prompt_str) {
        env->ReleaseStringUTFChars(jprompt, prompt_str);
    }

    if (prompt.empty()) {
        return env->NewStringUTF("");
    }

    try {
        // Tokenize prompt
        int n_prompt_tokens = -llama_tokenize(g_zenz.model, prompt.c_str(), prompt.length(), nullptr, 0, false, false);
        if (n_prompt_tokens <= 0) {
            n_prompt_tokens = prompt.length() + 32;
        }

        std::vector<llama_token> prompt_tokens(n_prompt_tokens);
        n_prompt_tokens = llama_tokenize(g_zenz.model, prompt.c_str(), prompt.length(), prompt_tokens.data(), prompt_tokens.size(), false, false);
        if (n_prompt_tokens <= 0) {
            LOGE("Failed to tokenize prompt");
            return env->NewStringUTF("");
        }
        prompt_tokens.resize(n_prompt_tokens);

        int n_ctx = llama_n_ctx(g_zenz.ctx);
        int max_gen = maxTokens > 0 ? maxTokens : 64;
        if (n_prompt_tokens > n_ctx - max_gen) {
            n_prompt_tokens = n_ctx - max_gen;
            if (n_prompt_tokens < 1) n_prompt_tokens = 1;
            prompt_tokens.resize(n_prompt_tokens);
        }

        // Reset KV sequence cache
        llama_kv_cache_clear(g_zenz.ctx);

        // Allocate batch
        int batch_size = n_prompt_tokens > max_gen ? n_prompt_tokens : max_gen;
        llama_batch batch = llama_batch_init(batch_size, 0, 1);

        for (int i = 0; i < n_prompt_tokens; ++i) {
            batch.token[i] = prompt_tokens[i];
            batch.pos[i] = i;
            batch.n_seq_id[i] = 1;
            batch.seq_id[i][0] = 0;
            batch.logits[i] = (i == n_prompt_tokens - 1);
        }
        batch.n_tokens = n_prompt_tokens;

        if (llama_decode(g_zenz.ctx, batch) != 0) {
            LOGE("Failed to decode prompt batch");
            llama_batch_free(batch);
            return env->NewStringUTF("");
        }

        int n_vocab = llama_n_vocab(g_zenz.model);
        llama_token eos_token = llama_token_eos(g_zenz.model);

        std::string output;
        for (int i = 0; i < max_gen; ++i) {
            float* logits = llama_get_logits(g_zenz.ctx);
            if (!logits) {
                LOGE("Failed to get logits");
                break;
            }

            std::vector<llama_token_data> candidates;
            candidates.reserve(n_vocab);
            for (llama_token token_id = 0; token_id < n_vocab; token_id++) {
                candidates.push_back({token_id, logits[token_id], 0.0f});
            }
            llama_token_data_array candidates_p = { candidates.data(), candidates.size(), false };

            llama_token new_token_id;
            if (temperature > 0.01f) {
                llama_sample_temp(g_zenz.ctx, &candidates_p, temperature);
                new_token_id = llama_sample_token(g_zenz.ctx, &candidates_p);
            } else {
                new_token_id = llama_sample_token_greedy(g_zenz.ctx, &candidates_p);
            }

            if (new_token_id == eos_token) {
                break;
            }

            char piece[128];
            int n_piece = llama_token_to_piece(g_zenz.model, new_token_id, piece, sizeof(piece));
            if (n_piece > 0) {
                std::string s(piece, n_piece);
                if (s.find("\uEE00") != std::string::npos ||
                    s.find("\uEE01") != std::string::npos ||
                    s.find("\uEE02") != std::string::npos ||
                    s.find("\uEE07") != std::string::npos ||
                    s.find("<|im_end|>") != std::string::npos ||
                    s.find("<|endoftext|>") != std::string::npos ||
                    s.find("</s>") != std::string::npos) {
                    break;
                }
                output += s;
            }

            batch.token[0] = new_token_id;
            batch.pos[0] = n_prompt_tokens + i;
            batch.n_seq_id[0] = 1;
            batch.seq_id[0][0] = 0;
            batch.logits[0] = true;
            batch.n_tokens = 1;

            if (llama_decode(g_zenz.ctx, batch) != 0) {
                LOGE("Failed to decode generated token");
                break;
            }
        }

        llama_batch_free(batch);

        std::string safe_output = sanitize_utf8(output);
        return env->NewStringUTF(safe_output.c_str());
    } catch (const std::exception& e) {
        LOGE("Native convert exception: %s", e.what());
        return env->NewStringUTF("");
    } catch (...) {
        LOGE("Unknown native convert exception");
        return env->NewStringUTF("");
    }
}

} // extern "C"
