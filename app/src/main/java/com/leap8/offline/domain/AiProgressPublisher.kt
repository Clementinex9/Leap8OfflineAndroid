package com.leap8.offline.domain

/** Bounds token-only UI invalidations, while preserving engine/stage changes immediately. */
internal class AiProgressPublisher(
    private val clockNanos: () -> Long = System::nanoTime,
    private val publishValue: (AiGenerationProgress) -> Unit
) {
    private var previous: AiGenerationProgress? = null
    private var lastPublishedNanos = 0L

    @Synchronized
    fun publish(progress: AiGenerationProgress) {
        val before = previous
        if (before == progress) return
        val now = clockNanos()
        val transition = before == null || before.running != progress.running ||
            before.stage != progress.stage || before.processor != progress.processor ||
            progress.fraction >= 1f
        if (!transition && now - lastPublishedNanos < MIN_INTERVAL_NANOS) return
        previous = progress
        lastPublishedNanos = now
        publishValue(progress)
    }

    private companion object {
        const val MIN_INTERVAL_NANOS = 125_000_000L
    }
}
