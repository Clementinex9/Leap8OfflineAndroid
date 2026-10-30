package com.leap8.offline.ai

import android.app.ActivityManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.system.Os
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.util.Log
import com.leap8.llama.LlamaEngine
import com.leap8.offline.domain.AiProcessor
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Executors
import java.util.function.Consumer

/**
 * Runs every proprietary/native local inference backend outside the UI process. A Vulkan, QAIRT,
 * JNI or memory failure can terminate its backend process without taking the learner's editor and progress
 * state down with it; the client then starts a fresh process for the next fallback backend.
 */
open class LocalAiProcessService : Service() {
    private lateinit var messenger: Messenger
    private val generationExecutor = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    private val llamaDelegate = lazy { LlamaEngine(applicationContext) }
    private val llama by llamaDelegate
    protected open val serviceProcessor: AiProcessor = AiProcessor.CPU

    override fun onCreate() {
        super.onCreate()
        // Binder control stays responsive while native generation runs on its own executor. This
        // lets the UI terminate a timed-out worker even when a native driver call is stuck.
        messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
            when (message.what) {
                LocalAiIpc.GENERATE -> {
                    // Looper recycles Message as soon as this callback returns. Capture the
                    // payload and reply endpoint before handing work to another thread.
                    val request = Bundle(message.data)
                    val reply = message.replyTo
                    if (running.compareAndSet(false, true)) {
                        generationExecutor.execute {
                            try { handleGenerate(request, reply) } finally { running.set(false) }
                        }
                    } else {
                        send(message.replyTo, LocalAiIpc.ERROR, message.data.getString(LocalAiIpc.REQUEST_ID).orEmpty()) {
                            putString(LocalAiIpc.MESSAGE, "本地 AI 子进程已有任务正在运行")
                        }
                    }
                }
                LocalAiIpc.CANCEL_AND_EXIT -> Process.killProcess(Process.myPid())
            }
            true
        })
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        // The worker owns native handles, including destruction. Never initialize llama in
        // an NPU service merely because Android is destroying that service.
        generationExecutor.execute {
            if (llamaDelegate.isInitialized()) runCatching { llama.unload() }
        }
        generationExecutor.shutdown()
        super.onDestroy()
    }

    private fun handleGenerate(request: Bundle, reply: Messenger?) {
        val requestId = request.getString(LocalAiIpc.REQUEST_ID).orEmpty()
        if (reply == null) return
        val processor = runCatching {
            AiProcessor.valueOf(request.getString(LocalAiIpc.BACKEND).orEmpty())
        }.getOrDefault(AiProcessor.CPU)
        val maxTokens = request.getInt(LocalAiIpc.MAX_TOKENS).coerceIn(32, 1536)
        val started = System.nanoTime()
        Log.i("Leap8AI", "request=$requestId backend=$processor pid=${Process.myPid()} start")

        try {
            validateLocalAiRequest(
                request.getString(LocalAiIpc.SYSTEM_PROMPT).orEmpty(),
                request.getString(LocalAiIpc.USER_PROMPT).orEmpty(),
                request.getInt(LocalAiIpc.MAX_TOKENS),
                request.getFloat(LocalAiIpc.TEMPERATURE)
            )
            require(processor == serviceProcessor) {
                "${serviceProcessor.label} 独立服务拒绝了 ${processor.label} 请求"
            }
            val result = when (processor) {
                AiProcessor.NPU -> generateOnNpu(requestId, reply, request, maxTokens, started)
                AiProcessor.GPU, AiProcessor.CPU -> generateWithLlama(
                    requestId, reply, request, processor, maxTokens, started
                )
                else -> error("不支持的本地处理器")
            }
            require(result.text.length <= LocalAiIpc.MAX_RESULT_CHARS) { "本地 AI 输出过长，请缩短输入后重试" }
            Log.i("Leap8AI", "request=$requestId backend=${result.processor} tokens=${result.generatedTokens} elapsedMs=${result.elapsedMs} success")
            send(reply, LocalAiIpc.RESULT, requestId) {
                putString(LocalAiIpc.TEXT, result.text)
                putString(LocalAiIpc.ENGINE, result.engine)
                putString(LocalAiIpc.PROCESSOR, result.processor.name)
                putLong(LocalAiIpc.ELAPSED_MS, result.elapsedMs)
                putInt(LocalAiIpc.GENERATED_TOKENS, result.generatedTokens)
                result.inputTokens?.let { putInt(LocalAiIpc.INPUT_TOKENS, it) }
                result.outputTokens?.let { putInt(LocalAiIpc.OUTPUT_TOKENS, it) }
                result.decodeMs?.let { putLong(LocalAiIpc.DECODE_MS, it) }
                result.promptMs?.let { putLong(LocalAiIpc.PROMPT_MS, it) }
                putString(LocalAiIpc.TOKEN_SOURCE, result.tokenSource)
            }
        } catch (error: Throwable) {
            var reason = error
            while (reason is InvocationTargetException && reason.targetException != null) reason = reason.targetException
            Log.e("Leap8AI", "request=$requestId backend=$processor failure=${safeErrorMessage(error)}")
            send(reply, LocalAiIpc.ERROR, requestId) {
                putString(LocalAiIpc.MESSAGE, safeErrorMessage(error))
                putBoolean(LocalAiIpc.DISABLE_BACKEND, reason !is LocalAiMemoryException && reason !is IllegalArgumentException)
                putBoolean(LocalAiIpc.MEMORY_REJECTED, reason is LocalAiMemoryException)
            }
        }
    }

    private fun generateWithLlama(
        requestId: String,
        reply: Messenger,
        request: Bundle,
        requestedProcessor: AiProcessor,
        maxTokens: Int,
        started: Long
    ): ServiceGeneration {
        val model = validatedModel(request.getString(LocalAiIpc.MODEL_PATH).orEmpty())
        ensureMemory(model, requestedProcessor)
        if (requestedProcessor == AiProcessor.GPU && Build.VERSION.SDK_INT >= 31 &&
            Build.SOC_MODEL.uppercase().startsWith("SM8750")) {
            val kernelCache = File(codeCacheDir, "opencl-kernels").apply { mkdirs() }
            Os.setenv("GGML_OPENCL_KERNEL_CACHE_DIR", kernelCache.absolutePath, true)
        }
        var generatedTokens = 0
        var actualProcessor = if (requestedProcessor == AiProcessor.GPU) AiProcessor.GPU else AiProcessor.CPU
        val generation = llama.generate(
            model = model,
            systemPrompt = request.getString(LocalAiIpc.SYSTEM_PROMPT).orEmpty(),
            userPrompt = request.getString(LocalAiIpc.USER_PROMPT).orEmpty(),
            maxTokens = maxTokens,
            temperature = request.getFloat(LocalAiIpc.TEMPERATURE),
            preferGpu = requestedProcessor == AiProcessor.GPU,
            allowGpuFallback = false,
            onBackendSelected = { backend ->
                actualProcessor = if (backend.contains("Vulkan", true) || backend.contains("GPU", true)) {
                    AiProcessor.GPU
                } else AiProcessor.CPU
                sendProgress(
                    reply, requestId, .10f,
                    if (actualProcessor == AiProcessor.GPU) "GPU+CPU 模型已装载，正在验证首批 token" else "严格 CPU-only 模型已装载",
                    0,
                    actualProcessor
                )
            },
            onProgress = { _, generated, maximum ->
                generatedTokens = generated
                if (generated == maximum || generated % 16 == 0) {
                    sendProgress(
                        reply = reply,
                        requestId = requestId,
                        fraction = (.12f + .82f * generated / maximum.coerceAtLeast(1).toFloat()).coerceAtMost(.94f),
                        stage = "${actualProcessor.label} 独立进程正在生成",
                        generatedTokens = generated,
                        processor = actualProcessor
                    )
                }
            }
        )
        actualProcessor = if (generation.backend.contains("Vulkan", true) || generation.backend.contains("GPU", true)) {
            AiProcessor.GPU
        } else AiProcessor.CPU
        require(requestedProcessor != AiProcessor.GPU || actualProcessor == AiProcessor.GPU) {
            "GPU 请求未产生任何实际 GPU offload"
        }
        return ServiceGeneration(
            text = generation.text,
            engine = generation.backend,
            processor = actualProcessor,
            elapsedMs = (System.nanoTime() - started) / 1_000_000,
            generatedTokens = generation.outputTokens ?: generatedTokens,
            inputTokens = generation.inputTokens,
            outputTokens = generation.outputTokens,
            decodeMs = generation.decodeMs,
            promptMs = generation.promptMs,
            tokenSource = if (generation.outputTokens != null) "LOCAL_TOKENIZER" else "UNAVAILABLE"
        )
    }

    private fun generateOnNpu(
        requestId: String,
        reply: Messenger,
        request: Bundle,
        maxTokens: Int,
        started: Long
    ): ServiceGeneration {
        val hexagon = runCatching {
            Class.forName("com.leap8.qairt.HexagonModelBundle", false, classLoader)
                .getMethod("isCandidate", Context::class.java).invoke(null, applicationContext) as Boolean
        }.getOrDefault(false)
        // Check BEFORE initializing a bridge/native library or allocating model buffers.
        val model = if (hexagon) validatedModel(request.getString(LocalAiIpc.MODEL_PATH).orEmpty()) else null
        val modelBytes = model?.length() ?: runCatching {
            Class.forName("com.leap8.qairt.QairtModelBundle", false, classLoader)
                .getMethod("estimatedModelBytes", Context::class.java).invoke(null, applicationContext) as Long
        }.getOrElse { throw LocalAiMemoryException("无法估计内置 NPU 模型内存需求，已阻止加载") }
        ensureMemoryBytes(modelBytes, AiProcessor.NPU)
        val clazz = Class.forName(if (hexagon) HEXAGON_BRIDGE else QAIRT_BRIDGE)
        val instance = clazz.getField("INSTANCE").get(null)
        val available = clazz.getMethod("isAvailable", Context::class.java)
            .invoke(instance, applicationContext) as Boolean
        check(available) { "Hexagon NPU 服务尚未就绪" }
        val pieces = AtomicInteger(0)
        val consumer = Consumer<String> {
            val count = pieces.incrementAndGet()
            if (count % 8 == 0) {
                sendProgress(
                    reply, requestId,
                    (.10f + .84f * count / maxTokens.coerceAtLeast(1).toFloat()).coerceAtMost(.94f),
                    "Hexagon NPU 正在生成（文本流片段 $count，非 token）",
                    0,
                    AiProcessor.NPU
                )
            }
        }
        sendProgress(reply, requestId, .06f, "正在隔离进程中初始化 Hexagon NPU", 0, AiProcessor.NPU)
        val text = if (hexagon) {
            clazz.getMethod(
                "generate", Context::class.java, String::class.java, String::class.java,
                String::class.java, Int::class.javaPrimitiveType, Consumer::class.java
            ).invoke(
                instance, applicationContext, requireNotNull(model).absolutePath,
                request.getString(LocalAiIpc.SYSTEM_PROMPT).orEmpty(),
                request.getString(LocalAiIpc.USER_PROMPT).orEmpty(), maxTokens, consumer
            ) as String
        } else clazz.getMethod(
            "generate",
            Context::class.java,
            String::class.java,
            String::class.java,
            Int::class.javaPrimitiveType,
            Consumer::class.java
        ).invoke(
            instance,
            applicationContext,
            request.getString(LocalAiIpc.SYSTEM_PROMPT).orEmpty(),
            request.getString(LocalAiIpc.USER_PROMPT).orEmpty(),
            maxTokens,
            consumer
        ) as String
        require(text.isNotBlank()) { "NPU 返回了空内容" }
        val engine = runCatching {
            clazz.getMethod("runtimeInfo").invoke(instance) as String
        }.getOrDefault("QAIRT Genie · Hexagon HTP")
        // UTF-8 callbacks are not tokenizer tokens. Only the actual local tokenizer/generation
        // loop can report counts; legacy Genie stays unknown until a real usage API is integrated.
        val usage = if (hexagon) runCatching {
            parseLocalNativeUsage(clazz.getMethod("lastUsage").invoke(instance) as? LongArray)
        }.getOrNull() else null
        return ServiceGeneration(
            text = text,
            engine = engine,
            processor = AiProcessor.NPU,
            elapsedMs = (System.nanoTime() - started) / 1_000_000,
            generatedTokens = usage?.outputTokens ?: 0,
            inputTokens = usage?.inputTokens,
            outputTokens = usage?.outputTokens,
            decodeMs = usage?.decodeMs,
            promptMs = usage?.promptMs,
            tokenSource = if (usage != null) "LOCAL_TOKENIZER" else "UNAVAILABLE"
        )
    }

    private fun validatedModel(path: String): File {
        val modelsRoot = File(filesDir, "models").canonicalFile
        val model = File(path).canonicalFile
        require(model.parentFile == modelsRoot && model.isFile && model.canRead()) {
            "本地模型路径无效，请重新导入 GGUF"
        }
        return model
    }

    private fun ensureMemory(model: File, processor: AiProcessor) = ensureMemoryBytes(model.length(), processor)

    private fun ensureMemoryBytes(modelBytes: Long, processor: AiProcessor) {
        val info = ActivityManager.MemoryInfo()
        getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        val required = maxOf(requiredAvailableMemoryBytes(modelBytes, processor), info.threshold + 256L * 1024 * 1024)
        if (info.lowMemory || info.availMem < required) {
            throw LocalAiMemoryException("可用内存不足：${processor.label} 安全启动需要约 ${formatGiB(required)}，当前约 ${formatGiB(info.availMem)}。请关闭其他大型应用后重试。")
        }
    }

    private fun sendProgress(
        reply: Messenger,
        requestId: String,
        fraction: Float,
        stage: String,
        generatedTokens: Int,
        processor: AiProcessor
    ) = send(reply, LocalAiIpc.PROGRESS, requestId) {
        putFloat(LocalAiIpc.FRACTION, fraction)
        putString(LocalAiIpc.STAGE, stage)
        putInt(LocalAiIpc.GENERATED_TOKENS, generatedTokens)
        putString(LocalAiIpc.PROCESSOR, processor.name)
        putString(LocalAiIpc.TOKEN_SOURCE, if (processor == AiProcessor.NPU) "UNAVAILABLE" else "LOCAL_TOKENIZER")
    }

    private fun send(reply: Messenger?, what: Int, requestId: String, fill: Bundle.() -> Unit) {
        if (reply == null) return
        runCatching {
            reply.send(Message.obtain(null, what).apply {
                data = Bundle().apply {
                    putString(LocalAiIpc.REQUEST_ID, requestId)
                    fill()
                }
            })
        }
    }

    private fun safeErrorMessage(error: Throwable): String {
        var cause = error
        while (cause is InvocationTargetException && cause.targetException != null) {
            cause = cause.targetException
        }
        return (cause.message ?: cause.javaClass.simpleName)
            .replace(filesDir.absolutePath, "[应用数据]")
            .take(320)
    }

    private fun formatGiB(bytes: Long): String = "%.1f GiB".format(bytes / 1024.0 / 1024.0 / 1024.0)

    private data class ServiceGeneration(
        val text: String,
        val engine: String,
        val processor: AiProcessor,
        val elapsedMs: Long,
        val generatedTokens: Int,
        val inputTokens: Int? = null,
        val outputTokens: Int? = null,
        val decodeMs: Long? = null,
        val promptMs: Long? = null,
        val tokenSource: String = "UNAVAILABLE"
    )

    private companion object {
        const val QAIRT_BRIDGE = "com.leap8.qairt.QairtEssayBridge"
        const val HEXAGON_BRIDGE = "com.leap8.qairt.HexagonEssayBridge"
    }
}

class LocalAiGpuProcessService : LocalAiProcessService() {
    override val serviceProcessor: AiProcessor = AiProcessor.GPU
}

class LocalAiNpuProcessService : LocalAiProcessService() {
    override val serviceProcessor: AiProcessor = AiProcessor.NPU
}
