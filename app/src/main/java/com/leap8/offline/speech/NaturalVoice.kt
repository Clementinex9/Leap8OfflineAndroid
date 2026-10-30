package com.leap8.offline.speech

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Crash-safe client for the bundled Kokoro v1.0 voices.
 *
 * sherpa-onnx and the voice model live in [NaturalVoiceProcessService], never in the UI process.
 * A native loader, driver, or memory failure therefore degrades to Android's offline TTS instead
 * of closing the current learning screen.
 */
class NaturalVoice(
    context: Context,
    private val onStatus: (ready: Boolean, message: String) -> Unit
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean(false)
    private var remote: Messenger? = null
    private var replyMessenger: Messenger? = null
    private var replyHandler: Handler? = null
    private var connection: ServiceConnection? = null
    private var bindingGeneration = 0L
    private var memoryPaused = false
    private var bound = false
    private var neuralReady = false
    private var neuralFailed = false
    private var fallbackReady = false
    private var fallbackMessage = "系统离线语音尚未就绪"
    private var pendingSpeech: Pair<String, Boolean>? = null
    private var fallbackOnFailure: Pair<String, Boolean>? = null
    private val preferences = appContext.getSharedPreferences("natural_voice", Context.MODE_PRIVATE)
    val currentVoiceId: String
        get() = VoiceCatalog.resolve(preferences.getString("voice_id", VoiceCatalog.DEFAULT_VOICE_ID)).id
    val currentSpeed: Float
        get() = VoiceCatalog.normalizeSpeed(preferences.getFloat("speed", VoiceCatalog.DEFAULT_SPEED))
    private var fallback: OfflineTts? = null
    private var fallbackGeneration = 0L
    private val initializationTimeout = Runnable {
        if (!closed.get() && !memoryPaused && !neuralReady) isolateFailure()
    }

    private fun ensureFallback() {
        if (fallback != null || closed.get() || memoryPaused) return
        val generation = ++fallbackGeneration
        fallback = OfflineTts(appContext) { ready, message ->
            mainHandler.post {
                if (generation != fallbackGeneration || closed.get() || memoryPaused) return@post
                fallbackReady = ready
                fallbackMessage = message
                if (ready && neuralFailed) playFallbackIfReady()
                if (!closed.get() && !memoryPaused && !neuralReady) {
                    val prefix = when {
                        neuralFailed -> "神经语音不可用，已安全回退"
                        bound -> "神经语音独立进程加载中"
                        else -> "轻触喇叭即可加载 Kokoro 高品质语音"
                    }
                    onStatus(!neuralFailed || ready, "$prefix · $message")
                }
            }
        }
    }

    private fun handleReply(message: Message, generation: Long): Boolean {
        // Every bind owns its own endpoint. A reply queued before intentional memory cleanup
        // must not revive an old model or turn the next fresh binding into a failure.
        if (generation != bindingGeneration || !bound || closed.get() || memoryPaused) return true
        if (message.what == NaturalVoiceIpc.PLAYED) {
            fallbackOnFailure = null
        } else if (message.what == NaturalVoiceIpc.STATUS && !closed.get() && !neuralFailed) {
            val ready = message.data.getBoolean(NaturalVoiceIpc.READY)
            val failed = message.data.getBoolean(NaturalVoiceIpc.FAILED)
            neuralReady = ready
            neuralFailed = failed
            if (ready || failed) mainHandler.removeCallbacks(initializationTimeout)
            if (!closed.get()) {
                val detail = message.data.getString(NaturalVoiceIpc.MESSAGE).orEmpty()
                onStatus(
                    ready || fallbackReady,
                    when {
                        ready -> detail.ifBlank { "Kokoro 离线神经语音已就绪" }
                        failed -> "神经语音已隔离，使用系统回退 · $fallbackMessage"
                        else -> detail.ifBlank { "神经语音独立进程加载中" }
                    }
                )
            }
            if (failed) {
                disconnect()
                ensureFallback()
                playFallbackIfReady()
            }
        }
        return true
    }

    private val memoryOwner = SpeechMemoryOwner { paused -> setMemoryPaused(paused) }
    private val memoryRegistration: AutoCloseable

    init {
        memoryRegistration = SpeechMemoryCoordinator.register(memoryOwner)
        mainHandler.post {
            if (!closed.get() && !memoryPaused) onStatus(true, "轻触喇叭即可加载 Kokoro 高品质离线语音")
        }
    }

    private fun bindOnDemand() {
        if (bound || neuralFailed || closed.get() || memoryPaused) return
        val generation = ++bindingGeneration
        val nextHandler = Handler(Looper.getMainLooper()) { handleReply(it, generation) }
        replyHandler = nextHandler
        replyMessenger = Messenger(nextHandler)
        val nextConnection = object : ServiceConnection {
            private fun isCurrent() = connection === this && generation == bindingGeneration &&
                !closed.get() && !memoryPaused

            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                if (!isCurrent() || neuralFailed) return
                remote = Messenger(service)
                if (!send(NaturalVoiceIpc.INIT)) return
                pendingSpeech?.let { (text, slower) ->
                    if (sendSpeech(text, slower)) {
                        fallbackOnFailure = text to slower
                        pendingSpeech = null
                    }
                }
            }

            override fun onServiceDisconnected(name: ComponentName) { if (isCurrent()) isolateFailure() }
            override fun onBindingDied(name: ComponentName) { if (isCurrent()) isolateFailure() }
            override fun onNullBinding(name: ComponentName) { if (isCurrent()) isolateFailure() }
        }
        connection = nextConnection
        bound = runCatching {
            appContext.bindService(
                Intent(appContext, NaturalVoiceProcessService::class.java),
                nextConnection,
                Context.BIND_AUTO_CREATE
            )
        }.getOrDefault(false)
        if (!bound) isolateFailure()
        else mainHandler.postDelayed(initializationTimeout, 120_000L)
    }

    /** Apply persisted UI preferences without reloading the shared model or binding a process. */
    fun configure(voiceId: String, speed: Float) {
        preferences.edit()
            .putString("voice_id", VoiceCatalog.resolve(voiceId).id)
            .putFloat("speed", VoiceCatalog.normalizeSpeed(speed))
            .apply()
    }

    fun speak(text: String, slower: Boolean = false): Boolean {
        if (closed.get() || text.isBlank()) return false
        if (memoryPaused) {
            onStatus(false, MEMORY_PAUSE_MESSAGE)
            return false
        }
        val utterance = text.trim().take(MAX_SPEECH_CHARACTERS) to slower
        pendingSpeech = utterance
        fallbackOnFailure = null
        if (neuralFailed) {
            ensureFallback()
            playFallbackIfReady()
            return true
        }
        if (remote != null && sendSpeech(utterance.first, slower)) {
            fallbackOnFailure = utterance
            pendingSpeech = null
            fallback?.stop()
            return true
        }
        bindOnDemand()
        if (neuralFailed) {
            playFallbackIfReady()
            return true
        }
        onStatus(true, "Kokoro 高品质语音正在准备，完成后会自动朗读…")
        return true
    }

    fun stop() {
        pendingSpeech = null
        fallbackOnFailure = null
        if (!closed.get()) send(NaturalVoiceIpc.STOP)
        fallback?.stop()
    }

    fun shutdown() {
        stop()
        if (!closed.compareAndSet(false, true)) return
        memoryRegistration.close()
        mainHandler.removeCallbacksAndMessages(null)
        fallbackGeneration++
        fallback?.shutdown()
        disconnect()
    }

    private fun disconnect() {
        bindingGeneration++
        remote = null
        val previous = connection
        connection = null
        replyMessenger = null
        replyHandler?.removeCallbacksAndMessages(null)
        replyHandler = null
        if (bound && previous != null) runCatching { appContext.unbindService(previous) }
        bound = false
    }

    private fun setMemoryPaused(paused: Boolean) {
        if (closed.get() || memoryPaused == paused) return
        memoryPaused = paused
        if (paused) {
            mainHandler.removeCallbacks(initializationTimeout)
            stop()
            disconnect()
            neuralReady = false
            // Release an already-created system TTS client too; never instantiate one merely
            // to reclaim memory. Voice choice/speed live in preferences and are untouched.
            fallback?.shutdown()
            fallbackGeneration++
            fallback = null
            fallbackReady = false
        }
        runCatching {
            onStatus(!paused, if (paused) MEMORY_PAUSE_MESSAGE else "语音内存已释放；轻触喇叭可重新加载离线语音")
        }
    }

    private fun send(what: Int, fill: Bundle.() -> Unit = {}): Boolean {
        val target = remote ?: return false
        val endpoint = replyMessenger ?: return false
        return runCatching {
            target.send(Message.obtain(null, what).apply {
                replyTo = endpoint
                data = Bundle().apply(fill)
            })
        }.onFailure { isolateFailure() }.isSuccess
    }

    private fun sendSpeech(text: String, slower: Boolean): Boolean = send(NaturalVoiceIpc.SPEAK) {
        putString(NaturalVoiceIpc.TEXT, text.trim().take(MAX_SPEECH_CHARACTERS))
        putBoolean(NaturalVoiceIpc.SLOWER, slower)
        // Read shared preferences for every utterance so an already-open learning screen
        // immediately follows a voice change made in Profile.
        putString(NaturalVoiceIpc.VOICE_ID, currentVoiceId)
        putFloat(NaturalVoiceIpc.SPEED, currentSpeed)
    }

    private fun playFallbackIfReady() {
        if (!fallbackReady || closed.get() || memoryPaused) return
        val utterance = pendingSpeech ?: fallbackOnFailure ?: return
        pendingSpeech = null
        fallbackOnFailure = null
        fallback?.speak(utterance.first, utterance.second)
    }

    private fun isolateFailure() {
        if (memoryPaused) return
        mainHandler.removeCallbacks(initializationTimeout)
        disconnect()
        neuralReady = false
        neuralFailed = true
        if (!closed.get() && !memoryPaused) {
            mainHandler.post {
                if (!closed.get() && !memoryPaused) {
                    ensureFallback()
                    playFallbackIfReady()
                    onStatus(
                        fallbackReady,
                        "神经语音进程未能启动，主界面已保护 · $fallbackMessage"
                    )
                }
            }
        }
    }

    private companion object {
        const val MAX_SPEECH_CHARACTERS = 1_500
        const val MEMORY_PAUSE_MESSAGE = "本地 AI 正在释放并使用内存，离线朗读暂时暂停；任务结束后可再次点按播放"
    }
}

internal object NaturalVoiceIpc {
    const val INIT = 1
    const val SPEAK = 2
    const val STOP = 3
    const val STATUS = 4
    const val PLAYED = 5

    const val READY = "ready"
    const val FAILED = "failed"
    const val MESSAGE = "message"
    const val TEXT = "text"
    const val SLOWER = "slower"
    const val VOICE_ID = "voice_id"
    const val SPEED = "speed"
}
