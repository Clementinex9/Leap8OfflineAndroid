package com.leap8.offline.speech

import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Exercises real main-looper dispatch without allocating a speech or AI model. */
@RunWith(AndroidJUnit4::class)
class SpeechMemoryCoordinatorInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private class Owner : SpeechMemoryOwner {
        val states = mutableListOf<Boolean>()
        override fun setMemoryPaused(paused: Boolean) {
            check(Looper.myLooper() == Looper.getMainLooper())
            states.add(paused)
        }
    }

    @Test
    fun backgroundLeasePausesExistingAndNewOwnersAndResumesOnMain() {
        val pool = Executors.newSingleThreadExecutor()
        val existing = Owner()
        val createdDuringPause = Owner()
        var firstRegistration: AutoCloseable? = null
        var secondRegistration: AutoCloseable? = null
        var lease: AutoCloseable? = null
        try {
            instrumentation.runOnMainSync { firstRegistration = SpeechMemoryCoordinator.register(existing) }
            lease = pool.submit<AutoCloseable> { SpeechMemoryCoordinator.acquirePause() }.get(5, TimeUnit.SECONDS)
            instrumentation.runOnMainSync {
                assertEquals(listOf(true), existing.states)
                secondRegistration = SpeechMemoryCoordinator.register(createdDuringPause)
                assertEquals(listOf(true), createdDuringPause.states)
            }
            val acquired = checkNotNull(lease)
            pool.submit { acquired.close() }.get(5, TimeUnit.SECONDS)
            instrumentation.runOnMainSync {
                assertEquals(listOf(true, false), existing.states)
                assertEquals(listOf(true, false), createdDuringPause.states)
            }
        } finally {
            lease?.close()
            instrumentation.runOnMainSync {
                firstRegistration?.close()
                secondRegistration?.close()
            }
            pool.shutdownNow()
        }
    }

    @Test
    fun closeStillResumesWhenCallerAlreadyInterrupted() {
        val pool = Executors.newSingleThreadExecutor()
        val owner = Owner()
        var registration: AutoCloseable? = null
        var lease: AutoCloseable? = null
        try {
            instrumentation.runOnMainSync { registration = SpeechMemoryCoordinator.register(owner) }
            lease = pool.submit<AutoCloseable> { SpeechMemoryCoordinator.acquirePause() }.get(5, TimeUnit.SECONDS)
            val acquired = checkNotNull(lease)
            val interruptPreserved = pool.submit<Boolean> {
                Thread.currentThread().interrupt()
                try {
                    acquired.close()
                    Thread.currentThread().isInterrupted
                } finally { Thread.interrupted() }
            }.get(5, TimeUnit.SECONDS)
            assertTrue(interruptPreserved)
            instrumentation.runOnMainSync { assertEquals(listOf(true, false), owner.states) }
        } finally {
            lease?.close()
            instrumentation.runOnMainSync { registration?.close() }
            pool.shutdownNow()
        }
    }

    @Test
    fun interruptedPendingAcquireCannotLeaveVoicePermanentlyPaused() {
        val owner = Owner()
        var registration: AutoCloseable? = null
        val mainEntered = CountDownLatch(1)
        val releaseMain = CountDownLatch(1)
        val callerEntered = CountDownLatch(1)
        val interrupted = AtomicBoolean(false)
        val caller = Thread {
            callerEntered.countDown()
            try { SpeechMemoryCoordinator.acquirePause().close() }
            catch (_: InterruptedException) { interrupted.set(true) }
        }
        try {
            instrumentation.runOnMainSync { registration = SpeechMemoryCoordinator.register(owner) }
            Handler(Looper.getMainLooper()).post {
                mainEntered.countDown()
                // A short deterministic blocked dispatch, not a native model or real memory flood.
                releaseMain.await(2, TimeUnit.SECONDS)
            }
            assertTrue(mainEntered.await(1, TimeUnit.SECONDS))
            caller.start()
            assertTrue(callerEntered.await(1, TimeUnit.SECONDS))
            caller.interrupt()
            caller.join(1000)
            assertFalse(caller.isAlive)
            assertTrue(interrupted.get())
            releaseMain.countDown()
            instrumentation.runOnMainSync { assertTrue(owner.states.isEmpty()) }
            // A subsequent normal lease works: cancellation did not poison registry state.
            instrumentation.runOnMainSync {
                SpeechMemoryCoordinator.acquirePause().close()
                assertEquals(listOf(true, false), owner.states)
            }
        } finally {
            releaseMain.countDown()
            caller.interrupt()
            caller.join(1000)
            instrumentation.runOnMainSync { registration?.close() }
        }
    }
}
