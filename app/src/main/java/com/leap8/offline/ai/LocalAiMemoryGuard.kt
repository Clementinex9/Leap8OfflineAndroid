package com.leap8.offline.ai

import com.leap8.offline.domain.AiProcessor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

enum class LocalAiMemoryStage { IDLE, CHECKING, CLEANING, READY, BLOCKED }

data class LocalAiMemoryStatus(
    val stage: LocalAiMemoryStage = LocalAiMemoryStage.IDLE,
    val availableBytes: Long = 0,
    val requiredBytes: Long = 0,
    val beforeBytes: Long = 0,
    val cleanupAttempted: Boolean = false,
    val message: String = "本地模型加载前自动检查内存；不足时释放本应用可回收资源。"
)

internal data class LocalMemorySnapshot(val availableBytes: Long, val lowMemory: Boolean, val threshold: Long = 0)

/** No disk deletion, foreign process killing or promise that GC will recover a given amount. */
internal interface LocalMemoryEnvironment {
    fun snapshot(): LocalMemorySnapshot
    fun pauseReclaimableResources(): AutoCloseable
    fun requestGarbageCollection()
    fun awaitReclaim()
}

/** One cleanup budget and one speech-pause lease for the ENTIRE request, including fallback. */
internal class LocalAiMemoryGuard(private val environment: LocalMemoryEnvironment) {
    private val mutableStatus = MutableStateFlow(LocalAiMemoryStatus())
    val status = mutableStatus.asStateFlow()

    fun openSession(onStage: (String, AiProcessor) -> Unit = { _, _ -> }): Session = Session(onStage)

    inner class Session(private val onStage: (String, AiProcessor) -> Unit) : AutoCloseable {
        private var lease: AutoCloseable? = null
        private var attempted = false
        private var before = 0L
        private var closed = false

        fun ensure(modelBytes: Long, processor: AiProcessor, forceCleanup: Boolean = false) {
            check(!closed) { "内存保护会话已关闭" }
            var snapshot = environment.snapshot()
            val modelRequired = requiredAvailableMemoryBytes(modelBytes, processor)
            fun required() = maxOf(modelRequired, snapshot.threshold + 256L * 1024 * 1024)
            fun enough() = !snapshot.lowMemory && snapshot.availableBytes >= required()
            fun publish(stage: LocalAiMemoryStage, message: String) {
                mutableStatus.value = LocalAiMemoryStatus(stage, snapshot.availableBytes, required(), before, attempted, message)
                onStage(message, processor)
            }
            publish(LocalAiMemoryStage.CHECKING, "正在检查 ${processor.label} 模型加载所需内存")
            if ((!enough() || forceCleanup) && !attempted) {
                attempted = true
                before = snapshot.availableBytes
                publish(LocalAiMemoryStage.CLEANING, "内存不足，正在释放本应用语音模型与音频缓存…")
                // Keep speech paused until all backend attempts finish, including errors/cancellation.
                // acquirePause must roll back its own partial acquisition if interrupted.
                lease = environment.pauseReclaimableResources()
                environment.requestGarbageCollection()
                // Native voice release is queued on its owner thread, never on the UI thread.
                // Poll finitely; a slow release must not turn into an infinite clean/reload loop.
                for (poll in 0 until 8) {
                    environment.awaitReclaim()
                    snapshot = environment.snapshot()
                    if (enough()) break
                }
            }
            if (!enough()) {
                val message = "已尝试释放本应用可回收内存；${processor.label} 估计需要 ${gib(required())}，" +
                    "当前可用 ${gib(snapshot.availableBytes)}，仍不足，已阻止高风险加载。请关闭其他大型应用、选择较小模型，或自行切换 API。"
                publish(LocalAiMemoryStage.BLOCKED, message)
                throw LocalAiMemoryException(message)
            }
            publish(LocalAiMemoryStage.READY, if (attempted) "清理后复查通过，继续加载 ${processor.label}；语音将在本次任务结束后可重新加载。"
                else "内存检查通过，继续加载 ${processor.label}。")
        }

        override fun close() {
            if (closed) return
            closed = true
            val ownedLease = lease
            lease = null
            runCatching { ownedLease?.close() }
            if (mutableStatus.value.stage in setOf(LocalAiMemoryStage.CHECKING, LocalAiMemoryStage.CLEANING)) {
                mutableStatus.value = mutableStatus.value.copy(
                    stage = LocalAiMemoryStage.IDLE,
                    message = "本次内存检查已结束；下次任务将重新检查。"
                )
            }
        }

        fun recordWorkerRejection(message: String) {
            mutableStatus.value = mutableStatus.value.copy(
                stage = LocalAiMemoryStage.BLOCKED,
                message = "加载前内存状态已变化，独立服务已安全拒绝加载：$message"
            )
        }
    }
}

private fun gib(bytes: Long) = String.format(Locale.ROOT, "%.2f GiB", bytes / 1073741824.0)
