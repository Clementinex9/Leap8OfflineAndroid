package com.leap8.offline.core

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * JNI bridge to the C++20 core engine (`libleap8_core.so` on Android, `leap8_core.dll` in host JVM tests).
 */
internal object Leap8CoreNative {
    private val loadResult: Result<Boolean> = runCatching {
        val explicitPath = System.getProperty("leap8.core.dll.path")?.takeIf { it.isNotBlank() }
        if (explicitPath != null) {
            System.load(explicitPath)
        } else {
            System.loadLibrary("leap8_core")
        }
        nativeVersion() == 1
    }

    val isNativeLoaded: Boolean
        get() = loadResult.getOrDefault(false)

    val loadError: Throwable?
        get() = loadResult.exceptionOrNull()

    fun requireLoaded() {
        if (!isNativeLoaded) {
            throw UnsatisfiedLinkError(
                "leap8_core native library is not loaded: ${loadError?.message ?: "unknown error"}"
            ).apply {
                loadError?.let { initCause(it) }
            }
        }
    }

    data class NativeVocabCard(
        val id: String,
        val word: String,
        val phonetic: String,
        val partOfSpeech: String,
        val meaningZh: String,
        val meaningZhTw: String,
        val example: String,
        val exampleZh: String,
        val roots: String,
        val collocations: List<String>,
        val level: String,
        val topic: String
    )

    data class NativeProgressInput(
        val wordId: String,
        val repetitions: Int,
        val intervalDays: Int,
        val dueEpochDay: Long
    )

    class NativeWordbookState(
        val generation: Long,
        val newCount: Int,
        val learningCount: Int,
        val masteredCount: Int,
        val dueCount: Int,
        val sortedCardIndices: IntArray,
        val sortedFlags: ByteArray
    ) {
        fun stateOrdinalAt(position: Int): Int = sortedFlags[position].toInt() and 0x03
        fun isDueAt(position: Int): Boolean = (sortedFlags[position].toInt() and 0x04) != 0
    }

    data class NativeReviewResult(
        val repetitions: Int,
        val intervalDays: Int,
        val dueEpochDay: Long,
        val lapses: Int,
        val easeFactor: Double
    )

    data class NativeDashboardMetrics(
        val streakDays: Int,
        val todayMinutes: Int,
        val dueWords: Int,
        val learnedWords: Int,
        val totalWords: Int,
        val newWords: Int,
        val learningWords: Int,
        val masteredWords: Int,
        val completedSessions: Int,
        val estimatedBand: Double,
        val skillNorms: FloatArray
    )

    data class NativeCriterionScore(
        val name: String,
        val band: Double,
        val evidence: String,
        val nextStep: String
    )

    data class NativeEssayScore(
        val overallBand: Double,
        val wordCount: Int,
        val criteria: List<NativeCriterionScore>,
        val strengths: List<String>,
        val priorities: List<String>,
        val correctedExamples: List<String>
    )

    data class NativeAiRevisionChange(
        val before: String,
        val after: String,
        val reasonZh: String,
        val category: String
    )

    data class NativeAiRevision(
        val revisedText: String,
        val summaryZh: String,
        val changes: List<NativeAiRevisionChange>
    )

    data class NativePronunciationResult(
        val accuracyPercent: Int,
        val matchedWords: List<String>,
        val missingWords: List<String>,
        val feedback: String
    )

    fun loadVocabularyCatalog(
        gzipBytes: ByteArray,
        editorialCards: List<NativeVocabCard>,
        sortByLevelWord: Boolean = true
    ): List<NativeVocabCard> {
        requireLoaded()
        val writer = BinWriter(4096)
        writer.writeI32(editorialCards.size)
        for (card in editorialCards) {
            writer.writeStr(card.id)
            writer.writeStr(card.word)
            writer.writeStr(card.phonetic)
            writer.writeStr(card.partOfSpeech)
            writer.writeStr(card.meaningZh)
            writer.writeStr(card.meaningZhTw)
            writer.writeStr(card.example)
            writer.writeStr(card.exampleZh)
            writer.writeStr(card.roots)
            writer.writeI32(card.collocations.size)
            for (col in card.collocations) {
                writer.writeStr(col)
            }
            writer.writeStr(card.level)
            writer.writeStr(card.topic)
        }
        val packed = nativeLoadVocabularyCatalog(gzipBytes, writer.toByteArray(), sortByLevelWord)
        val reader = BinReader(packed)
        val count = reader.readI32()
        val out = ArrayList<NativeVocabCard>(count)
        repeat(count) {
            val id = reader.readStr()
            val word = reader.readStr()
            val phonetic = reader.readStr()
            val pos = reader.readStr()
            val meaningZh = reader.readStr()
            val meaningZhTw = reader.readStr()
            val example = reader.readStr()
            val exampleZh = reader.readStr()
            val roots = reader.readStr()
            val colCount = reader.readI32()
            val cols = ArrayList<String>(colCount)
            repeat(colCount) { cols.add(reader.readStr()) }
            val level = reader.readStr()
            val topic = reader.readStr()
            out.add(
                NativeVocabCard(
                    id = id,
                    word = word,
                    phonetic = phonetic,
                    partOfSpeech = pos,
                    meaningZh = meaningZh,
                    meaningZhTw = meaningZhTw,
                    example = example,
                    exampleZh = exampleZh,
                    roots = roots,
                    collocations = cols,
                    level = level,
                    topic = topic
                )
            )
        }
        return out
    }

    fun syncWordbookState(
        progressRows: List<NativeProgressInput>,
        todayEpochDay: Long
    ): NativeWordbookState {
        requireLoaded()
        val writer = BinWriter(32 + progressRows.size * 32)
        writer.writeI32(progressRows.size)
        for (row in progressRows) {
            writer.writeStr(row.wordId)
            writer.writeI32(row.repetitions)
            writer.writeI32(row.intervalDays)
            writer.writeI64(row.dueEpochDay)
        }
        val packed = nativeSyncWordbookState(writer.toByteArray(), todayEpochDay)
        val reader = BinReader(packed)
        val generation = reader.readI64()
        val n = reader.readI32()
        val newCount = reader.readI32()
        val learningCount = reader.readI32()
        val masteredCount = reader.readI32()
        val dueCount = reader.readI32()
        val sortedIndices = IntArray(n) { reader.readI32() }
        val sortedFlags = ByteArray(n) { reader.readU8() }
        return NativeWordbookState(
            generation = generation,
            newCount = newCount,
            learningCount = learningCount,
            masteredCount = masteredCount,
            dueCount = dueCount,
            sortedCardIndices = sortedIndices,
            sortedFlags = sortedFlags
        )
    }

    fun filterWordbook(
        expectedGeneration: Long,
        filterMode: Int,
        query: String
    ): IntArray? {
        requireLoaded()
        return nativeFilterWordbook(
            expectedGeneration = expectedGeneration,
            filterMode = filterMode,
            queryUtf8 = query.toByteArray(StandardCharsets.UTF_8)
        )
    }

    fun reviewWord(
        repetitions: Int,
        intervalDays: Int,
        easeFactor: Double,
        lapses: Int,
        quality: Int,
        todayEpochDay: Long
    ): NativeReviewResult {
        requireLoaded()
        val raw = nativeReviewWord(repetitions, intervalDays, easeFactor, lapses, quality, todayEpochDay)
        return NativeReviewResult(
            repetitions = raw[0].toInt(),
            intervalDays = raw[1].toInt(),
            dueEpochDay = raw[2],
            lapses = raw[3].toInt(),
            easeFactor = java.lang.Double.longBitsToDouble(raw[4])
        )
    }

    fun computeDashboard(
        totalVocabulary: Int,
        todayEpochDay: Long,
        wpReps: IntArray,
        wpIntervals: IntArray,
        wpDueDays: LongArray,
        sessSkills: IntArray,
        sessScores: DoubleArray,
        sessDurations: IntArray,
        sessDays: LongArray
    ): NativeDashboardMetrics {
        requireLoaded()
        require(wpReps.size == wpIntervals.size && wpReps.size == wpDueDays.size) {
            "Word progress array lengths must match"
        }
        require(
            sessSkills.size == sessScores.size &&
                sessSkills.size == sessDurations.size &&
                sessSkills.size == sessDays.size
        ) {
            "Practice session array lengths must match"
        }
        val raw = nativeComputeDashboard(
            totalVocabulary = totalVocabulary,
            todayEpochDay = todayEpochDay,
            wpReps = wpReps,
            wpIntervals = wpIntervals,
            wpDueDays = wpDueDays,
            sessSkills = sessSkills,
            sessScores = sessScores,
            sessDurations = sessDurations,
            sessDays = sessDays
        )
        return NativeDashboardMetrics(
            streakDays = raw[0].toInt(),
            todayMinutes = raw[1].toInt(),
            dueWords = raw[2].toInt(),
            learnedWords = raw[3].toInt(),
            totalWords = raw[4].toInt(),
            newWords = raw[5].toInt(),
            learningWords = raw[6].toInt(),
            masteredWords = raw[7].toInt(),
            completedSessions = raw[8].toInt(),
            estimatedBand = raw[9],
            skillNorms = FloatArray(6) { idx -> raw[10 + idx].toFloat() }
        )
    }

    fun scoreEssayRubric(taskPrompt: String, minimumWords: Int, essay: String): NativeEssayScore {
        requireLoaded()
        val packed = nativeScoreEssayRubric(
            taskPromptUtf8 = taskPrompt.toByteArray(StandardCharsets.UTF_8),
            minimumWords = minimumWords,
            essayUtf8 = essay.toByteArray(StandardCharsets.UTF_8)
        )
        return decodeEssayScore(packed)
    }

    fun parseAiEssayScore(raw: String, essay: String): NativeEssayScore {
        requireLoaded()
        val packed = nativeParseAiEssayScore(
            rawUtf8 = raw.toByteArray(StandardCharsets.UTF_8),
            essayUtf8 = essay.toByteArray(StandardCharsets.UTF_8)
        )
        return decodeEssayScore(packed)
    }

    private fun decodeEssayScore(packed: ByteArray): NativeEssayScore {
        val reader = BinReader(packed)
        val overallBand = reader.readF64()
        val wordCount = reader.readI32()
        val critCount = reader.readI32()
        val criteria = ArrayList<NativeCriterionScore>(critCount)
        repeat(critCount) {
            criteria.add(
                NativeCriterionScore(
                    name = reader.readStr(),
                    band = reader.readF64(),
                    evidence = reader.readStr(),
                    nextStep = reader.readStr()
                )
            )
        }
        val strengths = reader.readStrList()
        val priorities = reader.readStrList()
        val corrected = reader.readStrList()
        return NativeEssayScore(
            overallBand = overallBand,
            wordCount = wordCount,
            criteria = criteria,
            strengths = strengths,
            priorities = priorities,
            correctedExamples = corrected
        )
    }

    fun parseAiRevision(raw: String, original: String): NativeAiRevision {
        requireLoaded()
        val packed = nativeParseAiRevision(
            rawUtf8 = raw.toByteArray(StandardCharsets.UTF_8),
            originalUtf8 = original.toByteArray(StandardCharsets.UTF_8)
        )
        val reader = BinReader(packed)
        val revisedText = reader.readStr()
        val summaryZh = reader.readStr()
        val changeCount = reader.readI32()
        val changes = ArrayList<NativeAiRevisionChange>(changeCount)
        repeat(changeCount) {
            changes.add(
                NativeAiRevisionChange(
                    before = reader.readStr(),
                    after = reader.readStr(),
                    reasonZh = reader.readStr(),
                    category = reader.readStr()
                )
            )
        }
        return NativeAiRevision(
            revisedText = revisedText,
            summaryZh = summaryZh,
            changes = changes
        )
    }

    fun comparePronunciation(reference: String, transcript: String): NativePronunciationResult {
        requireLoaded()
        val packed = nativeComparePronunciation(
            referenceUtf8 = reference.toByteArray(StandardCharsets.UTF_8),
            transcriptUtf8 = transcript.toByteArray(StandardCharsets.UTF_8)
        )
        val reader = BinReader(packed)
        val accuracy = reader.readI32()
        val matched = reader.readStrList()
        val missing = reader.readStrList()
        val feedback = reader.readStr()
        return NativePronunciationResult(
            accuracyPercent = accuracy,
            matchedWords = matched,
            missingWords = missing,
            feedback = feedback
        )
    }

    fun validateAudioPcm(samples: FloatArray) {
        requireLoaded()
        nativeValidateAudioPcm(samples)
    }

    fun enhanceSpeechPcm(samples: FloatArray, sampleRate: Int = 24_000) {
        requireLoaded()
        nativeEnhanceSpeechPcm(samples, sampleRate)
    }

    fun normalizeVoiceSpeed(speed: Float): Float {
        requireLoaded()
        return nativeNormalizeVoiceSpeed(speed)
    }

    fun effectiveVoiceSpeed(speed: Float, slower: Boolean): Float {
        requireLoaded()
        return nativeEffectiveVoiceSpeed(speed, slower)
    }

    fun readGgufArchitecture(path: String): String? {
        requireLoaded()
        val raw = nativeReadGgufArchitecture(path.toByteArray(StandardCharsets.UTF_8)) ?: return null
        return String(raw, StandardCharsets.UTF_8)
    }

    fun fileSha256(path: String): String {
        requireLoaded()
        val raw = nativeFileSha256(path.toByteArray(StandardCharsets.UTF_8))
        return String(raw, StandardCharsets.UTF_8)
    }

    fun requiredAvailableMemoryBytes(modelBytes: Long, processorOrdinal: Int): Long {
        requireLoaded()
        return nativeRequiredAvailableMemoryBytes(modelBytes, processorOrdinal)
    }

    fun compactSampleIndices(count: Int, maximum: Int): IntArray {
        requireLoaded()
        return nativeCompactSampleIndices(count, maximum)
    }

    fun sanitizeModelLabel(input: String, strictWhitelist: Boolean): String {
        requireLoaded()
        val raw = nativeSanitizeModelLabel(
            inputUtf8 = input.toByteArray(StandardCharsets.UTF_8),
            strictWhitelist = strictWhitelist
        )
        return String(raw, StandardCharsets.UTF_8)
    }

    @JvmStatic
    private external fun nativeVersion(): Int

    @JvmStatic
    private external fun nativeLoadVocabularyCatalog(
        gzipBytes: ByteArray,
        packedEditorialBytes: ByteArray,
        sortByLevelWord: Boolean
    ): ByteArray

    @JvmStatic
    private external fun nativeSyncWordbookState(
        packedProgressBytes: ByteArray,
        todayEpochDay: Long
    ): ByteArray

    @JvmStatic
    private external fun nativeFilterWordbook(
        expectedGeneration: Long,
        filterMode: Int,
        queryUtf8: ByteArray
    ): IntArray?

    @JvmStatic
    private external fun nativeReviewWord(
        repetitions: Int,
        intervalDays: Int,
        easeFactor: Double,
        lapses: Int,
        quality: Int,
        todayEpochDay: Long
    ): LongArray

    @JvmStatic
    private external fun nativeComputeDashboard(
        totalVocabulary: Int,
        todayEpochDay: Long,
        wpReps: IntArray,
        wpIntervals: IntArray,
        wpDueDays: LongArray,
        sessSkills: IntArray,
        sessScores: DoubleArray,
        sessDurations: IntArray,
        sessDays: LongArray
    ): DoubleArray

    @JvmStatic
    private external fun nativeScoreEssayRubric(
        taskPromptUtf8: ByteArray,
        minimumWords: Int,
        essayUtf8: ByteArray
    ): ByteArray

    @JvmStatic
    private external fun nativeParseAiEssayScore(
        rawUtf8: ByteArray,
        essayUtf8: ByteArray
    ): ByteArray

    @JvmStatic
    private external fun nativeParseAiRevision(
        rawUtf8: ByteArray,
        originalUtf8: ByteArray
    ): ByteArray

    @JvmStatic
    private external fun nativeComparePronunciation(
        referenceUtf8: ByteArray,
        transcriptUtf8: ByteArray
    ): ByteArray

    @JvmStatic
    private external fun nativeValidateAudioPcm(samples: FloatArray)

    @JvmStatic
    private external fun nativeEnhanceSpeechPcm(samples: FloatArray, sampleRate: Int)

    @JvmStatic
    private external fun nativeNormalizeVoiceSpeed(speed: Float): Float

    @JvmStatic
    private external fun nativeEffectiveVoiceSpeed(speed: Float, slower: Boolean): Float

    @JvmStatic
    private external fun nativeReadGgufArchitecture(pathUtf8: ByteArray): ByteArray?

    @JvmStatic
    private external fun nativeFileSha256(pathUtf8: ByteArray): ByteArray

    @JvmStatic
    private external fun nativeRequiredAvailableMemoryBytes(
        modelBytes: Long,
        processorOrdinal: Int
    ): Long

    @JvmStatic
    private external fun nativeCompactSampleIndices(count: Int, maximum: Int): IntArray

    @JvmStatic
    private external fun nativeSanitizeModelLabel(
        inputUtf8: ByteArray,
        strictWhitelist: Boolean
    ): ByteArray

    private class BinWriter(initialCapacity: Int = 256) {
        private val out = ByteArrayOutputStream(initialCapacity)

        fun writeI32(v: Int) {
            out.write(v and 0xFF)
            out.write((v ushr 8) and 0xFF)
            out.write((v ushr 16) and 0xFF)
            out.write((v ushr 24) and 0xFF)
        }

        fun writeI64(v: Long) {
            for (shift in 0 until 64 step 8) {
                out.write(((v ushr shift) and 0xFFL).toInt())
            }
        }

        fun writeStr(s: String) {
            val bytes = s.toByteArray(StandardCharsets.UTF_8)
            writeI32(bytes.size)
            out.write(bytes)
        }

        fun toByteArray(): ByteArray = out.toByteArray()
    }

    private class BinReader(private val buf: ByteArray) {
        private var pos = 0

        fun readU8(): Byte {
            check(pos < buf.size) { "Truncated native payload (u8)" }
            return buf[pos++]
        }

        fun readI32(): Int {
            check(pos + 4 <= buf.size) { "Truncated native payload (i32)" }
            val v = (buf[pos].toInt() and 0xFF) or
                ((buf[pos + 1].toInt() and 0xFF) shl 8) or
                ((buf[pos + 2].toInt() and 0xFF) shl 16) or
                ((buf[pos + 3].toInt() and 0xFF) shl 24)
            pos += 4
            return v
        }

        fun readI64(): Long {
            check(pos + 8 <= buf.size) { "Truncated native payload (i64)" }
            var v = 0L
            for (i in 0 until 8) {
                v = v or ((buf[pos + i].toLong() and 0xFFL) shl (i * 8))
            }
            pos += 8
            return v
        }

        fun readF64(): Double = java.lang.Double.longBitsToDouble(readI64())

        fun readStr(): String {
            val len = readI32()
            check(len >= 0 && pos + len <= buf.size) { "Truncated native payload (str len=$len)" }
            val s = if (len == 0) "" else String(buf, pos, len, StandardCharsets.UTF_8)
            pos += len
            return s
        }

        fun readStrList(): List<String> {
            val count = readI32()
            val list = ArrayList<String>(count)
            repeat(count) { list.add(readStr()) }
            return list
        }
    }
}
