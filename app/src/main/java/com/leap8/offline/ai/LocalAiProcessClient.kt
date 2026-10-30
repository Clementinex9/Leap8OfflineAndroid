package com.leap8.offline.ai

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import com.leap8.offline.domain.AiGenerationProgress
import com.leap8.offline.domain.AiProcessor
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class LocalAiProcessClient(context: Context) {
    private val appContext = context.applicationContext
    private val replyThread = HandlerThread("Leap8-AI-IPC").apply { start() }

    fun generate(
        processor: AiProcessor,
        model: File?,
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int,
        temperature: Float,
        onProgress: (AiGenerationProgress) -> Unit
    ): AiTextGeneration {
        try {
            return generateAttempt(processor, model, systemPrompt, userPrompt, maxTokens, temperature, onProgress)
        } catch (error: LocalAiWorkerException) {
            if (!error.retryBinding) throw error
            // Android can briefly retain a dead ServiceRecord after the previous worker exits.
            // Retry only before any GENERATE was sent, so a task can never execute twice.
            Log.i("Leap8AI", "backend=$processor stale binding; reconnecting once before request dispatch")
            Thread.sleep(250)
            return generateAttempt(processor, model, systemPrompt, userPrompt, maxTokens, temperature, onProgress)
        }
    }

    private fun generateAttempt(
        processor: AiProcessor,
        model: File?,
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int,
        temperature: Float,
        onProgress: (AiGenerationProgress) -> Unit
    ): AiTextGeneration {
        check(Looper.myLooper() != Looper.getMainLooper()) { "本地 AI 不能阻塞界面线程" }
        require(processor in setOf(AiProcessor.NPU, AiProcessor.GPU, AiProcessor.CPU))
        validateLocalAiRequest(systemPrompt, userPrompt, maxTokens, temperature)

        val requestId = UUID.randomUUID().toString()
        val connected = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val completed = AtomicBoolean(false)
        val closing = AtomicBoolean(false)
        val requestSent = AtomicBoolean(false)
        val remote = AtomicReference<Messenger?>(null)
        val generation = AtomicReference<AiTextGeneration?>(null)
        val failure = AtomicReference<Throwable?>(null)
        val workerDied = CountDownLatch(1)
        val workerBinder = AtomicReference<IBinder?>(null)

        fun fail(message: String, crashed: Boolean, disableBackend: Boolean = true, retryBinding: Boolean = false, memoryRejected: Boolean = false) {
            if (completed.compareAndSet(false, true)) {
                Log.e("Leap8AI", "request=$requestId backend=$processor crashed=$crashed disable=$disableBackend error=$message")
                failure.set(LocalAiWorkerException(message, processor, crashed, disableBackend, retryBinding, memoryRejected))
                finished.countDown()
            }
        }

        val deathRecipient = IBinder.DeathRecipient {
            workerDied.countDown()
            fail("${processor.label} 推理子进程异常退出", crashed = true, retryBinding = !requestSent.get())
            connected.countDown()
        }

        val replyHandler = Handler(replyThread.looper) { message ->
            val data = message.data
            if (closing.get() || completed.get() || data.getString(LocalAiIpc.REQUEST_ID) != requestId) return@Handler true
            when (message.what) {
                LocalAiIpc.PROGRESS -> runCatching {
                    val actualProcessor = runCatching {
                        AiProcessor.valueOf(data.getString(LocalAiIpc.PROCESSOR).orEmpty())
                    }.getOrDefault(processor)
                    onProgress(
                        AiGenerationProgress(
                            running = true,
                            fraction = data.getFloat(LocalAiIpc.FRACTION).coerceIn(0f, .98f),
                            stage = data.getString(LocalAiIpc.STAGE).orEmpty().ifBlank { "本地 AI 正在生成" },
                            generatedTokens = data.getInt(LocalAiIpc.GENERATED_TOKENS),
                            maxTokens = maxTokens,
                            processor = actualProcessor,
                            outputTokens = if (data.getString(LocalAiIpc.TOKEN_SOURCE) == "LOCAL_TOKENIZER") data.getInt(LocalAiIpc.GENERATED_TOKENS) else null,
                            tokenSource = data.getString(LocalAiIpc.TOKEN_SOURCE)
                        )
                    )
                }
                LocalAiIpc.RESULT -> if (completed.compareAndSet(false, true)) {
                    val actualProcessor = runCatching {
                        AiProcessor.valueOf(data.getString(LocalAiIpc.PROCESSOR).orEmpty())
                    }.getOrDefault(processor)
                    generation.set(
                        AiTextGeneration(
                            text = data.getString(LocalAiIpc.TEXT).orEmpty(),
                            elapsedMs = data.getLong(LocalAiIpc.ELAPSED_MS),
                            engine = data.getString(LocalAiIpc.ENGINE).orEmpty(),
                            processor = actualProcessor,
                            generatedTokens = data.getInt(LocalAiIpc.GENERATED_TOKENS)
                        )
                    )
                    // Publish native metadata before releasing the blocking caller. It is not
                    // inferred from response text, and an unknown/missing field remains null.
                    runCatching {
                        onProgress(AiGenerationProgress(
                            running = true,
                            fraction = .98f,
                            stage = "本地生成已完成，正在整理结果",
                            generatedTokens = data.getInt(LocalAiIpc.GENERATED_TOKENS),
                            maxTokens = maxTokens,
                            processor = actualProcessor,
                            inputTokens = if (data.containsKey(LocalAiIpc.INPUT_TOKENS)) data.getInt(LocalAiIpc.INPUT_TOKENS) else null,
                            outputTokens = if (data.containsKey(LocalAiIpc.OUTPUT_TOKENS)) data.getInt(LocalAiIpc.OUTPUT_TOKENS) else null,
                            decodeMs = if (data.containsKey(LocalAiIpc.DECODE_MS)) data.getLong(LocalAiIpc.DECODE_MS) else null,
                            tokenSource = data.getString(LocalAiIpc.TOKEN_SOURCE),
                            promptMs = if (data.containsKey(LocalAiIpc.PROMPT_MS)) data.getLong(LocalAiIpc.PROMPT_MS) else null
                        ))
                    }
                    finished.countDown()
                }
                LocalAiIpc.ERROR -> fail(
                    data.getString(LocalAiIpc.MESSAGE).orEmpty().ifBlank { "本地 AI 子进程返回错误" },
                    crashed = false,
                    disableBackend = data.getBoolean(LocalAiIpc.DISABLE_BACKEND, true),
                    memoryRejected = data.getBoolean(LocalAiIpc.MEMORY_REJECTED)
                )
            }
            true
        }
        val replyMessenger = Messenger(replyHandler)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                if (closing.get() || completed.get()) return
                workerBinder.set(service)
                try {
                    service.linkToDeath(deathRecipient, 0)
                } catch (_: android.os.RemoteException) {
                    deathRecipient.binderDied()
                    return
                }
                remote.set(Messenger(service))
                if (closing.get()) requestWorkerExit(remote.get())
                connected.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                workerDied.countDown()
                fail("${processor.label} 推理子进程异常退出", crashed = true, retryBinding = !requestSent.get())
                connected.countDown()
            }

            override fun onBindingDied(name: ComponentName) {
                workerDied.countDown()
                fail("${processor.label} 推理绑定失效", crashed = true, retryBinding = !requestSent.get())
                connected.countDown()
            }

            override fun onNullBinding(name: ComponentName) {
                fail("无法启动本地 AI 推理子进程", crashed = false)
                connected.countDown()
            }
        }

        val bound = runCatching {
            appContext.bindService(
                Intent(appContext, serviceClass(processor)),
                connection,
                Context.BIND_AUTO_CREATE
            )
        }.getOrElse { throw LocalAiWorkerException(it.message ?: "无法绑定本地 AI 服务", processor, false) }
        if (!bound) throw LocalAiWorkerException("无法启动本地 AI 服务", processor, false)

        try {
            if (!connected.await(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw LocalAiWorkerException("本地 AI 服务启动超时", processor, false)
            }
            failure.get()?.let { throw it }
            val target = remote.get() ?: throw LocalAiWorkerException("本地 AI 服务未连接", processor, true)
            val request = Message.obtain(null, LocalAiIpc.GENERATE).apply {
                replyTo = replyMessenger
                data = Bundle().apply {
                    putString(LocalAiIpc.REQUEST_ID, requestId)
                    putString(LocalAiIpc.BACKEND, processor.name)
                    putString(LocalAiIpc.MODEL_PATH, model?.absolutePath.orEmpty())
                    putString(LocalAiIpc.SYSTEM_PROMPT, systemPrompt)
                    putString(LocalAiIpc.USER_PROMPT, userPrompt)
                    putInt(LocalAiIpc.MAX_TOKENS, maxTokens)
                    putFloat(LocalAiIpc.TEMPERATURE, temperature)
                }
            }
            requestSent.set(true)
            runCatching { target.send(request) }.getOrElse {
                throw LocalAiWorkerException("无法向 ${processor.label} 推理子进程发送任务", processor, true)
            }
            if (!finished.await(GENERATION_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                throw LocalAiWorkerException("本地 AI 生成超时，已请求终止独立进程", processor, true)
            }
            failure.get()?.let { throw it }
            return requireNotNull(generation.get()) { "本地 AI 未返回结果" }.also {
                require(it.text.isNotBlank()) { "本地 AI 返回了空内容" }
            }
        } finally {
            // One backend attempt owns one short-lived process. This guarantees that CPU fallback
            // cannot inherit Vulkan or QAIRT native state from a previous attempt.
            closing.set(true)
            completed.set(true)
            // Remove BIND_AUTO_CREATE before killing the process, otherwise Android may
            // automatically reconnect this finished request to a replacement worker.
            runCatching { appContext.unbindService(connection) }
            requestWorkerExit(remote.get())
            if (workerBinder.get() != null) {
                try {
                    workerDied.await(WORKER_EXIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            workerBinder.getAndSet(null)?.let { binder ->
                if (binder.isBinderAlive) runCatching { binder.unlinkToDeath(deathRecipient, 0) }
            }
            replyHandler.removeCallbacksAndMessages(null)
        }
    }

    private fun requestWorkerExit(target: Messenger?) {
        if (target == null) return
        runCatching { target.send(Message.obtain(null, LocalAiIpc.CANCEL_AND_EXIT)) }
    }

    private fun serviceClass(processor: AiProcessor): Class<out LocalAiProcessService> = when (processor) {
        AiProcessor.NPU -> LocalAiNpuProcessService::class.java
        AiProcessor.GPU -> LocalAiGpuProcessService::class.java
        AiProcessor.CPU -> LocalAiProcessService::class.java
        else -> error("不支持的本地处理器：${processor.label}")
    }

    private companion object {
        const val CONNECT_TIMEOUT_SECONDS = 15L
        const val GENERATION_TIMEOUT_MINUTES = 12L
        const val WORKER_EXIT_TIMEOUT_SECONDS = 3L
    }
}
