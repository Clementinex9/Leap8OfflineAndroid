package com.leap8.offline.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.leap8.offline.ai.ggufArchitecture
import com.leap8.offline.data.LearningRepository
import com.leap8.offline.data.NativeWordbookFilter
import com.leap8.offline.data.SeedContent
import com.leap8.offline.data.VocabularyCatalog
import com.leap8.offline.domain.LocalRubricEssayScorer
import com.leap8.offline.domain.ReviewRating
import com.leap8.offline.domain.SpacedRepetition
import com.leap8.offline.domain.WordProgress
import com.leap8.offline.speech.PronunciationAnalyzer
import com.leap8.offline.speech.VoiceAudioGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class Leap8CoreNativeInstrumentedTest {
    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun nativeCoreLibraryWorksEndToEndOnDevice() {
        assertTrue(
            "libleap8_core.so failed to load on device: ${Leap8CoreNative.loadError}",
            Leap8CoreNative.isNativeLoaded
        )

        val catalog = VocabularyCatalog(targetContext)
        assertEquals(5048, catalog.cards.size)

        val repo = LearningRepository(targetContext)
        val snapshot = repo.wordbook()
        assertEquals(5048, snapshot.total)

        val t0 = System.nanoTime()
        val matches = NativeWordbookFilter.filter(snapshot.entries, "全部", "sustainable")
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000.0
        assertTrue("Expected matches for 'sustainable'", matches.isNotEmpty())
        assertTrue("Expected fast native search (<50ms), took ${elapsedMs}ms", elapsedMs < 50.0)

        // Exercise ARM64 NEON PCM validator on 100k samples
        val samples = FloatArray(100_000) { idx -> 0.5f * sin(idx * 0.02).toFloat() }
        VoiceAudioGuard.validate(samples)
        assertThrows(IllegalStateException::class.java) {
            VoiceAudioGuard.validate(FloatArray(1024) { 1.0f })
        }

        val score = LocalRubricEssayScorer().score(
            task = SeedContent.essayTasks.first(),
            essay = "Technology enhances learning access and fosters collaboration across classrooms. " +
                "Consequently, teachers can personalize feedback effectively."
        )
        assertEquals(4, score.criteria.size)
        assertTrue(score.wordCount > 10)

        val reviewed = SpacedRepetition.review(WordProgress("test-word"), ReviewRating.GOOD, 20000L)
        assertEquals(1, reviewed.repetitions)
        assertEquals(1, reviewed.intervalDays)

        val pron = PronunciationAnalyzer.compare("Education unlocks opportunity", "education unlocks")
        assertTrue(pron.accuracy in 50..80)

        val probeFile = File(targetContext.cacheDir, "device-probe.gguf")
        try {
            probeFile.writeBytes(minimalGguf("qwen35"))
            assertEquals("qwen35", ggufArchitecture(probeFile))
            probeFile.writeBytes(byteArrayOf(1, 2, 3, 4))
            assertNull(ggufArchitecture(probeFile))
        } finally {
            probeFile.delete()
        }
    }

    private fun minimalGguf(arch: String): ByteArray {
        val out = ByteArrayOutputStream()
        fun u32(v: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
        fun u64(v: Long) = out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array())
        fun str(s: String) {
            val b = s.toByteArray(StandardCharsets.UTF_8)
            u64(b.size.toLong())
            out.write(b)
        }
        out.write("GGUF".toByteArray(StandardCharsets.US_ASCII))
        u32(3)
        u64(0L)
        u64(1L)
        str("general.architecture")
        u32(8)
        str(arch)
        return out.toByteArray()
    }
}
