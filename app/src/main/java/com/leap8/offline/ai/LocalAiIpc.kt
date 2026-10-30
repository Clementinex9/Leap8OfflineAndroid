package com.leap8.offline.ai

internal object LocalAiIpc {
    const val GENERATE = 1
    const val PROGRESS = 2
    const val RESULT = 3
    const val ERROR = 4
    const val CANCEL_AND_EXIT = 5

    const val REQUEST_ID = "request_id"
    const val BACKEND = "backend"
    const val MODEL_PATH = "model_path"
    const val SYSTEM_PROMPT = "system_prompt"
    const val USER_PROMPT = "user_prompt"
    const val MAX_TOKENS = "max_tokens"
    const val TEMPERATURE = "temperature"
    const val GENERATED_TOKENS = "generated_tokens"
    const val INPUT_TOKENS = "input_tokens"
    const val OUTPUT_TOKENS = "output_tokens"
    const val DECODE_MS = "decode_ms"
    const val PROMPT_MS = "prompt_ms"
    const val TOKEN_SOURCE = "token_source"
    const val FRACTION = "fraction"
    const val STAGE = "stage"
    const val TEXT = "text"
    const val ENGINE = "engine"
    const val ELAPSED_MS = "elapsed_ms"
    const val PROCESSOR = "processor"
    const val MESSAGE = "message"
    const val DISABLE_BACKEND = "disable_backend"
    const val MEMORY_REJECTED = "memory_rejected"
    const val MAX_PROMPT_CHARS = 48_000
    const val MAX_RESULT_CHARS = 96_000
}

/** Shared versioned payload returned by the independent b10333 and b11982 bridges. */
internal data class LocalNativeUsage(val inputTokens: Int, val outputTokens: Int, val promptMs: Long, val decodeMs: Long)

internal fun parseLocalNativeUsage(values: LongArray?): LocalNativeUsage? {
    if (values == null || values.size != 5 || values[0] != 1L || values.drop(1).any { it < 0 } ||
        values[1] > Int.MAX_VALUE || values[2] > Int.MAX_VALUE) return null
    return LocalNativeUsage(values[1].toInt(), values[2].toInt(), values[3], values[4])
}

internal class LocalAiWorkerException(
    message: String,
    val processor: com.leap8.offline.domain.AiProcessor,
    val workerCrashed: Boolean,
    val disableBackend: Boolean = true,
    val retryBinding: Boolean = false,
    val memoryRejected: Boolean = false
) : IllegalStateException(message)

internal class LocalAiMemoryException(message: String) : IllegalStateException(message)

internal fun validateLocalAiRequest(system: String, user: String, maxTokens: Int, temperature: Float) {
    require(system.length.toLong() + user.length <= LocalAiIpc.MAX_PROMPT_CHARS) {
        "输入过长，请分段使用本地 AI"
    }
    require(maxTokens in 32..1536) { "本地 AI 输出长度应在 32 至 1536 tokens 之间" }
    require(temperature.isFinite() && temperature in 0f..2f) { "本地 AI 采样参数无效" }
}
