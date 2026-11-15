package com.leap8.offline.core

import com.leap8.offline.data.SeedContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.sin

class Leap8CoreNativeTest {
    @Test
    fun nativeLibraryLoadsCleanly() {
        assertTrue(
            "Expected leap8_core host library to load: ${Leap8CoreNative.loadError}",
            Leap8CoreNative.isNativeLoaded
        )
        assertNull(Leap8CoreNative.loadError)
    }

    @Test
    fun vocabularyInflateSyncAndFilter() {
        val assetFile = listOf(
            File("src/main/assets/datasets/ielts_vocabulary.tsv.bin"),
            File("app/src/main/assets/datasets/ielts_vocabulary.tsv.bin")
        ).first { it.isFile }
        val gzipBytes = assetFile.readBytes()
        val editorial = SeedContent.vocabulary.map { card ->
            Leap8CoreNative.NativeVocabCard(
                id = card.id,
                word = card.word,
                phonetic = card.phonetic,
                partOfSpeech = card.partOfSpeech,
                meaningZh = card.meaningZh,
                meaningZhTw = card.meaningZhTw,
                example = card.example,
                exampleZh = card.exampleZh,
                roots = card.roots,
                collocations = card.collocations,
                level = card.level,
                topic = card.topic
            )
        }
        val catalog = Leap8CoreNative.loadVocabularyCatalog(gzipBytes, editorial, sortByLevelWord = true)
        assertEquals(5048, catalog.size)

        val sustainable = catalog.firstOrNull { it.word.equals("sustainable", ignoreCase = true) }
        assertNotNull(sustainable)
        val firstId = catalog[0].id
        val secondId = catalog[1].id
        val state = Leap8CoreNative.syncWordbookState(
            progressRows = listOf(
                Leap8CoreNative.NativeProgressInput(firstId, repetitions = 5, intervalDays = 21, dueEpochDay = 20050L),
                Leap8CoreNative.NativeProgressInput(secondId, repetitions = 1, intervalDays = 1, dueEpochDay = 20000L)
            ),
            todayEpochDay = 20000L
        )
        assertEquals(5048, state.sortedCardIndices.size)
        assertEquals(5046, state.newCount)
        assertEquals(1, state.learningCount)
        assertEquals(1, state.masteredCount)
        assertEquals(5047, state.dueCount)

        val enMatches = Leap8CoreNative.filterWordbook(state.generation, filterMode = 0, query = "sustainable")!!
        assertTrue(enMatches.isNotEmpty())
        assertTrue(enMatches.any { catalog[state.sortedCardIndices[it]].word.contains("sustainable", ignoreCase = true) })

        val zhMatches = Leap8CoreNative.filterWordbook(state.generation, filterMode = 0, query = "可持续")!!
        assertTrue(zhMatches.isNotEmpty())

        val masteredOnly = Leap8CoreNative.filterWordbook(state.generation, filterMode = 3, query = "")!!
        assertEquals(1, masteredOnly.size)
        assertEquals(firstId, catalog[state.sortedCardIndices[masteredOnly[0]]].id)

        val learningOnly = Leap8CoreNative.filterWordbook(state.generation, filterMode = 2, query = "")!!
        assertEquals(1, learningOnly.size)
        assertEquals(secondId, catalog[state.sortedCardIndices[learningOnly[0]]].id)

        assertNull(Leap8CoreNative.filterWordbook(state.generation + 99L, filterMode = 0, query = ""))
    }

    @Test
    fun spacedRepetitionAndDashboard() {
        val firstGood = Leap8CoreNative.reviewWord(0, 0, 2.5, 0, quality = 4, todayEpochDay = 100L)
        assertEquals(1, firstGood.repetitions)
        assertEquals(1, firstGood.intervalDays)
        assertEquals(101L, firstGood.dueEpochDay)

        val secondEasy = Leap8CoreNative.reviewWord(
            firstGood.repetitions, firstGood.intervalDays, firstGood.easeFactor, firstGood.lapses,
            quality = 5, todayEpochDay = 101L
        )
        assertEquals(2, secondEasy.repetitions)
        assertEquals(6, secondEasy.intervalDays)
        assertEquals(107L, secondEasy.dueEpochDay)

        val failed = Leap8CoreNative.reviewWord(
            secondEasy.repetitions, secondEasy.intervalDays, secondEasy.easeFactor, secondEasy.lapses,
            quality = 1, todayEpochDay = 107L
        )
        assertEquals(0, failed.repetitions)
        assertEquals(0, failed.intervalDays)
        assertEquals(107L, failed.dueEpochDay)
        assertEquals(1, failed.lapses)

        val metrics = Leap8CoreNative.computeDashboard(
            totalVocabulary = 5048,
            todayEpochDay = 20000L,
            wpReps = intArrayOf(6, 2),
            wpIntervals = intArrayOf(30, 3),
            wpDueDays = longArrayOf(20020L, 19999L),
            sessSkills = intArrayOf(0, 1, 2),
            sessScores = doubleArrayOf(6.5, 7.0, 7.5),
            sessDurations = intArrayOf(120, 600, 300),
            sessDays = longArrayOf(19999L, 20000L, 20000L)
        )
        assertEquals(2, metrics.streakDays)
        assertEquals(15, metrics.todayMinutes)
        assertEquals(2, metrics.learnedWords)
        assertEquals(1, metrics.masteredWords)
        assertEquals(5047, metrics.dueWords)
        assertEquals(3, metrics.completedSessions)
        assertTrue(metrics.estimatedBand in 3.0..8.5)
    }

    @Test
    fun essayScoringAiParsingAndPronunciation() {
        val essay = """
            Although remote work offers flexibility, urban offices remain vital for collaboration.
            Consequently, companies should balance hybrid policies with structured mentorship.

            Furthermore, sustainable transport infrastructure mitigates congestion and enhances productivity.
            However, people is often reluctant to adopt change without feasible incentives.
        """.trimIndent()
        val rubric = Leap8CoreNative.scoreEssayRubric("Discuss remote work and urban offices.", 250, essay)
        assertEquals(4, rubric.criteria.size)
        assertTrue(rubric.wordCount > 30)
        assertTrue(rubric.correctedExamples.any { it.contains("people is → people are") })

        val aiScoreJson = """
            {
              "overallBand": 6.5,
              "criteria": [
                {"name":"任务回应","band":6.3,"evidence":"Covers both views","nextStep":"Add data"},
                {"name":"连贯衔接","band":6.7,"evidence":"Clear paragraphs","nextStep":"Vary linkers"},
                {"name":"词汇资源","band":6.5,"evidence":"Uses feasible","nextStep":"Check collocations"},
                {"name":"语法范围与准确性","band":6.2,"evidence":"Mostly accurate","nextStep":"Fix agreement"}
              ],
              "strengths": ["Clear position", "Balanced"],
              "priorities": ["Subject-verb agreement"],
              "correctedExamples": ["people is -> people are"]
            }
        """.trimIndent()
        val parsedScore = Leap8CoreNative.parseAiEssayScore(aiScoreJson, essay)
        assertEquals(6.5, parsedScore.overallBand, 0.001)
        assertEquals(6.5, parsedScore.criteria[0].band, 0.001)
        assertEquals(2, parsedScore.strengths.size)

        assertThrows(IllegalArgumentException::class.java) {
            Leap8CoreNative.parseAiEssayScore("{\"criteria\":[{\"name\":\"only1\"}]}", essay)
        }

        val revRaw = """
            <think>Analyze sentence</think>
            {
              "revisedText": "People are eager to learn.",
              "summaryZh": "修正主谓一致。",
              "changes": [
                {"before": "People is", "after": "People are", "reasonZh": "复数主语用 are", "category": "语法"},
                {"before": "not in original", "after": "ignored", "reasonZh": "hallucinated", "category": "语法"}
              ]
            }
        """.trimIndent()
        val rev = Leap8CoreNative.parseAiRevision(revRaw, "People is eager to learn.")
        assertEquals("People are eager to learn.", rev.revisedText)
        assertEquals(1, rev.changes.size)
        assertEquals("People is", rev.changes[0].before)

        val pron = Leap8CoreNative.comparePronunciation("Sustainable energy matters", "sustainable energy")
        assertEquals(66, pron.accuracyPercent)
        assertEquals(listOf("sustainable", "energy"), pron.matchedWords)
        assertEquals(listOf("matters"), pron.missingWords)
    }

    @Test
    fun audioGuardGgufSha256MemoryAndTelemetry() {
        val pcm = FloatArray(4096) { i -> 0.4f * sin(i * 0.05).toFloat() }
        Leap8CoreNative.validateAudioPcm(pcm)

        assertThrows(IllegalStateException::class.java) {
            Leap8CoreNative.validateAudioPcm(FloatArray(16) { Float.NaN })
        }
        assertThrows(IllegalStateException::class.java) {
            Leap8CoreNative.validateAudioPcm(FloatArray(64) { 1.0f })
        }
        assertThrows(IllegalStateException::class.java) {
            Leap8CoreNative.validateAudioPcm(FloatArray(64) { 0.0f })
        }

        assertEquals(0.7f, Leap8CoreNative.normalizeVoiceSpeed(0.2f), 0.001f)
        assertEquals(1.0f, Leap8CoreNative.normalizeVoiceSpeed(Float.NaN), 0.001f)
        assertEquals(0.82f, Leap8CoreNative.effectiveVoiceSpeed(1.0f, slower = true), 0.001f)

        val ggufTemp = File.createTempFile("leap8-test", ".gguf")
        try {
            ggufTemp.writeBytes(buildMinimalGguf("qwen35"))
            assertEquals("qwen35", Leap8CoreNative.readGgufArchitecture(ggufTemp.absolutePath))

            val expectedSha = MessageDigest.getInstance("SHA-256")
                .digest(ggufTemp.readBytes())
                .joinToString("") { "%02x".format(it) }
            assertEquals(expectedSha, Leap8CoreNative.fileSha256(ggufTemp.absolutePath))

            ggufTemp.writeBytes("not-a-gguf".toByteArray())
            assertNull(Leap8CoreNative.readGgufArchitecture(ggufTemp.absolutePath))
        } finally {
            ggufTemp.delete()
        }

        assertEquals(2048L * 1024L * 1024L + 1280L * 1024L * 1024L,
            Leap8CoreNative.requiredAvailableMemoryBytes(2048L * 1024L * 1024L, processorOrdinal = 0))

        val indices = Leap8CoreNative.compactSampleIndices(500, 5)
        assertEquals(listOf(0, 124, 249, 374, 499), indices.toList())

        assertFalse(Leap8CoreNative.sanitizeModelLabel("Bearer sk-secret-123 model", false).contains("sk-secret"))
        assertEquals("", Leap8CoreNative.sanitizeModelLabel("sk-abcd1234", strictWhitelist = true))
        assertEquals("qwen3.5-9b", Leap8CoreNative.sanitizeModelLabel("qwen3.5-9b\u0007", strictWhitelist = true))
    }

    @Test
    fun speechEnhancementAttenuatesHighFreqNoiseAndBoundsPeaks() {
        val sampleRate = 24_000
        val count = 24_000
        val signal = FloatArray(count) { i ->
            val t = i.toDouble() / sampleRate
            // DC offset + 440 Hz vocal fundamental + harsh 10 kHz high-frequency hiss + hot peak
            (0.18 + 0.55 * sin(2.0 * PI * 440.0 * t) + 0.40 * sin(2.0 * PI * 10_000.0 * t)).toFloat()
        }

        fun toneMagnitude(buf: FloatArray, freqHz: Double): Double {
            var re = 0.0
            var im = 0.0
            val start = 4_000
            val end = 20_000
            for (i in start until end) {
                val angle = 2.0 * PI * freqHz * i / sampleRate
                re += buf[i] * cos(angle)
                im -= buf[i] * sin(angle)
            }
            return hypot(re, im) / (end - start)
        }

        val before440 = toneMagnitude(signal, 440.0)
        val before10k = toneMagnitude(signal, 10_000.0)

        Leap8CoreNative.enhanceSpeechPcm(signal, sampleRate)

        val after440 = toneMagnitude(signal, 440.0)
        val after10k = toneMagnitude(signal, 10_000.0)
        val reductionDb = 20.0 * log10(before10k / maxOf(after10k, 1e-9))

        assertTrue("Expected >12 dB attenuation at 10 kHz, got $reductionDb dB", reductionDb > 12.0)
        assertTrue("Expected 440 Hz vocal tone preserved, after=$after440 before=$before440", after440 > before440 * 0.5)

        // Verify surgical 4.8 kHz whistle notch while preserving 2.6 kHz HD vocal clarity
        val whistleSignal = FloatArray(count) { i ->
            val t = i.toDouble() / sampleRate
            (0.25 * sin(2.0 * PI * 2_600.0 * t) + 0.30 * sin(2.0 * PI * 4_800.0 * t)).toFloat()
        }
        val before2600 = toneMagnitude(whistleSignal, 2_600.0)
        val before4800 = toneMagnitude(whistleSignal, 4_800.0)
        Leap8CoreNative.enhanceSpeechPcm(whistleSignal, sampleRate)
        val after2600 = toneMagnitude(whistleSignal, 2_600.0)
        val after4800 = toneMagnitude(whistleSignal, 4_800.0)
        val notchDb = 20.0 * log10(before4800 / maxOf(after4800, 1e-9))
        assertTrue("Expected >7 dB surgical notch at 4.8 kHz, got $notchDb dB", notchDb > 7.0)
        assertTrue("Expected 2.6 kHz presence boosted/preserved, after=$after2600 before=$before2600", after2600 > before2600 * 0.9)

        val mean = signal.average()
        assertTrue("Expected DC offset removed, mean=$mean", abs(mean) < 0.01)

        val maxAbs = signal.maxOf { abs(it) }
        assertTrue("Expected soft-knee ceiling <= 0.82f, got $maxAbs", maxAbs <= 0.82f)
        assertEquals("Expected fade-in zero at index 0", 0.0f, signal[0], 0.001f)
        assertEquals("Expected fade-out zero at last index", 0.0f, signal[count - 1], 0.001f)

        assertThrows(IllegalStateException::class.java) {
            Leap8CoreNative.enhanceSpeechPcm(FloatArray(128) { 0.0f }, sampleRate)
        }
        assertThrows(IllegalStateException::class.java) {
            Leap8CoreNative.enhanceSpeechPcm(FloatArray(128) { 1.0f }, sampleRate)
        }
    }

    private fun buildMinimalGguf(arch: String): ByteArray {
        val out = ByteArrayOutputStream()
        fun writeU32(v: Int) {
            out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
        }
        fun writeU64(v: Long) {
            out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array())
        }
        fun writeGgufString(s: String) {
            val bytes = s.toByteArray(StandardCharsets.UTF_8)
            writeU64(bytes.size.toLong())
            out.write(bytes)
        }
        out.write(byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte()))
        writeU32(3) // version
        writeU64(0L) // tensor count
        writeU64(1L) // metadata kv count
        writeGgufString("general.architecture")
        writeU32(8) // GGUF_TYPE_STRING
        writeGgufString(arch)
        return out.toByteArray()
    }
}
