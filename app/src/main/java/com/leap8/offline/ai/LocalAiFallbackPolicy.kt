package com.leap8.offline.ai

import com.leap8.offline.core.Leap8CoreNative
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
    val processorOrdinal = when (processor) {
        AiProcessor.NPU -> 0
        AiProcessor.GPU -> 1
        AiProcessor.CPU -> 2
        else -> -1
    }
    return Leap8CoreNative.requiredAvailableMemoryBytes(modelBytes, processorOrdinal)
}

internal fun shouldDisableLocalBackend(processor: AiProcessor, error: LocalAiWorkerException?): Boolean =
    error != null && error.disableBackend && (processor != AiProcessor.CPU || error.workerCrashed)
