package com.leap8.offline.speech

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import com.leap8.offline.core.Leap8CoreNative
import java.util.Locale

data class PronunciationResult(
    val transcript: String,
    val accuracy: Int,
    val matchedWords: List<String>,
    val missingWords: List<String>,
    val feedback: String
)

class OfflineSpeechEngine(private val context: Context) {
    val isAvailable: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && runCatching {
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        }.getOrDefault(false)

    private var recognizer: SpeechRecognizer? = null
    private var generation = 0L

    fun listen(
        reference: String,
        onState: (String) -> Unit,
        onResult: (PronunciationResult) -> Unit,
        onError: (String) -> Unit
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !isAvailable) {
            onError("本机没有已安装的离线英语语音包；仍可使用录音回放练习。")
            return
        }
        runCatching { listenOnDevice(reference, onState, onResult, onError) }
            .onFailure {
                destroy()
                onError(it.message ?: "离线语音识别器启动失败。")
            }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun listenOnDevice(
        reference: String,
        onState: (String) -> Unit,
        onResult: (PronunciationResult) -> Unit,
        onError: (String) -> Unit
    ) {
        destroy()
        val request = generation
        val current = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        recognizer = current
        current.apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { if (request == generation) onState("请开始朗读") }
                override fun onBeginningOfSpeech() { if (request == generation) onState("正在聆听…") }
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() { if (request == generation) onState("正在进行本地比对…") }
                override fun onError(error: Int) {
                    if (request != generation) return
                    destroy()
                    onError(errorMessage(error))
                }
                override fun onResults(results: Bundle?) {
                    if (request != generation) return
                    val transcript = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull().orEmpty()
                    destroy()
                    onResult(PronunciationAnalyzer.compare(reference, transcript))
                }
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            })
        }
    }

    fun destroy() {
        generation++
        val current = recognizer
        recognizer = null
        current?.let {
            runCatching { current.cancel() }
            runCatching { current.destroy() }
        }
    }

    private fun errorMessage(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH -> "没有识别到完整句子，请放慢速度并重试。"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没有检测到语音。"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "需要麦克风权限才能练习口语。"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "离线识别器正忙，请稍后重试。"
        else -> "离线识别未完成（错误 $code）。"
    }
}

object PronunciationAnalyzer {
    fun compare(reference: String, transcript: String): PronunciationResult {
        val result = Leap8CoreNative.comparePronunciation(reference, transcript)
        return PronunciationResult(
            transcript = transcript,
            accuracy = result.accuracyPercent,
            matchedWords = result.matchedWords,
            missingWords = result.missingWords,
            feedback = result.feedback
        )
    }
}
