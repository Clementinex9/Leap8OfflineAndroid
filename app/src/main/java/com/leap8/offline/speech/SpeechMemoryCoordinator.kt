package com.leap8.offline.speech

import android.os.Handler
import android.os.Looper
import java.util.WeakHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal fun interface SpeechMemoryOwner {
    fun setMemoryPaused(paused: Boolean)
}

/** Main-thread-only state, separated from Android dispatch for deterministic unit tests. */
internal class SpeechMemoryLeaseRegistry {
    private val owners = WeakHashMap<SpeechMemoryOwner, Unit>()
    private var leases = 0

    fun register(owner: SpeechMemoryOwner): AutoCloseable {
        check(owner !in owners) { "Speech owner already registered" }
        owners[owner] = Unit
        try {
            if (leases > 0) owner.setMemoryPaused(true)
        } catch (error: Throwable) {
            owners.remove(owner)
            runCatching { owner.setMemoryPaused(false) }
            throw error
        }
        return AutoCloseable { owners.remove(owner) }
    }

    fun acquire(): AutoCloseable {
        leases++
        if (leases == 1) {
            try {
                owners.keys.toList().forEach { it.setMemoryPaused(true) }
            } catch (error: Throwable) {
                leases = 0
                // One failing observer must not leave other screens permanently paused.
                owners.keys.toList().forEach { runCatching { it.setMemoryPaused(false) } }
                throw error
            }
        }
        val released = AtomicBoolean(false)
        return AutoCloseable {
            if (released.compareAndSet(false, true)) {
                check(leases > 0)
                leases--
                if (leases == 0) owners.keys.toList().forEach { runCatching { it.setMemoryPaused(false) } }
            }
        }
    }
}

/**
 * Temporarily releases only this app's already-created speech clients for a local AI request.
 * Native destruction stays on the speech service's worker thread; callers must recheck actual
 * system memory rather than assume that closing a binding immediately frees a fixed amount.
 */
object SpeechMemoryCoordinator {
    private val main = Handler(Looper.getMainLooper())
    private val registry = SpeechMemoryLeaseRegistry()

    internal fun register(owner: SpeechMemoryOwner): AutoCloseable {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Speech owners must register on the main thread" }
        val registration = registry.register(owner)
        return AutoCloseable {
            if (Looper.myLooper() == Looper.getMainLooper()) registration.close()
            else main.post { registration.close() }
        }
    }

    /** Background callers wait at most two seconds; cancellation cannot leak a pause lease. */
    fun acquirePause(): AutoCloseable {
        if (Looper.myLooper() == Looper.getMainLooper()) return mainLease(registry.acquire())
        val abandoned = AtomicBoolean(false)
        val acquired = AtomicReference<AutoCloseable?>(null)
        val error = AtomicReference<Throwable?>(null)
        val done = CountDownLatch(1)
        val task = Runnable {
            try {
                if (!abandoned.get()) {
                    acquired.set(registry.acquire())
                    if (abandoned.get()) acquired.getAndSet(null)?.close()
                }
            } catch (failure: Throwable) {
                error.set(failure)
            } finally { done.countDown() }
        }
        check(main.post(task)) { "无法请求释放语音内存" }
        try {
            check(done.await(2, TimeUnit.SECONDS)) { "释放语音内存超时，请稍后重试" }
            error.get()?.let { throw it }
            return mainLease(checkNotNull(acquired.getAndSet(null)))
        } catch (failure: Throwable) {
            abandoned.set(true)
            main.removeCallbacks(task)
            acquired.getAndSet(null)?.let { main.post { it.close() } }
            throw failure
        }
    }

    private fun mainLease(lease: AutoCloseable): AutoCloseable {
        val closed = AtomicBoolean(false)
        return AutoCloseable {
            if (closed.compareAndSet(false, true)) {
                if (Looper.myLooper() == Looper.getMainLooper()) lease.close()
                else main.post { lease.close() }
            }
        }
    }
}
