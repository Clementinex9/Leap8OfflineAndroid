package com.leap8.offline.ai

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.leap8.offline.domain.AiProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Runs on a device; invalid model paths exercise real Binder/Looper/executor dispatch without JNI. */
@RunWith(AndroidJUnit4::class)
class LocalAiProcessIpcTest {
    @Test
    fun cpuAndGpuWorkersPreserveRequestsAfterLooperRecyclesMessages() {
        withForegroundActivity {
            verifyWorker(LocalAiProcessService::class.java, "CPU")
            verifyWorker(LocalAiGpuProcessService::class.java, "GPU")
        }
    }

    /**
     * Run only on a real ARM64 device after importing a GGUF model into the app:
     * -Pandroid.testInstrumentationRunnerArguments.leap8RunModelTests=true
     *
     * This deliberately bypasses the routing policy to verify the strict CPU worker itself.
     */
    @Test
    fun importedModelGeneratesOnStrictCpuWorker() {
        val model = requireOptInModel()
        withForegroundActivity {
            val mainPid = Process.myPid()
            val progressProcessors = mutableListOf<AiProcessor>()
            val result = LocalAiProcessClient(targetContext()).generate(
                processor = AiProcessor.CPU,
                model = model,
                systemPrompt = SHORT_SYSTEM_PROMPT,
                userPrompt = SHORT_USER_PROMPT,
                maxTokens = 32,
                temperature = 0f,
                onProgress = { update -> synchronized(progressProcessors) {
                    update.processor?.let(progressProcessors::add)
                } }
            )
            assertEquals(AiProcessor.CPU, result.processor)
            assertTrue("CPU returned blank output", result.text.isNotBlank())
            assertTrue("CPU worker did not identify its actual backend", result.engine.contains("CPU", true))
            assertTrue("CPU progress mislabeled its backend", synchronized(progressProcessors) {
                progressProcessors.isNotEmpty() && progressProcessors.all { it == AiProcessor.CPU }
            })
            assertEquals("UI process changed during CPU generation", mainPid, Process.myPid())
        }
    }

    /** Verify actual Vulkan offload instead of trusting an Android Vulkan feature flag. */
    @Test
    fun importedModelGeneratesOnGpuWorker() {
        val model = requireOptInModel()
        assumeTrue(
            "This device does not declare Vulkan hardware support",
            targetContext().packageManager.hasSystemFeature(
                android.content.pm.PackageManager.FEATURE_VULKAN_HARDWARE_VERSION
            )
        )
        withForegroundActivity {
            val mainPid = Process.myPid()
            val progressProcessors = mutableListOf<AiProcessor>()
            val result = LocalAiProcessClient(targetContext()).generate(
                processor = AiProcessor.GPU,
                model = model,
                systemPrompt = SHORT_SYSTEM_PROMPT,
                userPrompt = SHORT_USER_PROMPT,
                maxTokens = 32,
                temperature = 0f,
                onProgress = { update -> synchronized(progressProcessors) {
                    update.processor?.let(progressProcessors::add)
                } }
            )
            assertEquals("GPU request silently ran on CPU", AiProcessor.GPU, result.processor)
            assertTrue("GPU returned blank output", result.text.isNotBlank())
            assertTrue("No actual GPU backend was reported", result.engine.contains("Vulkan", true) || result.engine.contains("OpenCL", true))
            assertTrue("GPU progress mislabeled its backend", synchronized(progressProcessors) {
                progressProcessors.isNotEmpty() && progressProcessors.all { it == AiProcessor.GPU }
            })
            assertEquals("UI process changed during GPU generation", mainPid, Process.myPid())
        }
    }

    /** Candidate metadata alone is insufficient: the bridge refuses output without an HTP session/offload. */
    @Test
    fun importedModelGeneratesOnRealHexagonWorker() {
        val model = requireOptInModel()
        assumeHexagonCandidate()
        withForegroundActivity {
            val mainPid = Process.myPid()
            val result = LocalAiProcessClient(targetContext()).generate(
                processor = AiProcessor.NPU, model = model,
                systemPrompt = SHORT_SYSTEM_PROMPT, userPrompt = SHORT_USER_PROMPT,
                maxTokens = 32, temperature = 0f, onProgress = {}
            )
            assertEquals("NPU request silently used another processor", AiProcessor.NPU, result.processor)
            assertTrue("Hexagon output was blank", result.text.isNotBlank())
            assertTrue("Real HTP backend was not reported", result.engine.contains("HTP0") && result.engine.contains("Hexagon"))
            assertTrue("NPU generation did not stream real token pieces", result.generatedTokens > 0)
            assertEquals("UI process changed during NPU generation", mainPid, Process.myPid())
        }
    }

    /** Uses the app-owned Binder EXIT control, including in non-debuggable performance builds. */
    @Test
    fun npuWorkerDeathFallsBackToIndependentGpuOrCpu() {
        requireOptInModel()
        assumeHexagonCandidate()
        withForegroundActivity {
            val context = targetContext()
            val service = OfflineAiService(context, OfflineAiModelManager(context))
            assumeTrue(service.runtimeStatus().fallbackChain.firstOrNull() == AiProcessor.NPU)
            val mainPid = Process.myPid()
            val connected = CountDownLatch(1)
            val target = AtomicReference<Messenger?>(null)
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    target.set(Messenger(binder)); connected.countDown()
                }
                override fun onServiceDisconnected(name: ComponentName) {}
            }
            var bound = context.bindService(Intent(context, LocalAiNpuProcessService::class.java), connection, Context.BIND_AUTO_CREATE)
            val attempted = AtomicBoolean(false)
            val killError = AtomicReference<String?>(null)
            try {
                assertTrue(bound)
                assertTrue("NPU worker did not bind", connected.await(15, TimeUnit.SECONDS))
                val result = service.generateWithFallback(
                    systemPrompt = "Write a detailed numbered explanation in English.",
                    userPrompt = "Give ten practical reasons to study English and explain each reason.",
                    maxTokens = 128, temperature = 0f
                ) { update ->
                    if (update.processor == AiProcessor.NPU && update.generatedTokens >= 8 && attempted.compareAndSet(false, true)) {
                        runCatching {
                            // Remove this extra binding before EXIT so Android cannot auto-restart it.
                            context.unbindService(connection); bound = false
                            requireNotNull(target.get()).send(Message.obtain(null, LocalAiIpc.CANCEL_AND_EXIT))
                        }.onFailure { killError.set(it.message ?: it.javaClass.simpleName) }
                    }
                }
                assertTrue("NPU never reached real streamed generation", attempted.get())
                assertNull("NPU EXIT control failed: ${killError.get()}", killError.get())
                assertTrue("Router did not recover on independent GPU/CPU", result.processor in setOf(AiProcessor.GPU, AiProcessor.CPU))
                assertTrue("Fallback returned empty text", result.text.isNotBlank())
                assertEquals("UI process was affected by NPU death", mainPid, Process.myPid())
                assertEquals(result.processor, service.runtimeStatus().processor)
            } finally {
                if (bound) context.unbindService(connection)
            }
        }
    }

    private fun assumeHexagonCandidate() {
        val candidate = runCatching {
            Class.forName("com.leap8.qairt.HexagonModelBundle", false, targetContext().classLoader)
                .getMethod("isCandidate", Context::class.java).invoke(null, targetContext()) as Boolean
        }.getOrDefault(false)
        assumeTrue("Requires matched SM8750 b11982 Hexagon runtime assets", candidate)
    }

    /**
     * Kill the real GPU worker only after it reports a loaded model. The normal router must then
     * complete the same request on a separate CPU-only worker while the UI process stays alive.
     */
    @Test
    fun gpuWorkerDeathFallsBackToStrictCpu() {
        requireOptInModel()
        assumeTrue(
            "This device does not declare Vulkan hardware support",
            targetContext().packageManager.hasSystemFeature(
                android.content.pm.PackageManager.FEATURE_VULKAN_HARDWARE_VERSION
            )
        )
        withForegroundActivity {
            val context = targetContext()
            val service = OfflineAiService(context, OfflineAiModelManager(context))
            assumeTrue(
                "This test requires GPU as the first available local backend",
                service.runtimeStatus().fallbackChain.firstOrNull() == AiProcessor.GPU
            )
            val mainPid = Process.myPid()
            val killAttempted = AtomicBoolean(false)
            val killError = AtomicReference<String?>(null)
            val result = service.generateWithFallback(
                systemPrompt = SHORT_SYSTEM_PROMPT,
                userPrompt = SHORT_USER_PROMPT,
                maxTokens = 32,
                temperature = 0f
            ) { update ->
                if (update.processor == AiProcessor.GPU && update.fraction >= .10f &&
                    killAttempted.compareAndSet(false, true)
                ) {
                    // LocalAiProcessClient guards progress callbacks; capture failures explicitly
                    // so a shell/permission error cannot silently turn into a misleading result.
                    runCatching {
                        val processName = "${context.packageName}:leap8_gpu"
                        val workerPid = shell("pidof $processName")
                            .split(Regex("\\s+"))
                            .firstOrNull { it.matches(Regex("[0-9]+")) }
                            ?.toIntOrNull()
                            ?: error("GPU worker PID was not visible")
                        check(workerPid != mainPid) { "Refusing to terminate the app process" }
                        val output = shell("run-as ${context.packageName} kill -9 $workerPid")
                        check(!output.contains("permission denied", true) &&
                            !output.contains("run-as:", true)) {
                            "Unable to terminate GPU worker: $output"
                        }
                    }.onFailure { killError.set(it.message ?: it.javaClass.simpleName) }
                }
            }
            assertTrue("GPU never reported a loaded model", killAttempted.get())
            assertNull("GPU worker termination failed: ${killError.get()}", killError.get())
            assertEquals("Router did not fall back to strict CPU", AiProcessor.CPU, result.processor)
            assertTrue("CPU fallback returned blank output", result.text.isNotBlank())
            assertEquals("Runtime status did not record the actual CPU", AiProcessor.CPU, service.runtimeStatus().processor)
            assertEquals("UI process changed during worker recovery", mainPid, Process.myPid())
        }
    }

    /** The existing control message kills only the worker process, never the app process. */
    @Test
    fun workerExitDoesNotTerminateTheAppProcess() {
        requireModelTestOptIn()
        withForegroundActivity {
            val context = targetContext()
            val mainPid = Process.myPid()
            val connected = CountDownLatch(1)
            val workerDied = CountDownLatch(1)
            var binder: IBinder? = null
            val recipient = IBinder.DeathRecipient { workerDied.countDown() }
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    binder = service
                    service.linkToDeath(recipient, 0)
                    connected.countDown()
                }
                override fun onServiceDisconnected(name: ComponentName) {
                    workerDied.countDown()
                }
            }
            val bound = context.bindService(
                Intent(context, LocalAiProcessService::class.java),
                connection,
                Context.BIND_AUTO_CREATE
            )
            try {
                assertTrue(bound)
                assertTrue("CPU worker did not bind", connected.await(15, TimeUnit.SECONDS))
                Messenger(requireNotNull(binder)).send(Message.obtain(null, LocalAiIpc.CANCEL_AND_EXIT))
                assertTrue("CPU worker did not exit", workerDied.await(10, TimeUnit.SECONDS))
                assertEquals("App process was affected by worker exit", mainPid, Process.myPid())
            } finally {
                if (bound) context.unbindService(connection)
                binder?.let { runCatching { it.unlinkToDeath(recipient, 0) } }
            }
        }
    }

    private fun requireOptInModel(): File {
        requireModelTestOptIn()
        val model = OfflineAiModelManager(targetContext()).modelFile()
        assertNotNull(
            "Import a valid GGUF model through the app before running optional model tests",
            model
        )
        return requireNotNull(model)
    }

    private fun requireModelTestOptIn() {
        assumeTrue(
            "Enable the real-model tests with leap8RunModelTests=true",
            InstrumentationRegistry.getArguments().getString("leap8RunModelTests") == "true"
        )
    }

    private fun targetContext(): Context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun shell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation()
            .uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor)
            .bufferedReader().use { it.readText().trim() }
    }

    private inline fun withForegroundActivity(block: () -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val component = "${targetContext().packageName}/.MainActivity"
        val shellOutput = shell("am start -W -n $component")
        assertTrue("Could not bring the app to foreground: $shellOutput", !shellOutput.contains("Error:"))
        instrumentation.waitForIdleSync()
        // Keep MainActivity visible throughout the suite. Some vivo builds freeze an isolated
        // inference process when instrumentation has no foreground Activity.
        block()
    }

    private fun verifyWorker(serviceClass: Class<out LocalAiProcessService>, backend: String) {
        val context = targetContext()
        val connected = CountDownLatch(1)
        val workerDied = CountDownLatch(1)
        val target = AtomicReference<Messenger?>(null)
        val binder = AtomicReference<IBinder?>(null)
        val recipient = IBinder.DeathRecipient { workerDied.countDown() }
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                binder.set(service)
                runCatching { service.linkToDeath(recipient, 0) }
                    .onFailure { workerDied.countDown() }
                target.set(Messenger(service))
                connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) { workerDied.countDown() }
            override fun onBindingDied(name: ComponentName) { workerDied.countDown() }
        }
        val replies = LinkedBlockingQueue<Pair<Int, Bundle>>()
        val replyThread = HandlerThread("Leap8-Ipc-Regression").apply { start() }
        val reply = Messenger(Handler(replyThread.looper) { message ->
            replies.offer(message.what to Bundle(message.data))
            true
        })
        val bound = context.bindService(Intent(context, serviceClass), connection, Context.BIND_AUTO_CREATE)
        try {
            assertTrue(bound)
            assertTrue("$backend did not bind", connected.await(15, TimeUnit.SECONDS))
            repeat(20) {
                val id = UUID.randomUUID().toString()
                requireNotNull(target.get()).send(Message.obtain(null, LocalAiIpc.GENERATE).apply {
                    replyTo = reply
                    data = Bundle().apply {
                        putString(LocalAiIpc.REQUEST_ID, id)
                        putString(LocalAiIpc.BACKEND, backend)
                        putString(LocalAiIpc.MODEL_PATH, "")
                        putString(LocalAiIpc.SYSTEM_PROMPT, "test")
                        putString(LocalAiIpc.USER_PROMPT, "中文与 emoji 😀")
                        putInt(LocalAiIpc.MAX_TOKENS, 32)
                        putFloat(LocalAiIpc.TEMPERATURE, 0f)
                    }
                })
                val response = replies.poll(10, TimeUnit.SECONDS)
                assertNotNull("$backend request was lost after Message recycling", response)
                assertEquals(LocalAiIpc.ERROR, response!!.first)
                assertEquals(id, response.second.getString(LocalAiIpc.REQUEST_ID))
                assertTrue(response.second.getString(LocalAiIpc.MESSAGE).orEmpty().isNotBlank())
            }
        } finally {
            // A previous test used to unbind and send EXIT without waiting. The next model test
            // could then bind the same service record while Android was still tearing it down.
            if (binder.get() != null) {
                runCatching { target.get()?.send(Message.obtain(null, LocalAiIpc.CANCEL_AND_EXIT)) }
                workerDied.await(10, TimeUnit.SECONDS)
            }
            if (bound) context.unbindService(connection)
            binder.get()?.let { runCatching { it.unlinkToDeath(recipient, 0) } }
            replyThread.quitSafely()
        }
    }

    private companion object {
        const val SHORT_SYSTEM_PROMPT = "Answer briefly in English."
        const val SHORT_USER_PROMPT = "Write one simple sentence about studying English."
    }
}
