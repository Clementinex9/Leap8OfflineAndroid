package com.leap8.offline.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechMemoryCoordinatorTest {
    private class Owner : SpeechMemoryOwner {
        val states = mutableListOf<Boolean>()
        var failPause = false
        var failResume = false
        override fun setMemoryPaused(paused: Boolean) {
            states.add(paused)
            if ((paused && failPause) || (!paused && failResume)) error("observer failed")
        }
    }

    @Test
    fun noOwnersCanBePausedAndReleasedWithoutCreatingAnySpeechClient() {
        val registry = SpeechMemoryLeaseRegistry()
        registry.acquire().close()
        val owner = Owner()
        registry.register(owner)
        assertTrue(owner.states.isEmpty())
    }

    @Test
    fun nestedLeasesPauseOnceAndResumeOnlyWhenLastLeaseEnds() {
        val registry = SpeechMemoryLeaseRegistry()
        val owner = Owner()
        registry.register(owner)
        val first = registry.acquire()
        val second = registry.acquire()
        assertEquals(listOf(true), owner.states)
        first.close()
        first.close()
        assertEquals(listOf(true), owner.states)
        second.close()
        assertEquals(listOf(true, false), owner.states)
    }

    @Test
    fun newScreenIsPausedImmediatelyAndCanResumeLazily() {
        val registry = SpeechMemoryLeaseRegistry()
        val lease = registry.acquire()
        val owner = Owner()
        registry.register(owner)
        assertEquals(listOf(true), owner.states)
        lease.close()
        assertEquals(listOf(true, false), owner.states)
    }

    @Test
    fun disposedScreenIsNotReactivatedWhenLeaseEnds() {
        val registry = SpeechMemoryLeaseRegistry()
        val owner = Owner()
        val registration = registry.register(owner)
        val lease = registry.acquire()
        registration.close()
        lease.close()
        assertEquals(listOf(true), owner.states)
    }

    @Test
    fun failingPauseRollsBackEveryOwnerAndAllowsNextAttempt() {
        val registry = SpeechMemoryLeaseRegistry()
        val owner = Owner()
        val broken = Owner().apply { failPause = true }
        registry.register(owner)
        registry.register(broken)
        var threw = false
        try { registry.acquire() } catch (_: IllegalStateException) { threw = true }
        assertTrue(threw)
        assertFalse(owner.states.last())
        assertFalse(broken.states.last())
        broken.failPause = false
        registry.acquire().close()
        assertFalse(owner.states.last())
        assertFalse(broken.states.last())
    }

    @Test
    fun failingResumeDoesNotPreventOtherOwnersFromResuming() {
        val registry = SpeechMemoryLeaseRegistry()
        val broken = Owner().apply { failResume = true }
        val owner = Owner()
        registry.register(broken)
        registry.register(owner)
        registry.acquire().close()
        assertEquals(listOf(true, false), owner.states)
        assertEquals(listOf(true, false), broken.states)
    }

    @Test
    fun registrationFailureDoesNotLeakAnOwnerOrChangeExistingLease() {
        val registry = SpeechMemoryLeaseRegistry()
        val lease = registry.acquire()
        val broken = Owner().apply { failPause = true }
        var threw = false
        try { registry.register(broken) } catch (_: IllegalStateException) { threw = true }
        assertTrue(threw)
        val owner = Owner()
        registry.register(owner)
        assertEquals(listOf(true), owner.states)
        lease.close()
        assertEquals(listOf(true, false), owner.states)
        assertEquals(listOf(true, false), broken.states)
    }
}
