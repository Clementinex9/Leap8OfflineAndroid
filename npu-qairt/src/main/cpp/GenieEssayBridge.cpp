// Genie C API bridge. Kotlin serializes load/query/free in the NPU service process.
#include <jni.h>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <cstring>
#include "GenieCommon.h"
#include "GenieDialog.h"

namespace {
struct Engine {
    GenieDialogConfig_Handle_t config = nullptr;
    GenieDialog_Handle_t dialog = nullptr;
    ~Engine() {
        if (dialog) GenieDialog_free(dialog);
        if (config) GenieDialogConfig_free(config);
    }
};

void throwJava(JNIEnv* env, const char* message) {
    if (env->ExceptionCheck()) return;
    jclass type = env->FindClass("java/lang/RuntimeException");
    if (type) {
        env->ThrowNew(type, message);
        env->DeleteLocalRef(type);
    }
}

std::string fromBytes(JNIEnv* env, jbyteArray value) {
    if (!value) throw std::runtime_error("Null Genie input");
    const jsize size = env->GetArrayLength(value);
    std::string result(static_cast<size_t>(size), '\0');
    env->GetByteArrayRegion(value, 0, size, reinterpret_cast<jbyte*>(result.data()));
    if (env->ExceptionCheck()) throw std::runtime_error("Unable to read Genie input");
    if (result.find('\0') != std::string::npos) throw std::runtime_error("Genie input contains a null character");
    return result;
}

jbyteArray toBytes(JNIEnv* env, const char* text, size_t size) {
    auto result = env->NewByteArray(static_cast<jsize>(size));
    if (result) env->SetByteArrayRegion(result, 0, static_cast<jsize>(size), reinterpret_cast<const jbyte*>(text));
    return result;
}

struct TokenCollector {
    JavaVM* vm = nullptr;
    jobject consumer = nullptr;
    jmethodID accept = nullptr;
    std::mutex mutex;
    std::string output;
    bool failed = false;
};

// A callback may be made on a runtime thread. JNIEnv and JNI local references
// belong to one thread; use JavaVM and a global reference instead.
void collectToken(const char* token, GenieDialog_SentenceCode_t, const void* userData) noexcept {
    if (!token || !userData) return;
    auto* collector = static_cast<TokenCollector*>(const_cast<void*>(userData));
    std::lock_guard<std::mutex> guard(collector->mutex);
    if (collector->failed) return;
    JNIEnv* env = nullptr;
    bool attached = false;
    try {
        const size_t length = std::strlen(token);
        // Defense against a malfunctioning runtime ignoring its generation limit.
        if (collector->output.size() + length > 1024 * 1024) {
            collector->failed = true;
            return;
        }
        collector->output.append(token, length);
        if (!collector->consumer) return;
        jint state = collector->vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
        if (state == JNI_EDETACHED) {
            if (collector->vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
                collector->failed = true;
                return;
            }
            attached = true;
        } else if (state != JNI_OK) {
            collector->failed = true;
            return;
        }
        jbyteArray piece = toBytes(env, token, length);
        if (piece && !env->ExceptionCheck()) {
            env->CallVoidMethod(collector->consumer, collector->accept, piece);
        }
        if (piece) env->DeleteLocalRef(piece);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            collector->failed = true;
        }
    } catch (...) {
        // No C++ exception may unwind through the proprietary C runtime.
        collector->failed = true;
    }
    if (attached) collector->vm->DetachCurrentThread();
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_leap8_qairt_QairtEssayBridge_nativeLoad(JNIEnv* env, jobject, jbyteArray configJson) {
    try {
        auto config = fromBytes(env, configJson);
        auto engine = std::make_unique<Engine>();
        auto status = GenieDialogConfig_createFromJson(config.c_str(), &engine->config);
        if (status != GENIE_STATUS_SUCCESS)
            throw std::runtime_error("Failed to parse Genie configuration: " + std::to_string(status));
        status = GenieDialog_create(engine->config, &engine->dialog);
        if (status != GENIE_STATUS_SUCCESS)
            throw std::runtime_error("Failed to create Genie dialog: " + std::to_string(status));
        return reinterpret_cast<jlong>(engine.release());
    } catch (const std::exception& error) {
        throwJava(env, error.what());
    } catch (...) {
        throwJava(env, "Unexpected Genie initialization failure");
    }
    return 0;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_leap8_qairt_QairtEssayBridge_nativeQuery(
    JNIEnv* env, jobject, jlong handle, jbyteArray prompt, jobject tokenConsumer
) {
    TokenCollector collector;
    try {
        auto* engine = reinterpret_cast<Engine*>(handle);
        if (!engine || !engine->dialog) throw std::runtime_error("Genie model is not loaded");
        auto input = fromBytes(env, prompt);
        // Each request already contains all system/user context. Reusing prior KV
        // state mixes essays and eventually fills the fixed compiled context.
        auto status = GenieDialog_reset(engine->dialog);
        if (status != GENIE_STATUS_SUCCESS)
            throw std::runtime_error("Failed to reset Genie dialog: " + std::to_string(status));
        if (tokenConsumer) {
            if (env->GetJavaVM(&collector.vm) != JNI_OK) throw std::runtime_error("Unable to obtain Java VM");
            jclass type = env->GetObjectClass(tokenConsumer);
            if (!type) throw std::runtime_error("Unable to access token consumer");
            collector.accept = env->GetMethodID(type, "accept", "(Ljava/lang/Object;)V");
            env->DeleteLocalRef(type);
            if (!collector.accept) throw std::runtime_error("Invalid token consumer");
            collector.consumer = env->NewGlobalRef(tokenConsumer);
            if (!collector.consumer) throw std::runtime_error("Unable to retain token consumer");
        }
        status = GenieDialog_query(engine->dialog, input.c_str(),
            GenieDialog_SentenceCode_t::GENIE_DIALOG_SENTENCE_COMPLETE, collectToken, &collector);
        if (collector.consumer) {
            env->DeleteGlobalRef(collector.consumer);
            collector.consumer = nullptr;
        }
        if (status != GENIE_STATUS_SUCCESS || collector.failed) {
            GenieDialog_reset(engine->dialog);
            throw std::runtime_error(collector.failed ? "Genie token delivery failed" :
                "Genie inference failed: " + std::to_string(status));
        }
        return toBytes(env, collector.output.data(), collector.output.size());
    } catch (const std::exception& error) {
        if (collector.consumer) env->DeleteGlobalRef(collector.consumer);
        throwJava(env, error.what());
    } catch (...) {
        if (collector.consumer) env->DeleteGlobalRef(collector.consumer);
        throwJava(env, "Unexpected Genie inference failure");
    }
    return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_leap8_qairt_QairtEssayBridge_nativeFree(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<Engine*>(handle);
}
