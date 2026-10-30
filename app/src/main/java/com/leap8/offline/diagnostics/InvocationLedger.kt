package com.leap8.offline.diagnostics

import com.leap8.offline.domain.AiGenerationProgress
import com.leap8.offline.domain.AiProcessor
import com.leap8.offline.domain.AiProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal const val LOCAL_TOKENIZER = "LOCAL_TOKENIZER"
internal const val API_COMPLETION_USAGE = "API_COMPLETION_USAGE"
internal const val UNAVAILABLE = "UNAVAILABLE"

/** Metadata only. Never accepts prompts, responses, credentials or Throwable messages. */
internal class InvocationLedger(enabled: Boolean, history: List<InvocationRecord>) {
    private val mutable = MutableStateFlow(DeveloperTelemetrySnapshot(enabled, recent = history.take(100)))
    val state = mutable.asStateFlow()

    @Synchronized fun begin(record: InvocationRecord): Boolean {
        if (!mutable.value.enabled) return false
        // A bounded active set protects against a buggy caller leaking uncompleted sessions.
        if (mutable.value.active.size >= 8) return false
        mutable.value = mutable.value.copy(active = mutable.value.active + record.copy(model = safeModelLabel(record.model)))
        return true
    }

    @Synchronized fun progress(id: String, progress: AiGenerationProgress, elapsedMs: Long) = update(id) { old ->
        val processor = progress.processor ?: old.processor
        val changedBackend = processor != old.processor
        val previous = if (changedBackend) old.copy(inputTokens = null, outputTokens = null, decodeMs = null, tokenSource = UNAVAILABLE) else old
        val route = if (processor == null || old.route.lastOrNull() == processor) old.route else (old.route + processor).takeLast(16)
        val local = progress.tokenSource == LOCAL_TOKENIZER
        old.copy(
            processor = processor, route = route, elapsedMs = elapsedMs.coerceAtLeast(old.elapsedMs),
            inputTokens = if (local) progress.inputTokens?.takeIf { it >= 0 } ?: previous.inputTokens else previous.inputTokens,
            // Partial counts are intentionally NOT accumulated or called final usage on failure.
            outputTokens = if (local) progress.outputTokens?.takeIf { it >= 0 } ?: previous.outputTokens else previous.outputTokens,
            decodeMs = if (local) progress.decodeMs?.takeIf { it >= 0 } ?: previous.decodeMs else previous.decodeMs,
            tokenSource = if (local) LOCAL_TOKENIZER else previous.tokenSource,
            firstProgressMs = old.firstProgressMs ?: elapsedMs.takeIf { local && progress.generatedTokens > 0 }
        )
    }

    @Synchronized fun sample(id: String, sample: PerformanceSample) = update(id) { old ->
        old.copy(elapsedMs = maxOf(old.elapsedMs, sample.elapsedMs), samples = compactSamples(old.samples + sample))
    }

    @Synchronized fun finish(id: String, outcome: InvocationOutcome, elapsedMs: Long, processor: AiProcessor? = null,
                             apiCompletionTokens: Int? = null) {
        val old = mutable.value.active.firstOrNull { it.id == id } ?: return
        val isApi = old.provider != AiProvider.LOCAL
        val nativeComplete = old.inputTokens != null && old.outputTokens != null && old.decodeMs != null
        val completed = old.copy(
            outcome = outcome, elapsedMs = maxOf(old.elapsedMs, elapsedMs), processor = processor ?: old.processor,
            inputTokens = old.inputTokens.takeIf { !isApi && nativeComplete },
            outputTokens = if (isApi && outcome == InvocationOutcome.SUCCESS) apiCompletionTokens?.takeIf { it > 0 }
                else old.outputTokens.takeIf { nativeComplete },
            decodeMs = old.decodeMs.takeIf { nativeComplete },
            tokenSource = if (isApi && apiCompletionTokens != null && apiCompletionTokens > 0 && outcome == InvocationOutcome.SUCCESS)
                API_COMPLETION_USAGE else if (nativeComplete) old.tokenSource else UNAVAILABLE
        )
        mutable.value = mutable.value.copy(active = mutable.value.active.filterNot { it.id == id },
            recent = (listOf(completed) + mutable.value.recent).take(100))
    }

    @Synchronized fun setEnabled(enabled: Boolean) {
        // Disabling discards in-flight monitoring immediately, not the learner's actual request.
        mutable.value = mutable.value.copy(enabled = enabled, active = if (enabled) mutable.value.active else emptyList())
    }
    @Synchronized fun clearHistory() { mutable.value = mutable.value.copy(recent = emptyList()) }
    @Synchronized fun storageWarning() { mutable.value = mutable.value.copy(storageWarning = true) }
    private fun update(id: String, change: (InvocationRecord) -> InvocationRecord) {
        if (mutable.value.active.none { it.id == id }) return
        mutable.value = mutable.value.copy(active = mutable.value.active.map { if (it.id == id) change(it) else it })
    }
}

internal fun safeModelLabel(model: String): String = model
    .replace(Regex("(?i)(sk[-_]|bearer\\s+)[A-Za-z0-9._-]+"), "[已隐藏]")
    .filterNot { it.isISOControl() }.take(120)
