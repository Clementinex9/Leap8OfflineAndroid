package com.leap8.offline.speech

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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Exercises the real Kokoro model, synthesis and AudioTrack playback without a GGUF model. */
@RunWith(AndroidJUnit4::class)
class NaturalVoiceProcessIpcTest {
    @Test
    fun voicePreferencesAreSharedAcrossScreenInstances() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { verifySharedVoicePreferences() }
    }

    private fun verifySharedVoicePreferences() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val firstScreen = NaturalVoice(context) { _, _ -> }
        val originalVoice = firstScreen.currentVoiceId
        val originalSpeed = firstScreen.currentSpeed
        var secondScreen: NaturalVoice? = null
        try {
            firstScreen.configure("bf_emma", .85f)
            secondScreen = NaturalVoice(context) { _, _ -> }
            assertEquals("bf_emma", secondScreen.currentVoiceId)
            assertEquals(.85f, secondScreen.currentSpeed, .001f)
            secondScreen.configure("am_michael", 1.1f)
            assertEquals("am_michael", firstScreen.currentVoiceId)
            assertEquals(1.1f, firstScreen.currentSpeed, .001f)
        } finally {
            firstScreen.configure(originalVoice, originalSpeed)
            firstScreen.shutdown()
            secondScreen?.shutdown()
        }
    }

    @Test
    fun kokoroSpeaksAWordWithoutTerminatingTheAppProcess() {
        runNeuralIpcTest()
    }

    @Test
    fun kokoroSpeaksBritishEnglishAtTheSelectedSpeed() {
        runNeuralIpcTest(
            voiceId = "bf_emma",
            text = "Good pronunciation makes learning English more enjoyable.",
            speed = .85f
        )
    }

    /** Every selectable embedding must survive real native phonemization and playback. */
    @Test
    fun everySelectableVoiceSynthesizesAndPlaysASentence() {
        VoiceCatalog.voices.forEachIndexed { index, voice ->
            runNeuralIpcTest(
                voiceId = voice.id,
                text = "A quokka waits near the theatre.",
                speed = if (index % 2 == 0) .7f else 1.3f
            )
        }
    }

    @Test
    fun britishVoicePlaysAMultiSentenceListeningPassage() {
        runNeuralIpcTest(
            voiceId = "bm_george",
            text = "Students often improve when they listen carefully and practise every day. " +
                "The theatre near our university provides a useful place to hear different accents. " +
                "After each performance, we discuss the characters and explain why their choices matter.",
            speed = 1.3f
        )
    }

    private fun runNeuralIpcTest(
        voiceId: String = VoiceCatalog.DEFAULT_VOICE_ID,
        text: String = "hello",
        speed: Float = 1f
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // vivo may freeze a native service when no Activity is visible. Make each test
        // independently runnable, including when the British-voice case runs first.
        val launchOutput = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(
                "am start -W -n com.leap8.offline/.MainActivity"
            )
        ).use { it.bufferedReader().readText() }
        assertTrue("MainActivity launch failed: $launchOutput", !launchOutput.contains("Error", ignoreCase = true))
        instrumentation.waitForIdleSync()
        val context = instrumentation.targetContext
        val mainPid = Process.myPid()
        val connected = CountDownLatch(1)
        val readyOrFailed = CountDownLatch(1)
        val playedOrFailed = CountDownLatch(1)
        val failure = AtomicReference<String?>(null)
        val service = AtomicReference<Messenger?>(null)
        val replyThread = HandlerThread("Leap8-Kokoro-Ipc-Test").apply { start() }
        val reply = Messenger(Handler(replyThread.looper) { message ->
            when (message.what) {
                NaturalVoiceIpc.STATUS -> {
                    val data = message.data
                    if (data.getBoolean(NaturalVoiceIpc.FAILED)) {
                        failure.compareAndSet(null, data.getString(NaturalVoiceIpc.MESSAGE) ?: "Kokoro failed")
                        readyOrFailed.countDown()
                        playedOrFailed.countDown()
                    } else if (data.getBoolean(NaturalVoiceIpc.READY)) {
                        readyOrFailed.countDown()
                    }
                }
                NaturalVoiceIpc.PLAYED -> {
                    if (message.data.getString(NaturalVoiceIpc.VOICE_ID) != voiceId ||
                        kotlin.math.abs(message.data.getFloat(NaturalVoiceIpc.SPEED) - speed) > .001f ||
                        message.data.getInt("sample_rate") != 24_000 ||
                        message.data.getInt("sample_count") <= 0
                    ) failure.compareAndSet(null, "Kokoro did not synthesize the requested voice at 24 kHz")
                    playedOrFailed.countDown()
                }
            }
            true
        })
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                service.set(Messenger(binder))
                connected.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName) = reportDisconnect()
            override fun onBindingDied(name: ComponentName) = reportDisconnect()
            override fun onNullBinding(name: ComponentName) = reportDisconnect()

            private fun reportDisconnect() {
                failure.compareAndSet(null, "Kokoro voice worker disconnected")
                connected.countDown()
                readyOrFailed.countDown()
                playedOrFailed.countDown()
            }
        }

        var bound = false
        try {
            bound = context.bindService(
                Intent(context, NaturalVoiceProcessService::class.java),
                connection,
                Context.BIND_AUTO_CREATE
            )
            assertTrue("Kokoro service could not be bound", bound)
            assertTrue("Kokoro service did not connect", connected.await(15, TimeUnit.SECONDS))
            val target = requireNotNull(service.get()) { failure.get() ?: "Kokoro service binder is missing" }

            target.send(Message.obtain(null, NaturalVoiceIpc.INIT).apply { replyTo = reply })
            assertTrue("Kokoro initialization timed out", readyOrFailed.await(90, TimeUnit.SECONDS))
            assertNull("Kokoro failed during initialization", failure.get())

            target.send(Message.obtain(null, NaturalVoiceIpc.SPEAK).apply {
                replyTo = reply
                data = Bundle().apply {
                    putString(NaturalVoiceIpc.TEXT, text)
                    putBoolean(NaturalVoiceIpc.SLOWER, false)
                    putString(NaturalVoiceIpc.VOICE_ID, voiceId)
                    putFloat(NaturalVoiceIpc.SPEED, speed)
                }
            })
            assertTrue("Kokoro did not finish synthesis and playback", playedOrFailed.await(60, TimeUnit.SECONDS))
            assertNull("Kokoro failed while speaking", failure.get())
            assertEquals("The app process changed during Kokoro playback", mainPid, Process.myPid())
        } finally {
            runCatching { service.get()?.send(Message.obtain(null, NaturalVoiceIpc.STOP)) }
            if (bound) runCatching { context.unbindService(connection) }
            replyThread.quitSafely()
        }
    }
}
