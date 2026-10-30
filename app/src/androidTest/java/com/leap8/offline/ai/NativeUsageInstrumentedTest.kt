package com.leap8.offline.ai

import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.leap8.offline.MainActivity
import com.leap8.offline.diagnostics.DeveloperTelemetry
import com.leap8.offline.diagnostics.InvocationMode
import com.leap8.offline.diagnostics.InvocationOutcome
import com.leap8.offline.diagnostics.TelemetryTestContext
import com.leap8.offline.diagnostics.TelemetryStore
import com.leap8.offline.domain.AiGenerationProgress
import com.leap8.offline.domain.AiProcessor
import com.leap8.offline.domain.AiProvider
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Explicit opt-in; a separately copied 4B fixture NEVER changes the user's imported model or
 * preferences. Main test orchestration owns provisioning/removing this exact fixture filename.
 * Worker memory checks are intentionally not bypassed. No API or model download is performed.
 */
@RunWith(AndroidJUnit4::class)
class NativeUsageInstrumentedTest {
    @Test fun exactNativeUsageAndActualSensorSamplesOnAllLocalProcessors() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue("Requires explicit developer fixture opt-in",
            InstrumentationRegistry.getArguments().getString("leap8DeveloperFixture4b") == "true")
        val fixture = File(instrumentation.targetContext.filesDir, "models/_developer-stats-fixture4b.gguf").canonicalFile
        assumeTrue("Provision the approved test-only 4B fixture first", fixture.isFile && fixture.canRead())
        assertEquals("Fixture must not escape the app's model directory",
            File(instrumentation.targetContext.filesDir, "models").canonicalFile, fixture.parentFile)
        val fixtureBytes = fixture.length()
        assertTrue("Fixture should be a real GGUF, not a prompt/placeholder", fixtureBytes > 1024L * 1024 * 1024)

        // Instrumentation runs as the target UID: isolate diagnostics inside a unique cache
        // sandbox, with its own preference namespace, rather than another package's data.
        val testContext = TelemetryTestContext(instrumentation.targetContext)
        assertNotEquals(instrumentation.targetContext.noBackupFilesDir.canonicalFile, testContext.noBackupFilesDir.canonicalFile)
        val telemetry = DeveloperTelemetry(testContext)
        telemetry.setEnabled(true)
        telemetry.clearHistory()
        val mainPid = Process.myPid()

        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                for (processor in listOf(AiProcessor.NPU, AiProcessor.GPU, AiProcessor.CPU)) {
                    val finalUsage = AtomicReference<AiGenerationProgress?>(null)
                    val started = SystemClock.elapsedRealtime()
                    val result = telemetry.capture(
                        mode = InvocationMode.GENERAL,
                        provider = AiProvider.LOCAL,
                        model = "fixture4b",
                        temperature = 0f,
                        onProgress = { update ->
                            if (update.tokenSource == "LOCAL_TOKENIZER" && update.inputTokens != null && update.decodeMs != null) {
                                finalUsage.set(update)
                            }
                        }
                    ) { report ->
                        LocalAiProcessClient(instrumentation.targetContext).generate(
                            processor = processor,
                            model = fixture,
                            systemPrompt = "You are an English tutor. Reply briefly in English.",
                            userPrompt = "Explain why spaced repetition helps learners remember English vocabulary.",
                            maxTokens = 32,
                            temperature = 0f,
                            onProgress = report
                        )
                    }
                    val usage = checkNotNull(finalUsage.get()) { "$processor did not return final native usage" }
                    assertEquals("Worker silently switched processor", processor, result.processor)
                    assertEquals(processor, usage.processor)
                    assertEquals("LOCAL_TOKENIZER", usage.tokenSource)
                    assertTrue("Input count must come from the real tokenizer", checkNotNull(usage.inputTokens) > 0)
                    assertTrue("Output count outside requested range", checkNotNull(usage.outputTokens) in 1..32)
                    assertEquals(usage.outputTokens, result.generatedTokens)
                    assertEquals(usage.outputTokens, usage.generatedTokens)
                    assertTrue("Native generation timing missing/zero", checkNotNull(usage.decodeMs) > 0)
                    assertTrue("Native prefill timing must be nonnegative", checkNotNull(usage.promptMs) >= 0)
                    assertTrue("Native result unexpectedly empty", result.text.isNotBlank())
                    when (processor) {
                        AiProcessor.NPU -> assertTrue("No actual Hexagon engine label", result.engine.contains("HTP0") && result.engine.contains("Hexagon"))
                        AiProcessor.GPU -> assertTrue("No actual GPU engine label", result.engine.contains("OpenCL", true) || result.engine.contains("Vulkan", true))
                        AiProcessor.CPU -> assertTrue("No actual CPU engine label", result.engine.contains("CPU", true))
                        else -> error("Unexpected test processor")
                    }

                    val record = telemetry.state.value.recent.first()
                    assertEquals(InvocationOutcome.SUCCESS, record.outcome)
                    assertEquals(processor, record.processor)
                    assertEquals(usage.inputTokens, record.inputTokens)
                    assertEquals(usage.outputTokens, record.outputTokens)
                    assertEquals(usage.decodeMs, record.decodeMs)
                    assertEquals("LOCAL_TOKENIZER", record.tokenSource)
                    assertTrue("No actual performance samples were collected", record.samples.isNotEmpty())
                    assertTrue("Android available-memory sample is missing", record.samples.any { (it.availableMemoryBytes ?: 0L) > 0 })
                    val persistenceDeadline = SystemClock.elapsedRealtime() + 2000
                    var persisted = false
                    while (SystemClock.elapsedRealtime() < persistenceDeadline) {
                        persisted = runCatching { TelemetryStore(testContext).load().any {
                            it.id == record.id && it.outcome == InvocationOutcome.SUCCESS &&
                                it.inputTokens == usage.inputTokens && it.outputTokens == usage.outputTokens &&
                                it.decodeMs == usage.decodeMs && it.tokenSource == "LOCAL_TOKENIZER"
                        } }.getOrDefault(false)
                        if (persisted) break
                        Thread.sleep(25)
                    }
                    assertTrue("Native telemetry did not persist in its isolated sandbox", persisted)
                    assertFalse("Native telemetry sandbox storage warning", telemetry.state.value.storageWarning)
                    assertEquals("UI process changed during native generation", mainPid, Process.myPid())
                    assertTrue("Fixture was changed/deleted by inference", fixture.isFile && fixture.length() == fixtureBytes)
                    // Metadata only. Never log generated text, prompts, model content or secrets.
                    Log.i("Leap8NativeUsageTest", "backend=$processor input=${usage.inputTokens} output=${usage.outputTokens} " +
                        "prefillMs=${usage.promptMs} decodeMs=${usage.decodeMs} wallMs=${SystemClock.elapsedRealtime() - started} " +
                        "samples=${record.samples.size} pidUnchanged=true")
                }
            }
        } finally {
            // This removes only test-sandbox diagnostic history, never the model fixture or
            // the user's model-selection/learning data. Orchestration cleans the fixture.
            telemetry.clearHistory()
        }
    }
}
