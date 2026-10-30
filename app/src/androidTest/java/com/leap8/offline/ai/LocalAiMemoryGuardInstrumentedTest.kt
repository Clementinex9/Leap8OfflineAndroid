package com.leap8.offline.ai

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ActivityScenario
import com.leap8.offline.Leap8Application
import com.leap8.offline.MainActivity
import com.leap8.offline.domain.AiProcessor
import com.leap8.offline.speech.NaturalVoice
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith

/** Injected low snapshot, not a real RAM flood. Real main dispatch, voice client and memory reader. */
@RunWith(AndroidJUnit4::class)
class LocalAiMemoryGuardInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun importedModelGeneratesOrSafelyRejectsWhenMemoryRemainsInsufficient() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("leap8RunModelTests") == "true")
        val app = instrumentation.targetContext.applicationContext as Leap8Application
        val model = app.aiModelManager.modelFile()
        assumeTrue(model != null)
        val bytes = requireNotNull(model).length()
        val candidates = app.offlineAiService.runtimeStatus().fallbackChain
        ActivityScenario.launch(MainActivity::class.java).use {
            try {
                val result = app.offlineAiService.generateWithFallback(
                    systemPrompt = "You are an English tutor. Reply briefly in English.",
                    userPrompt = "Correct this sentence: Yesterday I go to school.",
                    maxTokens = 32
                )
                assertTrue(result.text.isNotBlank())
                assertTrue(result.processor in setOf(AiProcessor.NPU, AiProcessor.GPU, AiProcessor.CPU))
                assertEquals(LocalAiMemoryStage.READY, app.offlineAiService.memoryStatus.value.stage)
            } catch (error: IllegalStateException) {
                // The imported model may change, and the test must not defeat real low-memory
                // protection or replace the user's current model just to force a success case.
                val rejection = generateSequence<Throwable>(error) { it.cause }.any {
                    it is LocalAiMemoryException || it is LocalAiWorkerException && it.memoryRejected
                }
                if (!rejection) throw error
                val status = app.offlineAiService.memoryStatus.value
                assertEquals(LocalAiMemoryStage.BLOCKED, status.stage)
                assertTrue(status.cleanupAttempted)
                assertEquals(candidates, app.offlineAiService.runtimeStatus().fallbackChain)
            }
            assertTrue(model.isFile)
            assertEquals(bytes, model.length())
        }
    }

    private class NoNativeSpeechContext(base: Context) : ContextWrapper(base) {
        var binds = 0
        var unbinds = 0
        override fun getApplicationContext(): Context = this
        override fun bindService(service: Intent, connection: ServiceConnection, flags: Int): Boolean {
            binds++
            return true // Deliberately no native model/service allocation in this test.
        }
        override fun unbindService(connection: ServiceConnection) { unbinds++ }
    }

    @Test fun insufficientSnapshotTriggersRealVoicePauseAndLazyResumeWithoutDataDeletion() {
        val context = NoNativeSpeechContext(instrumentation.targetContext)
        lateinit var voice: NaturalVoice
        instrumentation.runOnMainSync {
            voice = NaturalVoice(context) { _, _ -> }
            assertTrue(voice.speak("example"))
            assertEquals(1, context.binds)
        }
        val real = AndroidLocalMemoryEnvironment(instrumentation.targetContext)
        var reads = 0
        var cleanupCalls = 0
        val env = object : LocalMemoryEnvironment {
            override fun snapshot() = if (reads++ == 0) LocalMemorySnapshot(0, true) else real.snapshot()
            override fun pauseReclaimableResources(): AutoCloseable {
                cleanupCalls++
                return real.pauseReclaimableResources()
            }
            override fun requestGarbageCollection() = real.requestGarbageCollection()
            override fun awaitReclaim() = real.awaitReclaim()
        }
        val guard = LocalAiMemoryGuard(env)
        try {
            guard.openSession().use {
                it.ensure(256L * 1024 * 1024, AiProcessor.CPU)
                assertEquals(LocalAiMemoryStage.READY, guard.status.value.stage)
                assertTrue(guard.status.value.cleanupAttempted)
                assertEquals(1, cleanupCalls)
                instrumentation.runOnMainSync {
                    assertEquals(1, context.unbinds)
                    assertFalse(voice.speak("cannot reload while AI owns the pause"))
                    assertEquals(1, context.binds)
                }
            }
            instrumentation.runOnMainSync {
                assertTrue(voice.speak("can reload after AI finishes"))
                assertEquals(2, context.binds)
            }
        } finally { instrumentation.runOnMainSync { voice.shutdown() } }
    }
}
