package com.leap8.offline.speech

import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.LruCache
import com.k2fsa.sherpa.onnx.GeneratedAudio
import com.k2fsa.sherpa.onnx.OfflineTts as SherpaOfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.GenerationConfig
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap

/** Owns every sherpa-onnx/native object inside the isolated :leap8_speech process. */
class NaturalVoiceProcessService : Service() {
    private lateinit var workerThread: HandlerThread
    private lateinit var worker: Handler
    private lateinit var messenger: Messenger
    private var neural: SherpaOfflineTts? = null
    private var state = State.NEW
    private val clients = ConcurrentHashMap<IBinder, Messenger>()
    private val requests = VoiceRequestState()
    private val playbackLock = Any()
    private var activeTrack: AudioTrack? = null
    // At most one minute of float PCM (~5.5 MiB). Repeating a vocabulary card is instant.
    // This memory-only cache never stores learning text on disk.
    private val audioCache = object : LruCache<String, GeneratedAudio>(24_000 * 60) {
        override fun sizeOf(key: String, value: GeneratedAudio): Int = value.samples.size
    }

    override fun onCreate() {
        super.onCreate()
        workerThread = HandlerThread("Leap8-Neural-Voice").apply { start() }
        worker = Handler(workerThread.looper)
        // Receive control messages independently of native synthesis and audio playback so STOP
        // and destruction can invalidate work immediately. Never retain a pooled Message.
        messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
            if (requests.isClosed()) return@Handler true
            message.replyTo?.let { clients[it.binder] = it }
            val reply = message.replyTo
            when (message.what) {
                NaturalVoiceIpc.INIT -> worker.post {
                    if (!requests.isClosed()) initializeVoice(reply)
                }
                NaturalVoiceIpc.SPEAK -> {
                    val data = Bundle(message.data)
                    val request = requests.next() ?: return@Handler true
                    stopPlayback()
                    worker.post { if (requests.isCurrent(request)) speak(data, reply, request) }
                }
                NaturalVoiceIpc.STOP -> {
                    requests.cancel()
                    stopPlayback()
                }
            }
            true
        })
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onUnbind(intent: Intent?): Boolean {
        clients.clear()
        stopSelf()
        return false
    }

    override fun onDestroy() {
        requests.close()
        stopPlayback()
        clients.clear()
        worker.removeCallbacksAndMessages(null)
        // release() must run after any active generate()/constructor call on the same thread.
        // Joining the worker or releasing the model on the main thread can ANR or native-crash.
        worker.post {
            runCatching { neural?.release() }
            neural = null
            audioCache.evictAll()
            workerThread.quitSafely()
        }
        super.onDestroy()
    }

    private fun initializeVoice(reply: Messenger?) {
        when (state) {
            State.READY -> sendStatus(reply, true, false, READY_MESSAGE)
            State.FAILED -> sendStatus(reply, false, true, "Kokoro 离线神经语音不可用")
            State.LOADING -> sendStatus(reply, false, false, "Kokoro 高品质离线语音正在加载…")
            State.NEW -> {
                state = State.LOADING
                broadcastStatus(false, false, "Kokoro 高品质离线语音正在加载…")
                runCatching { createNeuralVoice() }
                    .onSuccess {
                        neural = it
                        state = State.READY
                        broadcastStatus(true, false, READY_MESSAGE)
                    }
                    .onFailure {
                        state = State.FAILED
                        android.util.Log.e("Leap8Voice", "Kokoro initialization failed", it)
                        broadcastStatus(false, true, "Kokoro 初始化失败；已由主界面切换系统语音")
                    }
            }
        }
    }

    private fun speak(data: Bundle, reply: Messenger?, request: Long) {
        val text = data.getString(NaturalVoiceIpc.TEXT).orEmpty().trim().take(1_500)
        if (text.isBlank()) return
        val engine = neural
        if (state != State.READY || engine == null) {
            sendStatus(reply, false, state == State.FAILED, "Kokoro 尚未就绪")
            return
        }
        runCatching {
            val voice = VoiceCatalog.resolve(data.getString(NaturalVoiceIpc.VOICE_ID))
            val speed = VoiceCatalog.effectiveSpeed(
                data.getFloat(NaturalVoiceIpc.SPEED, VoiceCatalog.DEFAULT_SPEED),
                data.getBoolean(NaturalVoiceIpc.SLOWER)
            )
            val generatedAt = android.os.SystemClock.elapsedRealtime()
            // Select both the voice embedding and a canonical eSpeak voice NAME.
            // Passing the BCP-47 alias "en-gb" to this native version aborts it.
            val cacheKey = "${voice.id}:$speed:$text"
            val cached = audioCache.get(cacheKey)
            val audio = cached ?: engine.generateWithConfig(
                text, GenerationConfig(
                    sid = voice.speakerId,
                    speed = speed,
                    silenceScale = .25f,
                    extra = mapOf("lang" to voice.phonemizerVoice)
                )
            )
            check(audio.samples.isNotEmpty() && audio.sampleRate > 0) { "神经语音未生成有效音频" }
            VoiceAudioGuard.validate(audio.samples)
            if (cached == null && text.length <= 240) audioCache.put(cacheKey, audio)
            android.util.Log.i("Leap8Voice", "Kokoro voice=${voice.id} speed=$speed rate=${audio.sampleRate} samples=${audio.samples.size} cached=${cached != null} elapsedMs=${android.os.SystemClock.elapsedRealtime() - generatedAt}")
            if (requests.isCurrent(request)) {
                play(audio.samples, audio.sampleRate, request)
                if (requests.isCurrent(request)) runCatching {
                    reply?.send(Message.obtain(null, NaturalVoiceIpc.PLAYED).apply {
                        this.data = Bundle().apply {
                            putString(NaturalVoiceIpc.VOICE_ID, voice.id)
                            putFloat(NaturalVoiceIpc.SPEED, speed)
                            putInt("sample_rate", audio.sampleRate)
                            putInt("sample_count", audio.samples.size)
                        }
                    })
                }
            }
        }.onFailure {
            if (requests.isClosed()) return@onFailure
            state = State.FAILED
            runCatching { neural?.release() }
            neural = null
            android.util.Log.e("Leap8Voice", "Kokoro generation failed", it)
            broadcastStatus(false, true, "Kokoro 生成失败；已由主界面切换系统语音")
        }
    }

    private fun createNeuralVoice(): SherpaOfflineTts {
        val dataDirectory = prepareEspeakData()
        val model = OfflineTtsKokoroModelConfig(
            model = "$MODEL_DIR/$MODEL_FILE",
            voices = "$MODEL_DIR/voices.bin",
            tokens = "$MODEL_DIR/tokens.txt",
            dataDir = dataDirectory.absolutePath,
            lexicon = "$MODEL_DIR/lexicon-us-en.txt",
            lengthScale = 1f
        )
        return SherpaOfflineTts(
            assetManager = assets,
            config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    kokoro = model,
                    numThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4),
                    debug = false,
                    provider = "cpu"
                ),
                maxNumSentences = 1,
                silenceScale = .25f
            )
        ).also { engine ->
            if (engine.sampleRate() != 24_000 || engine.numSpeakers() < 28) {
                engine.release()
                error("Kokoro 模型版本或声线不匹配")
            }
        }
    }

    private fun prepareEspeakData(): File {
        val root = File(noBackupFilesDir, MODEL_DIR).apply { mkdirs() }
        val destination = File(root, "espeak-ng-data")
        val readyMarker = File(destination, ".ready")
        val requiredFiles = listOf("en_dict", "phontab", "phonindex", "phondata", "lang/gmw/en", "lang/gmw/en-US")
        fun hasEnglishVoices(directory: File) = requiredFiles.all { File(directory, it).length() > 0L }
        fun isComplete() = readyMarker.isFile && hasEnglishVoices(destination)
        if (isComplete()) return destination

        RandomAccessFile(File(root, ".extraction.lock"), "rw").channel.use { channel ->
            channel.lock().use {
                if (!isComplete()) {
                    // The lock also protects a new speech process against an older process that
                    // was killed while extracting assets. The final directory appears in one move.
                    val partial = File(root, "espeak-ng-data.partial-${android.os.Process.myPid()}")
                    check(partial.parentFile?.canonicalFile == root.canonicalFile)
                    if (partial.exists()) check(partial.deleteRecursively())
                    copyAssetTree("$MODEL_DIR/espeak-ng-data", partial)
                    check(hasEnglishVoices(partial)) {
                        "Kokoro 语音数据复制不完整"
                    }
                    check(File(partial, ".ready").createNewFile()) { "无法标记语音数据" }
                    if (destination.exists()) {
                        check(destination.parentFile?.canonicalFile == root.canonicalFile)
                        check(destination.deleteRecursively()) { "无法移除不完整的语音数据" }
                    }
                    try {
                        Files.move(partial.toPath(), destination.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
                    } catch (_: AtomicMoveNotSupportedException) {
                        Files.move(partial.toPath(), destination.toPath())
                    }
                }
            }
        }
        check(isComplete()) { "Kokoro 语音数据校验失败" }
        return destination
    }

    private fun copyAssetTree(assetPath: String, destination: File) {
        val children = assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            destination.parentFile?.mkdirs()
            assets.open(assetPath).use { input ->
                FileOutputStream(destination, false).use { output -> input.copyTo(output, 64 * 1024) }
            }
        } else {
            destination.mkdirs()
            children.forEach { child -> copyAssetTree("$assetPath/$child", File(destination, child)) }
        }
    }

    private fun play(samples: FloatArray, sampleRate: Int, request: Long) {
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        )
        check(minBuffer > 0) { "设备不支持神经语音音频格式" }
        val track = AudioTrack(
            AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .build(),
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setSampleRate(sampleRate)
                .build(),
            minBuffer.coerceAtLeast(8 * 1024),
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        try {
            check(track.state == AudioTrack.STATE_INITIALIZED) { "无法初始化语音播放" }
            synchronized(playbackLock) {
                if (!requests.isCurrent(request)) return
                activeTrack = track
                track.play()
            }
            var offset = 0
            var lastProgressAt = android.os.SystemClock.elapsedRealtime()
            while (offset < samples.size && requests.isCurrent(request)) {
                // Non-blocking, bounded writes keep stop()/onDestroy() from waiting for a
                // whole utterance while holding playbackLock.
                val written = synchronized(playbackLock) {
                    if (!requests.isCurrent(request)) return
                    track.write(samples, offset, minOf(4_096, samples.size - offset), AudioTrack.WRITE_NON_BLOCKING)
                }
                check(written >= 0) { "语音播放失败（$written）" }
                if (written > 0) {
                    offset += written
                    lastProgressAt = android.os.SystemClock.elapsedRealtime()
                } else {
                    check(android.os.SystemClock.elapsedRealtime() - lastProgressAt < 5_000L) { "语音播放超时" }
                    Thread.sleep(10)
                }
            }
            // write() only queues samples. Allow the final buffer to play before release().
            val drainDeadline = android.os.SystemClock.elapsedRealtime() + 5_000L
            while (requests.isCurrent(request) &&
                (track.playbackHeadPosition.toLong() and 0xffffffffL) < offset &&
                android.os.SystemClock.elapsedRealtime() < drainDeadline
            ) Thread.sleep(10)
        } finally {
            synchronized(playbackLock) {
                if (activeTrack === track) activeTrack = null
                runCatching { track.stop() }
                runCatching { track.release() }
            }
        }
    }

    private fun stopPlayback() = synchronized(playbackLock) { stopPlaybackLocked() }

    private fun stopPlaybackLocked() {
        activeTrack?.let { track ->
            runCatching { track.pause() }
            runCatching { track.flush() }
        }
        // The worker owns release(); another thread may still be inside AudioTrack.write().
    }

    private fun broadcastStatus(ready: Boolean, failed: Boolean, message: String) {
        clients.values.toList().forEach { sendStatus(it, ready, failed, message) }
    }

    private fun sendStatus(target: Messenger?, ready: Boolean, failed: Boolean, message: String) {
        if (target == null) return
        val sent = runCatching {
            target.send(Message.obtain(null, NaturalVoiceIpc.STATUS).apply {
                data = Bundle().apply {
                    putBoolean(NaturalVoiceIpc.READY, ready)
                    putBoolean(NaturalVoiceIpc.FAILED, failed)
                    putString(NaturalVoiceIpc.MESSAGE, message)
                }
            })
        }.isSuccess
        if (!sent) clients.remove(target.binder)
    }

    private enum class State { NEW, LOADING, READY, FAILED }

    private companion object {
        const val MODEL_DIR = "kokoro-int8-multi-lang-v1_0"
        const val MODEL_FILE = "model.int8.onnx"
        const val READY_MESSAGE = "Kokoro v1.0 高品质语音 · 24 kHz · 英美声线 · 完全离线"
    }
}
