package com.leap8.offline.diagnostics

import android.content.Context
import android.os.SystemClock
import com.leap8.offline.ai.AiTextGeneration
import com.leap8.offline.domain.AiGenerationProgress
import com.leap8.offline.domain.AiProvider
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** This singleton is lazy-created on IO with the AI router/ViewModel, never at Activity creation. */
class DeveloperTelemetry(context: Context) {
    private val preferences = context.getSharedPreferences("developer_telemetry", Context.MODE_PRIVATE)
    private val store = TelemetryStore(context)
    private var restoreFailed = false
    private val restored = runCatching { store.load() }.getOrElse { restoreFailed = true; emptyList() }
    private val ledger = InvocationLedger(preferences.getBoolean("enabled", true), restored)
    val state = ledger.state
    private val sampler = AndroidPerformanceSampler(context)
    private val io = Executors.newSingleThreadScheduledExecutor { work -> Thread(work, "Leap8-Performance").apply { isDaemon = true } }
    private val sessions = mutableMapOf<String, Long>()
    private var ticker: ScheduledFuture<*>? = null
    private var ticks = 0

    init { if (restoreFailed) ledger.storageWarning() }

    fun setEnabled(enabled: Boolean) {
        preferences.edit().putBoolean("enabled", enabled).apply()
        ledger.setEnabled(enabled)
        synchronized(sessions) {
            if (!enabled) { sessions.clear(); ticker?.cancel(false); ticker = null }
        }
        persist()
    }
    fun clearHistory() { ledger.clearHistory(); persist() }

    fun capture(mode: InvocationMode, provider: AiProvider, model: String, temperature: Float?,
                onProgress: (AiGenerationProgress) -> Unit,
                block: ((AiGenerationProgress) -> Unit) -> AiTextGeneration): AiTextGeneration {
        val id = UUID.randomUUID().toString()
        val started = SystemClock.elapsedRealtime()
        val record = InvocationRecord(id, System.currentTimeMillis(), mode, provider,
            model = model, samplingTemperature = temperature?.takeIf { it.isFinite() })
        if (!ledger.begin(record)) return block(onProgress)
        synchronized(sessions) {
            if (state.value.enabled && state.value.active.any { it.id == id }) {
                sessions[id] = started
                if (ticker == null) ticker = io.scheduleAtFixedRate({ sampleAll() }, 0, 1, TimeUnit.SECONDS)
            }
        }
        persist()
        try {
            val result = block { progress ->
                ledger.progress(id, progress, SystemClock.elapsedRealtime() - started)
                onProgress(progress)
            }
            sampleOne(id, started)
            ledger.finish(id, InvocationOutcome.SUCCESS, SystemClock.elapsedRealtime() - started,
                result.processor, result.generatedTokens.takeIf { provider != AiProvider.LOCAL })
            return result
        } catch (error: Throwable) {
            val cancelled = error is InterruptedException || error is CancellationException
            ledger.finish(id, if (cancelled) InvocationOutcome.CANCELLED else InvocationOutcome.FAILED,
                SystemClock.elapsedRealtime() - started)
            throw error // No messages or stack traces are persisted.
        } finally {
            synchronized(sessions) {
                sessions.remove(id)
                if (sessions.isEmpty()) { ticker?.cancel(false); ticker = null }
            }
            persist()
        }
    }

    private fun sampleAll() {
        val copy = synchronized(sessions) { sessions.toMap() }
        copy.forEach { (id, started) -> sampleOne(id, started) }
        if (++ticks % 5 == 0) saveNow()
    }
    private fun sampleOne(id: String, started: Long) {
        runCatching {
            val active = state.value.active.firstOrNull { it.id == id } ?: return
            ledger.sample(id, sampler.sample(SystemClock.elapsedRealtime() - started,
                active.outputTokens.takeIf { active.tokenSource == LOCAL_TOKENIZER }))
        }
    }
    private fun persist() { io.execute { saveNow() } }
    private fun saveNow() {
        val snapshot = state.value
        runCatching { store.save((snapshot.active + snapshot.recent).take(100)) }
            .onFailure { ledger.storageWarning() }
    }
}
