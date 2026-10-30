package com.leap8.llama

import android.content.Context
import android.os.Build
import java.io.File

data class LlamaGeneration(
    val text: String,
    val elapsedMs: Long,
    val modelFile: String,
    val backend: String,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val promptMs: Long? = null,
    val decodeMs: Long? = null
)

fun interface LlamaProgressListener {
    fun onProgress(partialText: ByteArray, generatedTokens: Int, maxTokens: Int)
}

object LlamaNative {
    private var initialized = false
    private var initializedForGpu = false
    private val initializationErrors = mutableMapOf<Boolean, Throwable>()
    private var systemInfo = ""

    val isArm64Supported: Boolean
        get() = Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }

    @Synchronized
    fun initialize(context: Context, enableVulkan: Boolean = true): Result<String> {
        if (initialized && (!enableVulkan || initializedForGpu)) return Result.success(systemInfo)
        // A recoverable GPU initialization failure must not poison the CPU path.
        initializationErrors[enableVulkan]?.let { return Result.failure(it) }
        if (!isArm64Supported) return Result.failure(
            UnsupportedOperationException("本地大模型当前仅支持 arm64-v8a Android 设备")
        )
        return runCatching {
            System.loadLibrary("leap8_llama_jni")
            systemInfo = nativeInit(
                context.applicationInfo.nativeLibraryDir.toByteArray(Charsets.UTF_8),
                enableVulkan,
                Build.VERSION.SDK_INT >= 31 && Build.SOC_MODEL.uppercase().startsWith("SM8750")
            ).toString(Charsets.UTF_8)
            initialized = true
            initializedForGpu = enableVulkan
            systemInfo
        }.onFailure { initializationErrors[enableVulkan] = it }
    }

    fun loadModel(
        context: Context,
        model: File,
        contextSize: Int = 4096,
        threads: Int = 4,
        preferGpu: Boolean = true
    ): Result<Long> = runCatching {
        initialize(context, enableVulkan = preferGpu).getOrThrow()
        require(model.isFile && model.canRead()) { "无法读取 GGUF 模型文件" }
        nativeLoadModel(model.absolutePath.toByteArray(Charsets.UTF_8), contextSize, threads, preferGpu).also {
            check(it != 0L) { "模型加载失败" }
        }
    }

    fun generate(
        handle: Long,
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int,
        temperature: Float,
        listener: LlamaProgressListener? = null
    ): Result<String> = runCatching {
        require(handle != 0L) { "模型尚未加载" }
        require(temperature.isFinite() && temperature >= 0f) { "采样温度必须是有限的非负数" }
        nativeGenerate(
            handle,
            systemPrompt.replace('\u0000', '\uFFFD').toByteArray(Charsets.UTF_8),
            userPrompt.replace('\u0000', '\uFFFD').toByteArray(Charsets.UTF_8),
            maxTokens,
            temperature,
            listener
        )
            .toString(Charsets.UTF_8)
            .trim()
    }

    fun unload(handle: Long) {
        if (handle != 0L) nativeUnloadModel(handle)
    }

    fun runtimeInfo(): String = systemInfo

    /** Native tokenizer counts, never a character/UTF-8-piece estimate. */
    fun lastUsage(handle: Long): LongArray? = if (handle == 0L) null else runCatching { nativeLastUsage(handle) }.getOrNull()

    fun backend(handle: Long): String = if (handle == 0L) "ARM CPU" else
        nativeBackend(handle).toString(Charsets.UTF_8).ifBlank { "ARM CPU" }

    private external fun nativeInit(nativeLibraryDir: ByteArray, enableGpu: Boolean, preferOpenCl: Boolean): ByteArray
    private external fun nativeLoadModel(modelPath: ByteArray, contextSize: Int, threads: Int, preferGpu: Boolean): Long
    private external fun nativeGenerate(
        handle: Long,
        systemPrompt: ByteArray,
        userPrompt: ByteArray,
        maxTokens: Int,
        temperature: Float,
        listener: LlamaProgressListener?
    ): ByteArray
    private external fun nativeBackend(handle: Long): ByteArray
    private external fun nativeLastUsage(handle: Long): LongArray?
    private external fun nativeUnloadModel(handle: Long)
}

class LlamaEngine(private val context: Context) {
    private val lock = Any()
    private var handle = 0L
    private var loadedPath: String? = null
    private var loadedWithGpuPreference = false
    private var gpuDisabledModelPath: String? = null

    fun isRuntimeAvailable(preferGpu: Boolean = true): Boolean =
        LlamaNative.initialize(context, enableVulkan = preferGpu).isSuccess

    fun generate(
        model: File,
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int = 768,
        temperature: Float = 0f,
        preferGpu: Boolean = true,
        allowGpuFallback: Boolean = true,
        onBackendSelected: ((backend: String) -> Unit)? = null,
        onProgress: ((partialText: String, generatedTokens: Int, maxTokens: Int) -> Unit)? = null,
        onBackendFallback: ((message: String) -> Unit)? = null
    ): LlamaGeneration = synchronized(lock) {
        val useGpuPreference = preferGpu && gpuDisabledModelPath != model.absolutePath
        try {
            ensureLoaded(model, useGpuPreference)
        } catch (loadError: Throwable) {
            if (!useGpuPreference || !allowGpuFallback) throw loadError
            gpuDisabledModelPath = model.absolutePath
            onBackendFallback?.invoke("GPU 初始化失败，已立即切换 ARM CPU")
            releaseHandle()
            ensureLoaded(model, preferGpu = false)
        }
        onBackendSelected?.invoke(LlamaNative.backend(handle))
        val started = System.nanoTime()
        val listener = onProgress?.let { callback ->
            LlamaProgressListener { partial, generated, maximum ->
                callback(partial.toString(Charsets.UTF_8), generated, maximum)
            }
        }
        val firstBackend = LlamaNative.backend(handle)
        val text = LlamaNative.generate(handle, systemPrompt, userPrompt, maxTokens, temperature, listener)
            .getOrElse { firstError ->
                if (!firstBackend.contains("GPU", ignoreCase = true) || !allowGpuFallback) throw firstError
                gpuDisabledModelPath = model.absolutePath
                onBackendFallback?.invoke("GPU 推理失败，已立即切换 ARM CPU")
                releaseHandle()
                ensureLoaded(model, preferGpu = false)
                onBackendSelected?.invoke(LlamaNative.backend(handle))
                LlamaNative.generate(handle, systemPrompt, userPrompt, maxTokens, temperature, listener).getOrThrow()
            }
        val usage = LlamaNative.lastUsage(handle)?.takeIf { values ->
            values.size == 5 && values[0] == 1L && values.drop(1).all { it >= 0 } &&
                values[1] <= Int.MAX_VALUE && values[2] <= Int.MAX_VALUE
        }
        LlamaGeneration(
            text = text,
            elapsedMs = (System.nanoTime() - started) / 1_000_000,
            modelFile = model.name,
            backend = "llama.cpp b10333 · ${LlamaNative.backend(handle)}",
            inputTokens = usage?.get(1)?.toInt(),
            outputTokens = usage?.get(2)?.toInt(),
            promptMs = usage?.get(3),
            decodeMs = usage?.get(4)
        )
    }

    fun unload() = synchronized(lock) {
        releaseHandle()
        gpuDisabledModelPath = null
    }

    private fun releaseHandle() {
        LlamaNative.unload(handle)
        handle = 0L
        loadedPath = null
        loadedWithGpuPreference = false
    }

    private fun ensureLoaded(model: File, preferGpu: Boolean) {
        if (handle != 0L && loadedPath == model.absolutePath && loadedWithGpuPreference == preferGpu) return
        if (handle != 0L) releaseHandle()
        handle = LlamaNative.loadModel(context, model, preferGpu = preferGpu).getOrThrow()
        loadedPath = model.absolutePath
        loadedWithGpuPreference = preferGpu
    }
}
