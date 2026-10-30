package com.leap8.offline.diagnostics

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.leap8.offline.MainActivity
import com.leap8.offline.ai.AiTextGeneration
import com.leap8.offline.domain.AiGenerationProgress
import com.leap8.offline.domain.AiProcessor
import com.leap8.offline.domain.AiProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CancellationException

/** Fake inference plus real sampler/storage, isolated to a unique app-cache sandbox. */
@RunWith(AndroidJUnit4::class)
class DeveloperTelemetryInstrumentedTest {
    private var foreground: ActivityScenario<MainActivity>? = null
    private lateinit var context: Context
    private lateinit var telemetry: DeveloperTelemetry

    @Before fun prepareOnlyTestSandboxHistory() {
        // ADB's pre-launch does not survive the instrumentation process restart on vivo.
        // Keep an Activity owned by this test resumed until all sampler/storage work drains.
        foreground = ActivityScenario.launch(MainActivity::class.java)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        context = TelemetryTestContext(instrumentation.targetContext)
        assertNotEquals(instrumentation.targetContext.noBackupFilesDir.canonicalFile, context.noBackupFilesDir.canonicalFile)
        telemetry = DeveloperTelemetry(context)
        telemetry.setEnabled(true)
        telemetry.clearHistory()
        awaitPersisted { it.isEmpty() }
    }

    @After fun clearOnlyTestSandboxHistory() {
        try {
            if (this::telemetry.isInitialized) {
                telemetry.setEnabled(true)
                telemetry.clearHistory()
                awaitPersisted { it.isEmpty() }
            }
        } finally {
            foreground?.close()
            foreground = null
        }
    }

    @Test fun realSamplesAndExactFinalMetadataPersistAcrossRecreation() {
        var forwarded = 0
        val result = telemetry.capture(
            mode = InvocationMode.WRITING,
            provider = AiProvider.LOCAL,
            model = "instrumentation-fixture",
            temperature = .25f,
            onProgress = { forwarded++ }
        ) { report ->
            report(AiGenerationProgress(
                running = true, stage = "fixture", generatedTokens = 5,
                processor = AiProcessor.NPU, inputTokens = 10, outputTokens = 5,
                decodeMs = 1000, tokenSource = LOCAL_TOKENIZER
            ))
            // Allow the real public-API sampler's immediate and one-second ticks to run.
            Thread.sleep(1100)
            AiTextGeneration("fixture", 1000, "fixture", AiProcessor.NPU, 5)
        }
        assertEquals(5, result.generatedTokens)
        assertEquals(1, forwarded)
        assertTrue(telemetry.state.value.active.isEmpty())
        val completed = telemetry.state.value.recent.single()
        assertEquals(InvocationOutcome.SUCCESS, completed.outcome)
        assertEquals(InvocationMode.WRITING, completed.mode)
        assertEquals(AiProcessor.NPU, completed.processor)
        assertEquals(10, completed.inputTokens)
        assertEquals(5, completed.outputTokens)
        assertEquals(1000L, completed.decodeMs)
        assertEquals(LOCAL_TOKENIZER, completed.tokenSource)
        assertTrue(completed.elapsedMs >= 1000)
        assertTrue("Expected at least two actual samples", completed.samples.size >= 2)
        completed.samples.forEach { sample ->
            assertTrue("Memory sampling must use Android's actual available-memory value", checkNotNull(sample.availableMemoryBytes) > 0)
            assertTrue(checkNotNull(sample.javaHeapBytes) >= 0)
            sample.batteryTempC?.let { assertTrue(it.isFinite() && it in -20f..85f) }
            sample.thermalStatus?.let { assertTrue(it in 0..6) }
        }
        awaitPersisted { records ->
            records.singleOrNull()?.let {
                it.id == completed.id && it.outcome == InvocationOutcome.SUCCESS &&
                    it.inputTokens == 10 && it.outputTokens == 5 && it.decodeMs == 1000L &&
                    it.tokenSource == LOCAL_TOKENIZER
            } == true
        }
        val restored = DeveloperTelemetry(context).state.value.recent.single()
        assertEquals(completed.id, restored.id)
        assertEquals(InvocationOutcome.SUCCESS, restored.outcome)
        assertEquals(10, restored.inputTokens)
        assertEquals(5, restored.outputTokens)
        assertEquals(1000L, restored.decodeMs)
        assertEquals(LOCAL_TOKENIZER, restored.tokenSource)
        assertEquals(completed.samples.sortedBy { it.elapsedMs }, restored.samples)
    }

    @Test fun disabledRecordingStillRunsAndForwardsButCreatesNoDiagnosticRecord() {
        telemetry.setEnabled(false)
        var calls = 0
        var forwarded = 0
        val result = telemetry.capture(
            InvocationMode.GENERAL, AiProvider.LOCAL, "instrumentation-fixture", 0f,
            onProgress = { forwarded++ }
        ) { report ->
            calls++
            report(AiGenerationProgress(running = true, stage = "fixture", processor = AiProcessor.CPU))
            AiTextGeneration("fixture", 1, "fixture", AiProcessor.CPU)
        }
        assertEquals(1, calls)
        assertEquals(1, forwarded)
        assertEquals("fixture", result.text)
        assertFalse(telemetry.state.value.enabled)
        assertTrue(telemetry.state.value.active.isEmpty())
        assertTrue(telemetry.state.value.recent.isEmpty())
        awaitPersisted { it.isEmpty() }
    }

    @Test fun failedPartialProgressIsObservedButNeverCountedAsFinalUsage() {
        assertThrows(IllegalStateException::class.java) {
            telemetry.capture(InvocationMode.REVISION, AiProvider.LOCAL, "instrumentation-fixture", 0f, {}) { report ->
                report(AiGenerationProgress(
                    running = true, stage = "fixture", generatedTokens = 16,
                    processor = AiProcessor.NPU, outputTokens = 16, tokenSource = LOCAL_TOKENIZER
                ))
                Thread.sleep(1100)
                throw IllegalStateException("fixture failure body must not be persisted")
            }
        }
        val failed = telemetry.state.value.recent.single()
        assertEquals(InvocationOutcome.FAILED, failed.outcome)
        assertNull(failed.inputTokens)
        assertNull(failed.outputTokens)
        assertNull(failed.decodeMs)
        assertEquals(UNAVAILABLE, failed.tokenSource)
        assertTrue(failed.samples.any { (it.generatedTokens ?: 0) >= 16 })
        awaitPersisted { records ->
            records.singleOrNull()?.let {
                it.id == failed.id && it.outcome == InvocationOutcome.FAILED &&
                    it.inputTokens == null && it.outputTokens == null && it.decodeMs == null &&
                    it.tokenSource == UNAVAILABLE
            } == true
        }
        val restored = DeveloperTelemetry(context).state.value.recent.single()
        assertEquals(InvocationOutcome.FAILED, restored.outcome)
        assertNull(restored.outputTokens)
        assertFalse(TelemetryCodec.encode(listOf(restored)).contains("failure body"))
    }

    @Test fun cancelledCallHasAnExplicitOutcomeAndUnknownUsage() {
        assertThrows(CancellationException::class.java) {
            telemetry.capture(InvocationMode.COACH_CORRECTION, AiProvider.LOCAL, "instrumentation-fixture", 0f, {}) {
                throw CancellationException("fixture")
            }
        }
        val cancelled = telemetry.state.value.recent.single()
        assertEquals(InvocationOutcome.CANCELLED, cancelled.outcome)
        assertNull(cancelled.inputTokens)
        assertNull(cancelled.outputTokens)
        assertEquals(UNAVAILABLE, cancelled.tokenSource)
        assertTrue(telemetry.state.value.active.isEmpty())
        awaitPersisted { records ->
            records.singleOrNull()?.let {
                it.id == cancelled.id && it.outcome == InvocationOutcome.CANCELLED &&
                    it.inputTokens == null && it.outputTokens == null && it.tokenSource == UNAVAILABLE
            } == true
        }
    }

    private fun awaitPersisted(predicate: (List<InvocationRecord>) -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 2000
        while (SystemClock.elapsedRealtime() < deadline) {
            val saved = runCatching { TelemetryStore(context).load() }.getOrNull()
            // Missing-file load() also returns empty. For setup/teardown's empty predicate,
            // require a real committed sandbox file rather than accepting "not written yet".
            val committed = File(context.noBackupFilesDir, "diagnostics/performance-v1.json").isFile
            if (committed && saved != null && predicate(saved)) {
                assertFalse("Telemetry sandbox persistence reported a storage failure", telemetry.state.value.storageWarning)
                return
            }
            Thread.sleep(25)
        }
        fail("Test-sandbox telemetry did not persist within two seconds")
    }
}
