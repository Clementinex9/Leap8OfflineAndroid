// Independent b11982 ABI. Never load this bridge in the old CPU/GPU workers.
#include <android/log.h>
#include <jni.h>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstdint>
#include <cstring>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>
#include "llama.h"
#include "ggml-backend.h"

namespace {
std::mutex guard;
std::atomic<bool> htpSession{false};
std::atomic<int> offloadedLayers{0};
ggml_backend_dev_t devices[2] = {nullptr, nullptr};
struct Engine {
    llama_model *model = nullptr;
    llama_context *context = nullptr;
    std::string path;
    ~Engine() { if (context) llama_free(context); if (model) llama_model_free(model); }
};
std::unique_ptr<Engine> engine;
bool initialized = false;
bool usageValid = false;
int64_t lastInputTokens = 0, lastOutputTokens = 0, lastPromptMs = 0, lastDecodeMs = 0;
void logCallback(ggml_log_level level, const char *text, void *) {
    if (!text) return;
    if (std::strstr(text, "HTP0 new session")) htpSession = true;
    int count = 0, total = 0;
    if (std::sscanf(text, "load_tensors: offloaded %d/%d layers", &count, &total) == 2) offloadedLayers = count;
    // Retain real HTP/offload evidence and warnings, but do not flood logcat with
    // thousands of per-node DEBUG graph lines while a learner is scrolling.
    if (level == GGML_LOG_LEVEL_DEBUG || level == GGML_LOG_LEVEL_NONE) return;
    const int priority = level == GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR :
        level == GGML_LOG_LEVEL_WARN ? ANDROID_LOG_WARN : ANDROID_LOG_INFO;
    __android_log_write(priority, "Leap8Hexagon", text);
}
std::string bytes(JNIEnv *env, jbyteArray input) {
    if (!input) throw std::runtime_error("Missing Hexagon input");
    const auto size = env->GetArrayLength(input);
    std::string value(size, '\0');
    env->GetByteArrayRegion(input, 0, size, reinterpret_cast<jbyte *>(value.data()));
    if (env->ExceptionCheck()) throw std::runtime_error("Unable to read Hexagon input");
    if (value.find('\0') != std::string::npos) throw std::runtime_error("NUL in Hexagon input");
    return value;
}
jbyteArray toBytes(JNIEnv *env, const std::string &value) {
    auto result = env->NewByteArray(static_cast<jsize>(value.size()));
    if (result) env->SetByteArrayRegion(result, 0, static_cast<jsize>(value.size()), reinterpret_cast<const jbyte *>(value.data()));
    return result;
}
void fail(JNIEnv *env, const char *message, bool invalidInput = false) {
    if (!env->ExceptionCheck()) {
        auto type = env->FindClass(invalidInput ? "java/lang/IllegalArgumentException" : "java/lang/IllegalStateException");
        if (type) { env->ThrowNew(type, message); env->DeleteLocalRef(type); }
    }
}
void initialize(const std::string &directory) {
    if (initialized) return;
    llama_log_set(logCallback, nullptr);
    ggml_log_set(logCallback, nullptr);
    (void) directory;
    // The official Snapdragon artifact builds these backends into its registry (DL=OFF).
    // Its .so files do not expose ggml_backend_init and must not be loaded as DL plugins.
    for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
        auto device = ggml_backend_dev_get(i);
        if (std::string(ggml_backend_dev_name(device)) == "HTP0" &&
            std::string(ggml_backend_dev_description(device)).find("Hexagon") != std::string::npos) devices[0] = device;
    }
    if (!devices[0]) throw std::runtime_error("Real Hexagon HTP0 device not detected");
    llama_backend_init();
    initialized = true;
}
void load(const std::string &path) {
    if (engine && engine->path == path) return;
    engine.reset();
    offloadedLayers = 0;
    auto next = std::make_unique<Engine>();
    auto modelParams = llama_model_default_params();
    modelParams.devices = devices;
    modelParams.n_gpu_layers = 99;
    modelParams.split_mode = LLAMA_SPLIT_MODE_NONE;
    modelParams.load_mode = LLAMA_LOAD_MODE_NONE;
    next->model = llama_model_load_from_file(path.c_str(), modelParams);
    if (!next->model) throw std::runtime_error("Hexagon GGUF model load failed");
    if (!htpSession || offloadedLayers <= 0) throw std::runtime_error("No real HTP session and offloaded layers; refusing NPU label");
    auto contextParams = llama_context_default_params();
    contextParams.n_ctx = 4096;
    contextParams.n_batch = 64;
    contextParams.n_ubatch = 64;
    contextParams.n_outputs_max = 1;
    contextParams.n_threads = 4;
    contextParams.n_threads_batch = 4;
    contextParams.offload_kqv = true;
    contextParams.op_offload = true;
    next->context = llama_init_from_model(next->model, contextParams);
    if (!next->context) throw std::runtime_error("Hexagon context allocation failed");
    next->path = path;
    engine = std::move(next);
}
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_leap8_qairt_HexagonEssayBridge_nativeGenerate(JNIEnv *env, jobject, jbyteArray libraryDir, jbyteArray modelPath,
        jbyteArray promptBytes, jint maxTokens, jobject consumer) {
    std::lock_guard<std::mutex> lock(guard);
    usageValid = false;
    try {
        initialize(bytes(env, libraryDir));
        load(bytes(env, modelPath));
        const auto prompt = bytes(env, promptBytes);
        auto vocab = llama_model_get_vocab(engine->model);
        int required = llama_tokenize(vocab, prompt.data(), static_cast<int32_t>(prompt.size()), nullptr, 0, true, true);
        if (required >= 0 || required < -16384) throw std::runtime_error("Invalid or oversized NPU prompt");
        std::vector<llama_token> tokens(static_cast<size_t>(-required));
        const int count = llama_tokenize(vocab, prompt.data(), static_cast<int32_t>(prompt.size()), tokens.data(), static_cast<int32_t>(tokens.size()), true, true);
        if (count < 0) throw std::runtime_error("NPU tokenizer failed");
        tokens.resize(count);
        const int limit = std::clamp(static_cast<int>(maxTokens), 1, 1536);
        if (tokens.size() + limit >= llama_n_ctx(engine->context)) throw std::invalid_argument("Prompt plus output exceeds 4096-token NPU context; shorten the essay");
        llama_memory_clear(llama_get_memory(engine->context), true);
        const auto promptStarted = std::chrono::steady_clock::now();
        for (size_t offset = 0; offset < tokens.size(); offset += 64) {
            auto batch = llama_batch_get_one(tokens.data() + offset, static_cast<int32_t>(std::min<size_t>(64, tokens.size() - offset)));
            if (llama_decode(engine->context, batch) != 0) throw std::runtime_error("HTP prompt decode failed");
        }
        llama_synchronize(engine->context);
        const auto promptFinished = std::chrono::steady_clock::now();
        std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(llama_sampler_init_greedy(), llama_sampler_free);
        jmethodID accept = nullptr;
        if (consumer) {
            auto clazz = env->GetObjectClass(consumer);
            accept = env->GetMethodID(clazz, "accept", "(Ljava/lang/Object;)V");
            env->DeleteLocalRef(clazz);
            if (!accept || env->ExceptionCheck()) throw std::runtime_error("Invalid NPU stream consumer");
        }
        std::string output;
        int generated = 0;
        const auto decodeStarted = std::chrono::steady_clock::now();
        for (int i = 0; i < limit; ++i) {
            auto token = llama_sampler_sample(sampler.get(), engine->context, -1);
            if (llama_vocab_is_eog(vocab, token)) break;
            ++generated; // Count tokens even when their UTF-8 piece is empty.
            llama_sampler_accept(sampler.get(), token);
            std::vector<char> piece(256);
            int length = llama_token_to_piece(vocab, token, piece.data(), static_cast<int32_t>(piece.size()), 0, false);
            if (length < 0) {
                piece.resize(static_cast<size_t>(-length));
                length = llama_token_to_piece(vocab, token, piece.data(), static_cast<int32_t>(piece.size()), 0, false);
            }
            if (length < 0) throw std::runtime_error("NPU token conversion failed");
            output.append(piece.data(), length);
            if (consumer && length) {
                auto chunk = toBytes(env, std::string(piece.data(), length));
                if (chunk) { env->CallVoidMethod(consumer, accept, chunk); env->DeleteLocalRef(chunk); }
                if (env->ExceptionCheck()) throw std::runtime_error("NPU stream cancelled");
            }
            auto batch = llama_batch_get_one(&token, 1);
            if (llama_decode(engine->context, batch) != 0) throw std::runtime_error("HTP generation decode failed");
        }
        if (output.empty()) throw std::runtime_error("Hexagon returned empty generation");
        llama_synchronize(engine->context);
        const auto decodeFinished = std::chrono::steady_clock::now();
        lastInputTokens = count;
        lastOutputTokens = generated;
        lastPromptMs = std::chrono::duration_cast<std::chrono::milliseconds>(promptFinished - promptStarted).count();
        lastDecodeMs = std::chrono::duration_cast<std::chrono::milliseconds>(decodeFinished - decodeStarted).count();
        usageValid = true;
        return toBytes(env, output);
    } catch (const std::exception &error) {
        fail(env, error.what(), dynamic_cast<const std::invalid_argument *>(&error) != nullptr);
        return nullptr;
    }
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_leap8_qairt_HexagonEssayBridge_nativeLastUsage(JNIEnv *env, jobject) {
    std::lock_guard<std::mutex> lock(guard);
    if (!usageValid) return nullptr;
    const jlong values[] = {1, lastInputTokens, lastOutputTokens, lastPromptMs, lastDecodeMs};
    auto result = env->NewLongArray(5);
    if (result) env->SetLongArrayRegion(result, 0, 5, values);
    return result;
}

extern "C" JNIEXPORT void JNICALL Java_com_leap8_qairt_HexagonEssayBridge_nativeClose(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(guard);
    engine.reset();
    usageValid = false;
}
