package com.leap8.offline.ai

import com.leap8.offline.domain.AiProcessor
import org.junit.Assert.*
import org.junit.Test

class LocalAiMemoryGuardTest {
    private val gib = 1024L * 1024 * 1024
    private val model = 2 * gib
    private val required = requiredAvailableMemoryBytes(model, AiProcessor.NPU)

    private class FakeEnvironment(var current: LocalMemorySnapshot) : LocalMemoryEnvironment {
        var cleanups = 0
        var closes = 0
        var gcCalls = 0
        var polls = 0
        var onPoll: () -> Unit = {}
        override fun snapshot() = current
        override fun pauseReclaimableResources(): AutoCloseable {
            cleanups++
            return AutoCloseable { closes++ }
        }
        override fun requestGarbageCollection() { gcCalls++ }
        override fun awaitReclaim() { polls++; onPoll() }
    }

    @Test fun sufficientMemoryDoesNotCleanOrPauseSpeech() {
        val env = FakeEnvironment(LocalMemorySnapshot(required, false))
        val guard = LocalAiMemoryGuard(env)
        guard.openSession().use { it.ensure(model, AiProcessor.NPU) }
        assertEquals(LocalAiMemoryStage.READY, guard.status.value.stage)
        assertEquals(0, env.cleanups)
        assertEquals(0, env.gcCalls)
        assertEquals(0, env.polls)
    }

    @Test fun lowMemoryAutomaticallyCleansAndRechecksBeforeProceeding() {
        val env = FakeEnvironment(LocalMemorySnapshot(gib, false))
        env.onPoll = { if (env.polls == 2) env.current = LocalMemorySnapshot(required, false) }
        val guard = LocalAiMemoryGuard(env)
        val stages = mutableListOf<LocalAiMemoryStage>()
        guard.openSession { _, _ -> stages.add(guard.status.value.stage) }.use {
            it.ensure(model, AiProcessor.NPU)
            assertEquals(0, env.closes) // Voice cannot reload during generation/fallback.
            assertTrue(guard.status.value.cleanupAttempted)
            assertEquals(gib, guard.status.value.beforeBytes)
            assertEquals(required, guard.status.value.availableBytes)
        }
        assertTrue(LocalAiMemoryStage.CLEANING in stages)
        assertEquals(1, env.cleanups)
        assertEquals(1, env.gcCalls)
        assertEquals(2, env.polls)
        assertEquals(1, env.closes)
    }

    @Test fun unsuccessfulCleanupBlocksLoadAndDoesNotLoopForEachFallback() {
        val env = FakeEnvironment(LocalMemorySnapshot(100, true))
        val guard = LocalAiMemoryGuard(env)
        guard.openSession().use { session ->
            for (backend in listOf(AiProcessor.NPU, AiProcessor.GPU, AiProcessor.CPU)) {
                assertThrows(LocalAiMemoryException::class.java) { session.ensure(model, backend) }
            }
        }
        assertEquals(LocalAiMemoryStage.BLOCKED, guard.status.value.stage)
        assertEquals(1, env.cleanups)
        assertEquals(8, env.polls)
        assertEquals(1, env.closes)
    }

    @Test fun lowMemoryFlagAndSystemThresholdOverrideLargeAvailableNumber() {
        val env = FakeEnvironment(LocalMemorySnapshot(10 * gib, true))
        val guard = LocalAiMemoryGuard(env)
        guard.openSession().use { session ->
            assertThrows(LocalAiMemoryException::class.java) { session.ensure(model, AiProcessor.NPU) }
            env.current = LocalMemorySnapshot(10 * gib, false, 11 * gib)
            assertThrows(LocalAiMemoryException::class.java) { session.ensure(model, AiProcessor.NPU) }
        }
        assertEquals(11 * gib + gib / 4, guard.status.value.requiredBytes)
    }

    @Test fun lowerMemoryCpuCanStillRunAfterNpuRejectedWithoutCleaningTwice() {
        val cpuRequired = requiredAvailableMemoryBytes(model, AiProcessor.CPU)
        val env = FakeEnvironment(LocalMemorySnapshot(cpuRequired, false))
        val guard = LocalAiMemoryGuard(env)
        guard.openSession().use { session ->
            assertThrows(LocalAiMemoryException::class.java) { session.ensure(model, AiProcessor.NPU) }
            session.ensure(model, AiProcessor.CPU)
        }
        assertEquals(LocalAiMemoryStage.READY, guard.status.value.stage)
        assertEquals(1, env.cleanups)
    }

    @Test fun workerRaceCanForceOneCleanupEvenWhenParentCheckHadPassed() {
        val env = FakeEnvironment(LocalMemorySnapshot(10 * gib, false))
        val guard = LocalAiMemoryGuard(env)
        guard.openSession().use { session ->
            session.ensure(model, AiProcessor.NPU)
            session.ensure(model, AiProcessor.NPU, forceCleanup = true)
            session.ensure(model, AiProcessor.CPU, forceCleanup = true)
        }
        assertEquals(1, env.cleanups)
        assertEquals(1, env.closes)
    }

    @Test fun interruptedRecheckReleasesLeaseAndPropagatesCancellation() {
        val env = FakeEnvironment(LocalMemorySnapshot(0, true))
        env.onPoll = { throw InterruptedException("cancel") }
        val guard = LocalAiMemoryGuard(env)
        assertThrows(InterruptedException::class.java) {
            guard.openSession().use { it.ensure(model, AiProcessor.NPU) }
        }
        assertEquals(1, env.closes)
        assertEquals(LocalAiMemoryStage.IDLE, guard.status.value.stage)
        val next = guard.openSession()
        next.close()
        assertThrows(IllegalStateException::class.java) { next.ensure(model, AiProcessor.NPU) }
    }

    @Test fun nextUserRequestHasANewCleanupBudgetAndCloseIsIdempotent() {
        val env = FakeEnvironment(LocalMemorySnapshot(0, true))
        val guard = LocalAiMemoryGuard(env)
        repeat(2) {
            val session = guard.openSession()
            assertThrows(LocalAiMemoryException::class.java) { session.ensure(model, AiProcessor.NPU) }
            session.close()
            session.close()
        }
        assertEquals(2, env.cleanups)
        assertEquals(2, env.closes)
    }

    @Test fun failingCleanupCannotLeaveBusyStatusOrMaskTheOriginalError() {
        val env = object : LocalMemoryEnvironment {
            override fun snapshot() = LocalMemorySnapshot(0, true)
            override fun pauseReclaimableResources(): AutoCloseable = error("cleanup timeout")
            override fun requestGarbageCollection() = Unit
            override fun awaitReclaim() = Unit
        }
        val guard = LocalAiMemoryGuard(env)
        val error = assertThrows(IllegalStateException::class.java) {
            guard.openSession().use { it.ensure(model, AiProcessor.NPU) }
        }
        assertEquals("cleanup timeout", error.message)
        assertEquals(LocalAiMemoryStage.IDLE, guard.status.value.stage)
    }

    @Test fun throwingLeaseCloseCannotMaskASuccessfulAdmission() {
        var current = LocalMemorySnapshot(0, true)
        val env = object : LocalMemoryEnvironment {
            override fun snapshot() = current
            override fun pauseReclaimableResources() = AutoCloseable { error("close failed") }
            override fun requestGarbageCollection() { current = LocalMemorySnapshot(required, false) }
            override fun awaitReclaim() = Unit
        }
        val guard = LocalAiMemoryGuard(env)
        guard.openSession().use { it.ensure(model, AiProcessor.NPU) }
        assertEquals(LocalAiMemoryStage.READY, guard.status.value.stage)
    }
}
