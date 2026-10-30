package com.leap8.offline.ai

import com.leap8.offline.domain.AiProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAiFallbackPolicyTest {
    @Test
    fun temporaryMemoryFailureDoesNotDisableGpuForTheSession() {
        val error = LocalAiWorkerException("low memory", AiProcessor.GPU, false, disableBackend = false)
        assertFalse(shouldDisableLocalBackend(AiProcessor.GPU, error))
        assertTrue(shouldDisableLocalBackend(AiProcessor.GPU, LocalAiWorkerException("driver", AiProcessor.GPU, true)))
        assertFalse(shouldDisableLocalBackend(AiProcessor.CPU, LocalAiWorkerException("input", AiProcessor.CPU, false)))
        assertTrue(shouldDisableLocalBackend(AiProcessor.CPU, LocalAiWorkerException("crash", AiProcessor.CPU, true)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOversizedIpcBeforeLaunchingAWorker() {
        validateLocalAiRequest("s", "x".repeat(LocalAiIpc.MAX_PROMPT_CHARS), 768, 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonFiniteSamplingBeforeNativeCode() {
        validateLocalAiRequest("s", "u", 768, Float.NaN)
    }

    @Test
    fun acceptsBoundarySizedRequestAndGreedySampling() {
        validateLocalAiRequest("", "x".repeat(LocalAiIpc.MAX_PROMPT_CHARS), 1536, 0f)
    }

    @Test
    fun ordersHardwareBackendsAndSkipsFailedOnes() {
        assertEquals(
            listOf(AiProcessor.NPU, AiProcessor.GPU, AiProcessor.CPU),
            localBackendChain(npuAvailable = true, gpuAvailable = true, modelAvailable = true)
        )
        assertEquals(
            listOf(AiProcessor.NPU, AiProcessor.CPU),
            localBackendChain(
                npuAvailable = true,
                gpuAvailable = true,
                modelAvailable = true,
                disabled = setOf(AiProcessor.GPU)
            )
        )
    }

    @Test
    fun neverOffersGpuOrCpuWithoutAnInstalledModel() {
        assertEquals(
            emptyList<AiProcessor>(),
            localBackendChain(npuAvailable = false, gpuAvailable = true, modelAvailable = false)
        )
        assertEquals(
            listOf(AiProcessor.NPU),
            localBackendChain(npuAvailable = true, gpuAvailable = true, modelAvailable = false)
        )
    }

    @Test
    fun gpuPreflightKeepsMoreRecoveryHeadroomThanCpu() {
        val modelBytes = 2_497_280_736L
        val gpuRequired = requiredAvailableMemoryBytes(modelBytes, AiProcessor.GPU)
        val cpuRequired = requiredAvailableMemoryBytes(modelBytes, AiProcessor.CPU)

        assertTrue(gpuRequired > cpuRequired)
        assertTrue(cpuRequired > modelBytes)
        assertTrue(requiredAvailableMemoryBytes(modelBytes, AiProcessor.NPU) > cpuRequired)
    }
}
