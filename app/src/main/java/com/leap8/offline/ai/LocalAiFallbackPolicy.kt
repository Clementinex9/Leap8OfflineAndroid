package com.leap8.offline.ai

import com.leap8.offline.domain.AiProcessor

internal fun localBackendChain(
    npuAvailable: Boolean,
    gpuAvailable: Boolean,
    modelAvailable: Boolean,
    disabled: Set<AiProcessor> = emptySet()
): List<AiProcessor> = buildList {
    if (npuAvailable && AiProcessor.NPU !in disabled) add(AiProcessor.NPU)
    if (modelAvailable && gpuAvailable && AiProcessor.GPU !in disabled) add(AiProcessor.GPU)
    if (modelAvailable && AiProcessor.CPU !in disabled) add(AiProcessor.CPU)
}

internal fun requiredAvailableMemoryBytes(modelBytes: Long, processor: AiProcessor): Long {
    val base = modelBytes.coerceAtLeast(256L * MIB)
    return when (processor) {
        AiProcessor.GPU -> base + base / 2 + 768L * MIB
        AiProcessor.CPU -> base + 768L * MIB
        // HTP repacks quantized weights and reserves DSP-mapped KV/compute buffers.
        AiProcessor.NPU -> base + 1280L * MIB
        else -> 0L
    }
}

internal fun shouldDisableLocalBackend(processor: AiProcessor, error: LocalAiWorkerException?): Boolean =
    error != null && error.disableBackend && (processor != AiProcessor.CPU || error.workerCrashed)

private const val MIB = 1024L * 1024L
