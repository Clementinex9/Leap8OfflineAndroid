#include <android/log.h>
#include <dirent.h>
#include <dlfcn.h>
#include <jni.h>
#include <algorithm>
#include <cmath>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <limits>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

#include "ggml-backend.h"
#include "llama.h"

namespace {

constexpr const char * TAG = "Leap8Llama";

struct Engine {
    llama_model * model = nullptr;
    llama_context * context = nullptr;
    const llama_vocab * vocab = nullptr;
    // llama_model_params keeps this pointer; it must outlive model construction.
    ggml_backend_dev_t devices[2] = {nullptr, nullptr};
    int contextSize = 4096;
    std::string backend = "ARM CPU";
    // Version, prompt tokens, non-EOG output tokens, prompt wall ms, decode-loop wall ms.
    // A failed generation keeps valid=false: never return stale statistics from a prior task.
    bool usageValid = false;
    int64_t inputTokens = 0;
    int64_t outputTokens = 0;
    int64_t promptMs = 0;
    int64_t decodeMs = 0;
    std::mutex mutex;

    ~Engine() {
        if (context != nullptr) llama_free(context);
        if (model != nullptr) llama_model_free(model);
    }
};

std::mutex runtimeMutex;
bool runtimeInitialized = false;
bool runtimeHasGpu = false;
std::string runtimeGpuBackend = "Vulkan";
std::mutex enginesMutex;
std::unordered_map<jlong, std::shared_ptr<Engine>> engines;
jlong nextEngineId = 1;

std::shared_ptr<Engine> findEngine(jlong handle) {
    std::lock_guard<std::mutex> guard(enginesMutex);
    const auto found = engines.find(handle);
    if (found == engines.end()) throw std::runtime_error("The local model is not loaded or has been unloaded");
    return found->second;
}

void logCallback(ggml_log_level level, const char * text, void *) {
    int priority = ANDROID_LOG_INFO;
    if (level == GGML_LOG_LEVEL_ERROR) priority = ANDROID_LOG_ERROR;
    else if (level == GGML_LOG_LEVEL_WARN) priority = ANDROID_LOG_WARN;
    else if (level == GGML_LOG_LEVEL_DEBUG) priority = ANDROID_LOG_DEBUG;
    __android_log_write(priority, TAG, text);
}

void throwJava(JNIEnv * env, const std::string & message, bool invalidInput = false) {
    if (env->ExceptionCheck()) return;
    jclass exceptionClass = env->FindClass(invalidInput ? "java/lang/IllegalArgumentException" : "java/lang/IllegalStateException");
    if (exceptionClass != nullptr) {
        env->ThrowNew(exceptionClass, message.c_str());
        env->DeleteLocalRef(exceptionClass);
    }
}

// Kotlin encodes standard UTF-8 explicitly. GetStringUTFChars supplies modified UTF-8,
// which corrupts supplementary characters (including emoji) before tokenization.
std::string fromJavaBytes(JNIEnv * env, jbyteArray value) {
    if (value == nullptr) return {};
    const jsize size = env->GetArrayLength(value);
    if (env->ExceptionCheck()) throw std::runtime_error("Unable to read native input");
    std::string result(static_cast<size_t>(size), '\0');
    if (size > 0) {
        env->GetByteArrayRegion(value, 0, size, reinterpret_cast<jbyte *>(result.data()));
        if (env->ExceptionCheck()) throw std::runtime_error("Unable to read native input");
    }
    return result;
}

jbyteArray toByteArray(JNIEnv * env, const std::string & value) {
    auto result = env->NewByteArray(static_cast<jsize>(value.size()));
    if (result != nullptr && !value.empty()) {
        env->SetByteArrayRegion(
            result,
            0,
            static_cast<jsize>(value.size()),
            reinterpret_cast<const jbyte *>(value.data())
        );
    }
    return result;
}

std::vector<llama_token> tokenize(const llama_vocab * vocab, const std::string & text) {
    if (text.size() > static_cast<size_t>(std::numeric_limits<int32_t>::max())) {
        throw std::runtime_error("The prompt is too large to tokenize");
    }
    int count = llama_tokenize(
        vocab, text.data(), static_cast<int32_t>(text.size()), nullptr, 0, true, true
    );
    if (count == 0) return {};
    if (count > 0 || count == std::numeric_limits<int32_t>::min()) {
        throw std::runtime_error("Invalid tokenizer output size");
    }
    std::vector<llama_token> tokens(static_cast<size_t>(-count));
    count = llama_tokenize(
        vocab,
        text.data(),
        static_cast<int32_t>(text.size()),
        tokens.data(),
        static_cast<int32_t>(tokens.size()),
        true,
        true
    );
    if (count < 0) throw std::runtime_error("Tokenizer output buffer was too small");
    tokens.resize(static_cast<size_t>(count));
    return tokens;
}

ggml_backend_dev_t preferredGpuDevice() {
    for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
        ggml_backend_dev_t device = ggml_backend_dev_get(i);
        const auto type = ggml_backend_dev_type(device);
        const char * backendName = ggml_backend_reg_name(ggml_backend_dev_backend_reg(device));
        if ((type == GGML_BACKEND_DEVICE_TYPE_GPU || type == GGML_BACKEND_DEVICE_TYPE_IGPU) &&
            backendName != nullptr && runtimeGpuBackend == backendName) {
            return device;
        }
    }
    return nullptr;
}

bool hasSuffix(const std::string & value, const char * suffix) {
    const size_t suffixLength = std::strlen(suffix);
    return value.size() >= suffixLength &&
        value.compare(value.size() - suffixLength, suffixLength, suffix) == 0;
}

/**
 * Mirrors llama.cpp's scored CPU plugin selection without touching any GPU library. This is the
 * key safety property of the dedicated CPU process: a broken Vulkan loader can never be dlopen'd
 * while handling a CPU fallback.
 */
void loadBestCpuBackend(const std::string & directory) {
    DIR * dir = opendir(directory.c_str());
    if (dir == nullptr) throw std::runtime_error("Unable to enumerate native CPU backends");
    using ScoreFunction = int (*)();
    int bestScore = 0;
    std::string bestPath;
    while (dirent * entry = readdir(dir)) {
        const std::string name(entry->d_name);
        if (name.rfind("libggml-cpu-", 0) != 0 || !hasSuffix(name, ".so")) continue;
        const std::string path = directory + "/" + name;
        void * handle = dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL);
        if (handle == nullptr) continue;
        auto score = reinterpret_cast<ScoreFunction>(dlsym(handle, "ggml_backend_score"));
        const int candidateScore = score == nullptr ? 0 : score();
        dlclose(handle);
        if (candidateScore > bestScore) {
            bestScore = candidateScore;
            bestPath = path;
        }
    }
    closedir(dir);
    if (bestPath.empty()) {
        const std::string genericPath = directory + "/libggml-cpu.so";
        if (ggml_backend_load(genericPath.c_str()) == nullptr) {
            throw std::runtime_error("No compatible ARM CPU backend was found");
        }
    } else if (ggml_backend_load(bestPath.c_str()) == nullptr) {
        throw std::runtime_error("The selected ARM CPU backend could not be loaded");
    }
    if (ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU) == nullptr) {
        throw std::runtime_error("The ARM CPU backend exposed no usable device");
    }
}

void loadGpuBackend(const std::string & directory) {
    const std::string path = directory + (runtimeGpuBackend == "OpenCL" ? "/libggml-opencl.so" : "/libggml-vulkan.so");
    if (ggml_backend_load(path.c_str()) == nullptr || preferredGpuDevice() == nullptr) {
        throw std::runtime_error("No compatible " + runtimeGpuBackend + " GPU backend was detected");
    }
}

void reportProgress(JNIEnv * env, jobject listener, const std::string & output, int generated, int maximum) {
    if (listener == nullptr) return;
    jclass listenerClass = env->GetObjectClass(listener);
    if (listenerClass == nullptr) throw std::runtime_error("Unable to inspect progress callback");
    jmethodID method = env->GetMethodID(listenerClass, "onProgress", "([BII)V");
    if (method != nullptr) {
        jbyteArray partial = toByteArray(env, output);
        if (partial != nullptr) {
            env->CallVoidMethod(listener, method, partial, static_cast<jint>(generated), static_cast<jint>(maximum));
            env->DeleteLocalRef(partial);
        }
    }
    env->DeleteLocalRef(listenerClass);
    // Preserve cancellation/errors raised by the caller; continuing JNI work with a
    // pending exception is invalid and swallowing it can keep a cancelled job alive.
    if (env->ExceptionCheck()) throw std::runtime_error("Progress callback failed");
}

std::string tokenPiece(const llama_vocab * vocab, llama_token token) {
    std::vector<char> buffer(128);
    int written = llama_token_to_piece(
        vocab, token, buffer.data(), static_cast<int32_t>(buffer.size()), 0, false
    );
    if (written < 0) {
        buffer.resize(static_cast<size_t>(-written));
        written = llama_token_to_piece(
            vocab, token, buffer.data(), static_cast<int32_t>(buffer.size()), 0, false
        );
    }
    if (written <= 0) return {};
    return std::string(buffer.data(), static_cast<size_t>(written));
}

std::string formatPrompt(
    llama_model * model,
    const std::string & systemPrompt,
    const std::string & userPrompt
) {
    llama_chat_message messages[2] = {
        {"system", systemPrompt.c_str()},
        {"user", userPrompt.c_str()}
    };
    const char * chatTemplate = llama_model_chat_template(model, nullptr);
    if (chatTemplate != nullptr) {
        std::vector<char> buffer((systemPrompt.size() + userPrompt.size()) * 2 + 2048);
        int written = llama_chat_apply_template(
            chatTemplate, messages, 2, true, buffer.data(), static_cast<int32_t>(buffer.size())
        );
        if (written > static_cast<int>(buffer.size())) {
            buffer.resize(static_cast<size_t>(written + 1));
            written = llama_chat_apply_template(
                chatTemplate, messages, 2, true, buffer.data(), static_cast<int32_t>(buffer.size())
            );
        }
        if (written > 0) return std::string(buffer.data(), static_cast<size_t>(written));
    }
    return "<|im_start|>system\n" + systemPrompt +
        "<|im_end|>\n<|im_start|>user\n" + userPrompt +
        "<|im_end|>\n<|im_start|>assistant\n";
}

void decodePrompt(Engine & engine, std::vector<llama_token> & tokens) {
    constexpr int batchSize = 512;
    for (size_t offset = 0; offset < tokens.size(); offset += batchSize) {
        int count = static_cast<int>(std::min(tokens.size() - offset, static_cast<size_t>(batchSize)));
        llama_batch batch = llama_batch_get_one(tokens.data() + offset, count);
        int result = llama_decode(engine.context, batch);
        if (result != 0) throw std::runtime_error("Prompt decoding failed: " + std::to_string(result));
    }
}

std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> makeSampler(float temperature) {
    if (temperature <= 0.0f) {
        return {llama_sampler_init_greedy(), llama_sampler_free};
    }
    auto params = llama_sampler_chain_default_params();
    params.no_perf = true;
    auto * chain = llama_sampler_chain_init(params);
    llama_sampler_chain_add(chain, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(chain, llama_sampler_init_top_p(0.90f, 1));
    llama_sampler_chain_add(chain, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(chain, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    return {chain, llama_sampler_free};
}

} // namespace

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_leap8_llama_LlamaNative_nativeInit(
    JNIEnv * env,
    jobject,
    jbyteArray nativeLibDir,
    jboolean enableGpu,
    jboolean preferOpenCl
) {
    try {
        std::lock_guard<std::mutex> guard(runtimeMutex);
        if (!runtimeInitialized) {
            llama_log_set(logCallback, nullptr);
            const std::string directory = fromJavaBytes(env, nativeLibDir);
            if (directory.empty()) throw std::runtime_error("Native library directory is unavailable");
            // A recoverable Vulkan failure may leave the CPU backend registered.
            if (ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU) == nullptr) {
                loadBestCpuBackend(directory);
            }
            runtimeGpuBackend = preferOpenCl == JNI_TRUE ? "OpenCL" : "Vulkan";
            if (enableGpu == JNI_TRUE) loadGpuBackend(directory);
            llama_backend_init();
            runtimeHasGpu = enableGpu == JNI_TRUE;
            runtimeInitialized = true;
        } else if (enableGpu == JNI_TRUE && !runtimeHasGpu) {
            throw std::runtime_error("GPU initialization requires the dedicated GPU worker process");
        }
        std::string info = runtimeHasGpu ? "MODE:" + runtimeGpuBackend + "_GPU_CPU\n" : "MODE:STRICT_CPU_ONLY\n";
        info += llama_print_system_info();
        for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
            ggml_backend_dev_t device = ggml_backend_dev_get(i);
            const char * name = ggml_backend_dev_name(device);
            const auto type = ggml_backend_dev_type(device);
            info += "\nDEVICE:" + std::to_string(static_cast<int>(type)) + ":" + (name == nullptr ? "unknown" : name);
        }
        return toByteArray(env, info);
    } catch (const std::exception & error) {
        throwJava(env, error.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_leap8_llama_LlamaNative_nativeLoadModel(
    JNIEnv * env,
    jobject,
    jbyteArray modelPath,
    jint contextSize,
    jint requestedThreads,
    jboolean preferGpu
) {
    try {
        {
            std::lock_guard<std::mutex> guard(runtimeMutex);
            if (!runtimeInitialized) throw std::runtime_error("llama.cpp runtime is not initialized");
            if (preferGpu == JNI_TRUE && !runtimeHasGpu) {
                throw std::runtime_error("GPU initialization requires the dedicated GPU worker process");
            }
        }
        auto engine = std::make_shared<Engine>();
        llama_model_params modelParams = llama_model_default_params();
        ggml_backend_dev_t gpuDevice = preferGpu == JNI_TRUE ? preferredGpuDevice() : nullptr;
        if (preferGpu == JNI_TRUE && gpuDevice == nullptr) {
            throw std::runtime_error("GPU was requested but no matching GPU device is active");
        }
        const bool useGpu = gpuDevice != nullptr;
        const char * gpuName = useGpu ? ggml_backend_dev_name(gpuDevice) : nullptr;
        engine->devices[0] = gpuDevice;
        // Full offload can make Android Vulkan drivers duplicate several GiB and terminate the
        // app. A bounded mixed offload keeps Adreno acceleration while leaving recovery headroom.
        modelParams.n_gpu_layers = useGpu ? 24 : 0;
        // nullptr means ALL registered devices in llama.cpp, even with n_gpu_layers=0.
        // An explicit empty list is required for a CPU context that never initializes Vulkan.
        modelParams.devices = engine->devices;
        modelParams.split_mode = LLAMA_SPLIT_MODE_NONE;
        const std::string path = fromJavaBytes(env, modelPath);
        engine->model = llama_model_load_from_file(path.c_str(), modelParams);
        if (engine->model == nullptr) throw std::runtime_error("Unable to load the GGUF model");
        if (llama_model_has_encoder(engine->model) || !llama_model_has_decoder(engine->model)) {
            throw std::runtime_error("Only decoder-only text GGUF models are supported");
        }
        const int offloadedLayers = useGpu
            ? std::min(modelParams.n_gpu_layers, llama_model_n_layer(engine->model) + 1) : 0;
        engine->backend = useGpu
            ? (std::string(gpuName == nullptr ? "Android GPU" : gpuName) + " · " + runtimeGpuBackend + " GPU+CPU · " +
               "offload target " + std::to_string(offloadedLayers) + " layers")
            : "ARM CPU · strict CPU-only";

        engine->contextSize = std::clamp(static_cast<int>(contextSize), 1024, 8192);
        const int hardwareThreads = static_cast<int>(std::max(2u, std::thread::hardware_concurrency()));
        const int threads = std::clamp(static_cast<int>(requestedThreads), 2, std::min(8, hardwareThreads));
        llama_context_params contextParams = llama_context_default_params();
        contextParams.n_ctx = static_cast<uint32_t>(engine->contextSize);
        contextParams.n_batch = 512;
        contextParams.n_ubatch = useGpu ? 128 : 512;
        // llama_batch_get_one requests only the last token's logits. Reserving 512
        // outputs needlessly inflates the Vulkan compute buffers for large vocabularies.
        contextParams.n_outputs_max = 1;
        contextParams.n_threads = threads;
        contextParams.n_threads_batch = threads;
        contextParams.offload_kqv = useGpu;
        contextParams.op_offload = useGpu;
        engine->context = llama_init_from_model(engine->model, contextParams);
        if (engine->context == nullptr) throw std::runtime_error("Unable to allocate the model context");
        engine->contextSize = static_cast<int>(llama_n_ctx(engine->context));
        engine->vocab = llama_model_get_vocab(engine->model);
        if (engine->vocab == nullptr) throw std::runtime_error("The GGUF model has no tokenizer vocabulary");
        std::lock_guard<std::mutex> guard(enginesMutex);
        if (nextEngineId == std::numeric_limits<jlong>::max()) {
            throw std::runtime_error("Native model handle limit reached");
        }
        const jlong id = nextEngineId++;
        engines.emplace(id, std::move(engine));
        return id;
    } catch (const std::exception & error) {
        throwJava(env, error.what());
        return 0;
    }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_leap8_llama_LlamaNative_nativeGenerate(
    JNIEnv * env,
    jobject,
    jlong handle,
    jbyteArray systemPrompt,
    jbyteArray userPrompt,
    jint maxTokens,
    jfloat temperature,
    jobject listener
) {
    try {
        auto engine = findEngine(handle);
        std::lock_guard<std::mutex> guard(engine->mutex);
        engine->usageValid = false;
        if (!std::isfinite(temperature) || temperature < 0.0f) {
            throw std::invalid_argument("Temperature must be a finite non-negative number");
        }
        llama_memory_clear(llama_get_memory(engine->context), false);
        const std::string formatted = formatPrompt(
            engine->model, fromJavaBytes(env, systemPrompt), fromJavaBytes(env, userPrompt)
        );
        auto tokens = tokenize(engine->vocab, formatted);
        const int outputLimit = std::clamp(static_cast<int>(maxTokens), 32, 1536);
        if (tokens.empty()) throw std::invalid_argument("The prompt produced no tokens");
        if (tokens.size() + static_cast<size_t>(outputLimit) > static_cast<size_t>(engine->contextSize)) {
            throw std::invalid_argument("Input is too long for the " + std::to_string(engine->contextSize) + "-token offline context");
        }
        const auto promptStarted = std::chrono::steady_clock::now();
        decodePrompt(*engine, tokens);
        llama_synchronize(engine->context);
        const auto promptFinished = std::chrono::steady_clock::now();
        auto sampler = makeSampler(temperature);
        if (!sampler) throw std::runtime_error("Unable to create the token sampler");

        std::string output;
        output.reserve(static_cast<size_t>(outputLimit) * 4);
        int generatedTokens = 0;
        const auto decodeStarted = std::chrono::steady_clock::now();
        for (int i = 0; i < outputLimit; ++i) {
            llama_token token = llama_sampler_sample(sampler.get(), engine->context, -1);
            if (llama_vocab_is_eog(engine->vocab, token)) break;
            output += tokenPiece(engine->vocab, token);
            generatedTokens = i + 1;
            llama_batch next = llama_batch_get_one(&token, 1);
            int result = llama_decode(engine->context, next);
            if (result != 0) throw std::runtime_error("Token decoding failed: " + std::to_string(result));
            // The UI only needs token counts. Avoid repeatedly copying the entire growing output
            // across JNI; the complete text is sent once at the end.
            if ((i + 1) % 16 == 0) reportProgress(env, listener, "", i + 1, outputLimit);
        }
        reportProgress(env, listener, output, generatedTokens, outputLimit);
        llama_synchronize(engine->context);
        const auto decodeFinished = std::chrono::steady_clock::now();
        engine->inputTokens = static_cast<int64_t>(tokens.size());
        engine->outputTokens = generatedTokens;
        engine->promptMs = std::chrono::duration_cast<std::chrono::milliseconds>(promptFinished - promptStarted).count();
        engine->decodeMs = std::chrono::duration_cast<std::chrono::milliseconds>(decodeFinished - decodeStarted).count();
        engine->usageValid = true;
        return toByteArray(env, output);
    } catch (const std::exception & error) {
        throwJava(env, error.what(), dynamic_cast<const std::invalid_argument *>(&error) != nullptr);
        return nullptr;
    }
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_leap8_llama_LlamaNative_nativeLastUsage(JNIEnv * env, jobject, jlong handle) {
    try {
        auto engine = findEngine(handle);
        std::lock_guard<std::mutex> guard(engine->mutex);
        if (!engine->usageValid) return nullptr;
        const jlong values[] = {1, engine->inputTokens, engine->outputTokens, engine->promptMs, engine->decodeMs};
        auto result = env->NewLongArray(5);
        if (result) env->SetLongArrayRegion(result, 0, 5, values);
        return result;
    } catch (const std::exception & error) {
        throwJava(env, error.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_leap8_llama_LlamaNative_nativeBackend(JNIEnv * env, jobject, jlong handle) {
    try {
        auto engine = findEngine(handle);
        return toByteArray(env, engine->backend);
    } catch (const std::exception & error) {
        throwJava(env, error.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_leap8_llama_LlamaNative_nativeUnloadModel(JNIEnv * env, jobject, jlong handle) {
    try {
        std::shared_ptr<Engine> removed;
        {
            std::lock_guard<std::mutex> guard(enginesMutex);
            const auto found = engines.find(handle);
            if (found == engines.end()) return;
            removed = std::move(found->second);
            engines.erase(found);
        }
        // In-flight generation retains its own reference. Never free its context, and
        // never run a driver destructor while holding the global handles mutex.
    } catch (const std::exception & error) {
        throwJava(env, error.what());
    }
}
