package com.leap8.offline.diagnostics

import com.leap8.offline.domain.AiProcessor
import com.leap8.offline.domain.AiProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class TelemetryStoreTest {
    private fun record(number: Int = 1) = InvocationRecord(
        id = UUID(0, number.toLong()).toString(),
        startedAtMs = 1_793_350_000_000,
        mode = InvocationMode.WRITING,
        provider = AiProvider.LOCAL,
        processor = AiProcessor.NPU,
        model = "Qwen3.5-9B-Q4_K_M.gguf",
        samplingTemperature = .25f,
        outcome = InvocationOutcome.SUCCESS,
        elapsedMs = 1000,
        inputTokens = 26,
        outputTokens = 32,
        decodeMs = 800,
        firstProgressMs = 100,
        tokenSource = "LOCAL_TOKENIZER",
        route = listOf(AiProcessor.NPU),
        samples = listOf(PerformanceSample(0, 28.5f, 6_000_000_000, 100_000_000, 0, 0))
    )

    @Test fun roundTripsWhitelistedMetadataWithoutChangingExactCounts() {
        val record = record()
        assertEquals(listOf(record), TelemetryCodec.decode(TelemetryCodec.encode(listOf(record))))
    }

    @Test fun preservesUnknownCountsAndSensorsAsNullRatherThanZero() {
        val unknown = record().copy(inputTokens = null, outputTokens = null, decodeMs = null,
            tokenSource = "UNAVAILABLE", samples = listOf(PerformanceSample(100)))
        val result = TelemetryCodec.decode(TelemetryCodec.encode(listOf(unknown))).single()
        assertNull(result.inputTokens)
        assertNull(result.outputTokens)
        assertNull(result.samples.single().batteryTempC)
        assertNull(result.samples.single().thermalStatus)
        assertNull(result.samples.single().generatedTokens)
        assertEquals("UNAVAILABLE", result.tokenSource)
    }

    @Test fun restoresUnfinishedCallsAsInterruptedNotSuccessful() {
        val result = TelemetryCodec.decode(TelemetryCodec.encode(listOf(record().copy(
            outcome = InvocationOutcome.RUNNING, outputTokens = null, elapsedMs = 500
        )))).single()
        assertEquals(InvocationOutcome.INTERRUPTED, result.outcome)
        assertEquals(500L, result.elapsedMs)
        assertNull(result.outputTokens)
    }

    @Test fun interruptedRestoresNeverTreatPartialCountersAsFinalUsage() {
        for (outcome in listOf(InvocationOutcome.RUNNING, InvocationOutcome.INTERRUPTED)) {
            val partial = record().copy(outcome = outcome, inputTokens = 26, outputTokens = 16,
                decodeMs = 400, tokenSource = "LOCAL_TOKENIZER",
                samples = listOf(PerformanceSample(400, generatedTokens = 16)))
            val result = TelemetryCodec.decode(TelemetryCodec.encode(listOf(partial))).single()
            assertEquals(InvocationOutcome.INTERRUPTED, result.outcome)
            assertNull(result.inputTokens)
            assertNull(result.outputTokens)
            assertNull(result.decodeMs)
            assertEquals("UNAVAILABLE", result.tokenSource)
            assertEquals(16, result.samples.single().generatedTokens)
        }
    }

    @Test fun capsRecordsAndCompactsSamplesPreservingFirstAndLast() {
        val samples = (0..2000).map { PerformanceSample(it.toLong(), batteryTempC = 20f + it % 10) }
        val encoded = TelemetryCodec.encode((1..110).map { record(it).copy(samples = samples) })
        val decoded = TelemetryCodec.decode(encoded)
        assertEquals(100, decoded.size)
        assertEquals(record(1).id, decoded.first().id)
        assertEquals(record(100).id, decoded.last().id)
        assertEquals(120, decoded.first().samples.size)
        assertEquals(0L, decoded.first().samples.first().elapsedMs)
        assertEquals(2000L, decoded.first().samples.last().elapsedMs)
    }

    @Test fun rejectsNonFiniteAndImpossibleNumericReadings() {
        val record = record().copy(samplingTemperature = Float.NaN,
            inputTokens = -1, outputTokens = -1,
            samples = listOf(PerformanceSample(5, Float.POSITIVE_INFINITY, -1, -1, 99, -1)))
        val result = TelemetryCodec.decode(TelemetryCodec.encode(listOf(record))).single()
        assertNull(result.samplingTemperature)
        assertNull(result.inputTokens)
        assertNull(result.outputTokens)
        assertNull(result.samples.single().batteryTempC)
        assertNull(result.samples.single().availableMemoryBytes)
        assertNull(result.samples.single().javaHeapBytes)
        assertNull(result.samples.single().thermalStatus)
    }

    @Test fun doesNotCoerceStringsOrFractionsIntoTokenCounts() {
        val root = JSONObject(TelemetryCodec.encode(listOf(record())))
        root.getJSONArray("records").getJSONObject(0)
            .put("inputTokens", "1234").put("outputTokens", 3.5)
            .put("samplingTemperature", "0.25").put("processor", "UNRECOGNIZED")
        val result = TelemetryCodec.decode(root.toString()).single()
        assertNull(result.inputTokens)
        assertNull(result.outputTokens)
        assertNull(result.samplingTemperature)
        assertNull(result.processor)
    }

    @Test fun dropsMalformedRequiredIdentityAndEnumsButKeepsOtherRecords() {
        val root = JSONObject(TelemetryCodec.encode(listOf(record(1), record(2), record(3))))
        root.getJSONArray("records").getJSONObject(0).put("id", "learner prompt masquerading as id")
        root.getJSONArray("records").getJSONObject(1).put("provider", "UNKNOWN")
        val result = TelemetryCodec.decode(root.toString())
        assertEquals(listOf(record(3).id), result.map { it.id })
    }

    @Test fun serializesNoUnknownFreeFormFieldsOrCredentialLookingMetadata() {
        val raw = record().copy(model = "https://example.test?api_key=secret", tokenSource = "Bearer secret")
        val encoded = TelemetryCodec.encode(listOf(raw))
        assertFalse(encoded.contains("secret"))
        assertFalse(encoded.contains("example.test"))
        val root = JSONObject(encoded)
        root.getJSONArray("records").getJSONObject(0)
            .put("prompt", "PRIVATE_ESSAY").put("response", "PRIVATE_OUTPUT").put("apiKey", "PRIVATE_KEY")
        val rewritten = TelemetryCodec.encode(TelemetryCodec.decode(root.toString()))
        assertFalse(rewritten.contains("PRIVATE_"))
        assertEquals("UNAVAILABLE", TelemetryCodec.decode(rewritten).single().tokenSource)
        assertEquals("", TelemetryCodec.decode(rewritten).single().model)
    }

    @Test fun boundsModelTextAndDeduplicatesIdsAndRoutes() {
        val first = record().copy(model = "Q".repeat(400), route = listOf(AiProcessor.NPU, AiProcessor.NPU, AiProcessor.CPU))
        val result = TelemetryCodec.decode(TelemetryCodec.encode(listOf(first, first)))
        assertEquals(1, result.size)
        assertEquals(120, result.single().model.length)
        assertEquals(listOf(AiProcessor.NPU, AiProcessor.CPU), result.single().route)
    }

    @Test fun malformedOversizedAndFutureVersionFilesFailSafely() {
        assertThrows(IllegalArgumentException::class.java) { TelemetryCodec.decode("{broken") }
        assertThrows(IllegalArgumentException::class.java) {
            TelemetryCodec.decode(" ".repeat(TelemetryCodec.MAX_BYTES + 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TelemetryCodec.decode(JSONObject().put("version", 2).put("records", JSONArray()).toString())
        }
        assertThrows(IllegalArgumentException::class.java) { TelemetryCodec.decode("{\"version\":1}") }
    }
}
