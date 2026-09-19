#include <android/log.h>
#include <jni.h>

#include <string>
#include <vector>

#include "llama.h"

namespace {

struct Engine {
    llama_model *model;
    llama_context *ctx;
    llama_sampler *sampler;
};

// Model loading prints hundreds of progress dots at WARN level; only real errors are worth logcat.
void log_errors(ggml_log_level level, const char *text, void *) {
    if (level == GGML_LOG_LEVEL_ERROR) {
        __android_log_write(ANDROID_LOG_WARN, "HumanRewriteLlama", text);
    }
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_humanrewrite_keyboard_core_LlamaEngine_nativeLoad(JNIEnv *env, jobject, jstring jpath, jint n_ctx, jint n_threads) {
    static bool backend_ready = false;
    if (!backend_ready) {
        llama_log_set(log_errors, nullptr);
        llama_backend_init();
        backend_ready = true;
    }

    const char *path = env->GetStringUTFChars(jpath, nullptr);
    llama_model *model = llama_model_load_from_file(path, llama_model_default_params());
    env->ReleaseStringUTFChars(jpath, path);
    if (!model) return 0;

    llama_context_params params = llama_context_default_params();
    params.n_ctx = n_ctx;
    params.n_batch = n_ctx;
    params.n_threads = n_threads;
    params.n_threads_batch = n_threads;
    llama_context *ctx = llama_init_from_model(model, params);
    if (!ctx) {
        llama_model_free(model);
        return 0;
    }

    // Greedy: a proofreader wants the single most likely correction, not creative variety.
    llama_sampler *sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
    return reinterpret_cast<jlong>(new Engine{model, ctx, sampler});
}

// Returns UTF-8 bytes (not a jstring): NewStringUTF rejects 4-byte characters such as emoji.
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_humanrewrite_keyboard_core_LlamaEngine_nativeComplete(JNIEnv *env, jobject, jlong handle, jbyteArray jprompt, jint max_tokens) {
    auto *engine = reinterpret_cast<Engine *>(handle);
    const llama_vocab *vocab = llama_model_get_vocab(engine->model);

    // The prompt arrives as real UTF-8 bytes: GetStringUTFChars would hand back Java's "modified
    // UTF-8", which encodes emoji as two 3-byte surrogates that the tokenizer can't read.
    const jsize prompt_len = env->GetArrayLength(jprompt);
    std::string prompt((size_t) prompt_len, '\0');
    env->GetByteArrayRegion(jprompt, 0, prompt_len, reinterpret_cast<jbyte *>(&prompt[0]));

    const int n_prompt = -llama_tokenize(vocab, prompt.c_str(), (int32_t) prompt.size(), nullptr, 0, true, true);
    if (n_prompt <= 0) return nullptr;
    std::vector<llama_token> tokens(n_prompt);
    if (llama_tokenize(vocab, prompt.c_str(), (int32_t) prompt.size(), tokens.data(), n_prompt, true, true) < 0) return nullptr;
    if (n_prompt + max_tokens > (int) llama_n_ctx(engine->ctx)) return nullptr;

    // Every rewrite is independent, so start from an empty context.
    llama_memory_clear(llama_get_memory(engine->ctx), true);
    if (llama_decode(engine->ctx, llama_batch_get_one(tokens.data(), n_prompt)) != 0) return nullptr;

    std::string out;
    char piece[256];
    for (int i = 0; i < max_tokens; i++) {
        llama_token token = llama_sampler_sample(engine->sampler, engine->ctx, -1);
        if (llama_vocab_is_eog(vocab, token)) break;
        const int n = llama_token_to_piece(vocab, token, piece, sizeof(piece), 0, false);
        if (n > 0) out.append(piece, n);
        if (llama_decode(engine->ctx, llama_batch_get_one(&token, 1)) != 0) break;
    }

    jbyteArray result = env->NewByteArray((jsize) out.size());
    env->SetByteArrayRegion(result, 0, (jsize) out.size(), reinterpret_cast<const jbyte *>(out.data()));
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_humanrewrite_keyboard_core_LlamaEngine_nativeFree(JNIEnv *, jobject, jlong handle) {
    auto *engine = reinterpret_cast<Engine *>(handle);
    llama_sampler_free(engine->sampler);
    llama_free(engine->ctx);
    llama_model_free(engine->model);
    delete engine;
}
