package com.leap8.offline.speech

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class VoiceRequestStateTest {
    @Test
    fun newerSpeechAndStopInvalidateOldSynthesis() {
        val requests = VoiceRequestState()
        val old = requests.next()!!
        val current = requests.next()!!
        assertFalse(requests.isCurrent(old))
        assertTrue(requests.isCurrent(current))
        requests.cancel()
        assertFalse(requests.isCurrent(current))
        assertNotNull(requests.next())
    }

    @Test
    fun destructionDuringGenerationPreventsLatePlaybackAndNewWork() {
        val requests = VoiceRequestState()
        val request = requests.next()!!
        val enteredNative = CountDownLatch(1)
        val finishNative = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val played = worker.submit<Boolean> {
                enteredNative.countDown()
                check(finishNative.await(5, TimeUnit.SECONDS))
                requests.isCurrent(request)
            }
            assertTrue(enteredNative.await(5, TimeUnit.SECONDS))
            requests.close()
            assertNull(requests.next())
            finishNative.countDown()
            assertFalse(played.get(5, TimeUnit.SECONDS))
            requests.cancel()
            assertFalse(requests.isCurrent(request))
        } finally {
            finishNative.countDown()
            worker.shutdownNow()
        }
    }
}
