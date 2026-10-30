package com.leap8.offline.diagnostics

import com.leap8.offline.domain.AiProcessor
import com.leap8.offline.domain.AiProvider

enum class InvocationMode(val label: String) {
    GENERAL("通用生成"), WRITING("作文评分"), REVISION("精修对比"),
    COACH_CORRECTION("教练·纠错"), COACH_REWRITE("教练·改写"),
    COACH_SPEAKING("教练·口语"), COACH_READING("教练·阅读"), COACH_VOCABULARY("教练·词汇")
}
enum class InvocationOutcome(val label: String) {
    RUNNING("调用中"), SUCCESS("生成完成"), FAILED("失败/被保护拦截"), CANCELLED("已取消"), INTERRUPTED("进程结束前未完成")
}
data class PerformanceSample(
    val elapsedMs: Long,
    val batteryTempC: Float? = null,
    val availableMemoryBytes: Long? = null,
    val javaHeapBytes: Long? = null,
    val thermalStatus: Int? = null,
    val generatedTokens: Int? = null
)
data class InvocationRecord(
    val id: String,
    val startedAtMs: Long,
    val mode: InvocationMode,
    val provider: AiProvider,
    val processor: AiProcessor? = null,
    val model: String = "",
    val samplingTemperature: Float? = null,
    val outcome: InvocationOutcome = InvocationOutcome.RUNNING,
    val elapsedMs: Long = 0,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val decodeMs: Long? = null,
    val firstProgressMs: Long? = null,
    val tokenSource: String = "未报告",
    val route: List<AiProcessor> = emptyList(),
    val samples: List<PerformanceSample> = emptyList()
)
data class DeveloperTelemetrySnapshot(
    val enabled: Boolean = true,
    val active: List<InvocationRecord> = emptyList(),
    val recent: List<InvocationRecord> = emptyList(),
    val storageWarning: Boolean = false
)

/** Preserve endpoints and bound memory; no invented interpolation for missing sensors. */
internal fun compactSamples(samples: List<PerformanceSample>, maximum: Int = 120): List<PerformanceSample> {
    if (samples.size <= maximum) return samples
    require(maximum >= 2)
    return (0 until maximum).map { samples[(it.toLong() * samples.lastIndex / (maximum - 1L)).toInt()] }
}
