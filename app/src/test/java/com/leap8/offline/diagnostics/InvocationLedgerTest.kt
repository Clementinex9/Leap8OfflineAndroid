package com.leap8.offline.diagnostics

import com.leap8.offline.domain.*
import org.junit.Assert.*
import org.junit.Test

class InvocationLedgerTest {
    private fun record(id: String = "one", provider: AiProvider = AiProvider.LOCAL) =
        InvocationRecord(id, 1000, InvocationMode.REVISION, provider)
    private fun usage(processor: AiProcessor = AiProcessor.NPU) = AiGenerationProgress(
        generatedTokens = 64, processor = processor, inputTokens = 100, outputTokens = 64,
        decodeMs = 2000, tokenSource = LOCAL_TOKENIZER)

    @Test fun completedNativeUsageIsRecordedOnceNotSummedAcrossCallbacks() {
        val ledger = InvocationLedger(true, emptyList())
        ledger.begin(record())
        repeat(4) { ledger.progress("one", usage(), 1000) }
        ledger.finish("one", InvocationOutcome.SUCCESS, 3000, AiProcessor.NPU)
        val result = ledger.state.value.recent.single()
        assertEquals(100, result.inputTokens)
        assertEquals(64, result.outputTokens)
        assertEquals(2000L, result.decodeMs)
        assertEquals(3000L, result.elapsedMs)
        assertTrue(ledger.state.value.active.isEmpty())
    }
    @Test fun utf8PiecesAreNotTokensAndApiMissingUsageIsNotZero() {
        val ledger = InvocationLedger(true, emptyList())
        ledger.begin(record())
        ledger.progress("one", AiGenerationProgress(generatedTokens = 30, processor = AiProcessor.NPU,
            outputTokens = 30, tokenSource = UNAVAILABLE), 100)
        ledger.finish("one", InvocationOutcome.SUCCESS, 200)
        assertNull(ledger.state.value.recent.single().outputTokens)
        ledger.begin(record("api", AiProvider.OPENAI))
        ledger.finish("api", InvocationOutcome.SUCCESS, 200, AiProcessor.CLOUD, 0)
        assertNull(ledger.state.value.recent.first().inputTokens)
        assertNull(ledger.state.value.recent.first().outputTokens)
    }
    @Test fun apiReportedCompletionIsKeptWithoutInventingPromptTokensOrBillingTotal() {
        val ledger = InvocationLedger(true, emptyList())
        ledger.begin(record(provider = AiProvider.QWEN))
        ledger.finish("one", InvocationOutcome.SUCCESS, 3000, AiProcessor.CLOUD, 44)
        val result = ledger.state.value.recent.single()
        assertEquals(44, result.outputTokens)
        assertNull(result.inputTokens)
        assertNull(result.decodeMs)
        assertEquals(API_COMPLETION_USAGE, result.tokenSource)
    }
    @Test fun interruptedPartialUsageDoesNotBecomeCompleteUsage() {
        val ledger = InvocationLedger(true, emptyList())
        ledger.begin(record())
        ledger.progress("one", AiGenerationProgress(generatedTokens = 16, outputTokens = 16,
            processor = AiProcessor.CPU, tokenSource = LOCAL_TOKENIZER), 100)
        ledger.finish("one", InvocationOutcome.CANCELLED, 300)
        assertNull(ledger.state.value.recent.single().outputTokens)
        assertEquals(UNAVAILABLE, ledger.state.value.recent.single().tokenSource)
    }
    @Test fun fallbackDoesNotReuseEarlierBackendUsage() {
        val ledger = InvocationLedger(true, emptyList())
        ledger.begin(record())
        ledger.progress("one", usage(AiProcessor.GPU), 100)
        ledger.progress("one", AiGenerationProgress(processor = AiProcessor.CPU), 200)
        ledger.finish("one", InvocationOutcome.FAILED, 300)
        val result = ledger.state.value.recent.single()
        assertNull(result.inputTokens)
        assertNull(result.outputTokens)
        assertEquals(listOf(AiProcessor.GPU, AiProcessor.CPU), result.route)
    }
    @Test fun disablingDropsOnlyMonitoringAndLateCallbacksCannotResurrectIt() {
        val ledger = InvocationLedger(true, emptyList())
        ledger.begin(record())
        ledger.setEnabled(false)
        ledger.progress("one", usage(), 100)
        ledger.finish("one", InvocationOutcome.SUCCESS, 300)
        assertTrue(ledger.state.value.recent.isEmpty())
        assertFalse(ledger.begin(record("two")))
        ledger.setEnabled(true)
        assertTrue(ledger.begin(record("three")))
    }
    @Test fun clearingHistoryKeepsActiveAndHistoryIsBounded() {
        val ledger = InvocationLedger(true, emptyList())
        repeat(110) {
            ledger.begin(record("$it"))
            ledger.finish("$it", InvocationOutcome.FAILED, 0)
        }
        assertEquals(100, ledger.state.value.recent.size)
        ledger.begin(record("active"))
        ledger.clearHistory()
        assertEquals("active", ledger.state.value.active.single().id)
        assertTrue(ledger.state.value.recent.isEmpty())
    }
    @Test fun sampleCompactionPreservesTimeEndpointsAndMissingSensors() {
        val samples = (0..150).map { PerformanceSample(it.toLong(), batteryTempC = null) }
        val compacted = compactSamples(samples)
        assertEquals(120, compacted.size)
        assertEquals(0L, compacted.first().elapsedMs)
        assertEquals(150L, compacted.last().elapsedMs)
        assertTrue(compacted.all { it.batteryTempC == null })
    }
    @Test fun modelMetadataIsBoundedAndDoesNotLeakKeyLookingValues() {
        val ledger = InvocationLedger(true, emptyList())
        ledger.begin(record().copy(model = "model sk-exampleSecret123\n" + "x".repeat(400)))
        val label = ledger.state.value.active.single().model
        assertTrue(label.length <= 120)
        assertFalse(label.contains("exampleSecret"))
        assertFalse(label.contains('\n'))
    }
}
