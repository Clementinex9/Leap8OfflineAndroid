package com.leap8.offline.speech

import android.content.Context
import android.os.Handler
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class OfflineTts(
    context: Context,
    private val onStatus: (ready: Boolean, message: String) -> Unit
) : TextToSpeech.OnInitListener {
    private val mainHandler = Handler(android.os.Looper.getMainLooper())
    private val closed = AtomicBoolean(false)
    private var engine: TextToSpeech? = null
    @Volatile private var ready = false

    init {
        runCatching { TextToSpeech(context.applicationContext, this) }
            .onSuccess { engine = it }
            .onFailure { report(false, "本机文字转语音服务无法启动。") }
    }

    override fun onInit(status: Int) {
        // Some engines report failure synchronously from the constructor. Defer handling until
        // the TextToSpeech instance has been assigned, and serialize setup with UI shutdown.
        mainHandler.post { configure(status) }
    }

    private fun configure(status: Int) {
        if (closed.get()) return
        ready = false
        if (status != TextToSpeech.SUCCESS) {
            report(false, "本机文字转语音引擎初始化失败。")
            return
        }
        runCatching {
            val current = requireNotNull(engine) { "文字转语音服务连接已失效" }
            val voice = chooseOfflineEnglishVoice(current.voices)
                ?: error("本机未安装离线英语语音。可在系统设置中安装语言包。")
            check(current.setVoice(voice) == TextToSpeech.SUCCESS) { "无法启用本机英语语音" }
            current.setSpeechRate(0.88f)
            current.setPitch(1.0f)
            ready = true
            report(true, "离线语音已就绪 · ${voice.locale.displayCountry.ifBlank { "English" }}")
        }.onFailure { error ->
            ready = false
            report(false, error.message ?: "本机离线英语语音初始化失败。")
        }
    }

    fun speak(text: String, slower: Boolean = false): Boolean {
        val current = engine ?: return false
        if (!ready || closed.get() || text.isBlank()) return false
        return runCatching {
            current.setSpeechRate(if (slower) 0.68f else 0.88f)
            current.speak(
                text.take(MAX_SPEECH_CHARACTERS),
                TextToSpeech.QUEUE_FLUSH,
                null,
                "leap8-${System.nanoTime()}"
            ) == TextToSpeech.SUCCESS
        }.getOrDefault(false)
    }

    fun stop() { engine?.let { runCatching { it.stop() } } }

    fun shutdown() {
        if (!closed.compareAndSet(false, true)) return
        mainHandler.removeCallbacksAndMessages(null)
        ready = false
        engine?.let { runCatching { it.stop() }; runCatching { it.shutdown() } }
        engine = null
    }

    private fun chooseOfflineEnglishVoice(voices: Set<Voice>?): Voice? = voices.orEmpty()
        .filter { !it.isNetworkConnectionRequired && it.locale.language == Locale.ENGLISH.language }
        .sortedWith(compareByDescending<Voice> { it.quality }.thenBy { it.latency })
        .firstOrNull()

    private fun report(isReady: Boolean, message: String) {
        if (closed.get()) return
        mainHandler.post { if (!closed.get()) onStatus(isReady, message) }
    }

    private companion object {
        const val MAX_SPEECH_CHARACTERS = 3_000
    }
}
