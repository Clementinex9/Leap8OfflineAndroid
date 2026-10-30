package com.leap8.offline.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class AiProgressPublisherTest {
    @Test
    fun tokenUpdatesAreLimitedButLatestValueAtNextIntervalIsPublished() {
        var now = 0L
        val values = mutableListOf<AiGenerationProgress>()
        val publisher = AiProgressPublisher({ now }, values::add)
        val started = AiGenerationProgress(true, .1f, "生成", processor = AiProcessor.CPU)
        publisher.publish(started)
        repeat(124) {
            now += 1_000_000L
            publisher.publish(started.copy(generatedTokens = it + 1))
        }
        assertEquals(1, values.size)
        now = 125_000_000L
        publisher.publish(started.copy(generatedTokens = 125))
        assertEquals(125, values.last().generatedTokens)
        assertEquals(2, values.size)
    }

    @Test
    fun fallbackStagesAndCompletionAreNeverDelayed() {
        val values = mutableListOf<AiGenerationProgress>()
        val publisher = AiProgressPublisher({ 0L }, values::add)
        val started = AiGenerationProgress(true, .1f, "生成", processor = AiProcessor.GPU)
        publisher.publish(started)
        publisher.publish(started.copy(processor = AiProcessor.CPU))
        publisher.publish(started.copy(stage = "回退量表", processor = AiProcessor.RULES))
        publisher.publish(started.copy(running = false, fraction = 1f))
        publisher.publish(values.last())
        assertEquals(4, values.size)
    }
}
