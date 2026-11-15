package com.leap8.offline.data

import android.content.Context
import com.leap8.offline.core.Leap8CoreNative
import com.leap8.offline.domain.*
import java.lang.ref.WeakReference
import java.time.LocalDate

internal object NativeWordbookFilter {
    private val lock = Any()
    private val generations = ArrayDeque<Pair<WeakReference<List<WordbookEntry>>, Long>>(4)

    fun register(entries: List<WordbookEntry>, generation: Long) {
        synchronized(lock) {
            generations.removeAll { it.first.get() == null }
            while (generations.size >= 4) generations.removeFirst()
            generations.addLast(WeakReference(entries) to generation)
        }
    }

    fun filter(entries: List<WordbookEntry>, filterLabel: String, query: String): List<WordbookEntry> {
        val generation = synchronized(lock) {
            generations.lastOrNull { it.first.get() === entries }?.second
        }
        val mode = when (filterLabel) {
            "新词" -> 1
            "学习中" -> 2
            "已掌握" -> 3
            "待复习" -> 4
            else -> 0
        }
        if (generation != null) {
            val indices = Leap8CoreNative.filterWordbook(generation, mode, query)
            if (indices != null) {
                return List(indices.size) { pos -> entries[indices[pos]] }
            }
        }
        return entries.filter { entry ->
            val matches = when (filterLabel) {
                "新词" -> entry.state == WordLearningState.NEW
                "学习中" -> entry.state == WordLearningState.LEARNING
                "已掌握" -> entry.state == WordLearningState.MASTERED
                "待复习" -> entry.isDue
                else -> true
            }
            matches && (query.isBlank() || entry.card.word.contains(query, true) || entry.card.meaningZh.contains(query, true))
        }
    }
}

class LearningRepository(context: Context) {
    private val database = OfflineDatabase(context.applicationContext)
    // Native VocabularyCatalog already returns cards sorted once by (level, word).
    private val vocabulary = VocabularyCatalog(context).cards
    private var previousEntries: Map<String, WordbookEntry> = emptyMap()

    fun dueVocabulary(): List<VocabularyCard> {
        val today = LocalDate.now().toEpochDay()
        val progress = database.allWordProgress()
        val due = vocabulary
            .filter { card -> progress[card.id]?.dueEpochDay?.let { it <= today } ?: true }
            .sortedWith(compareBy({ progress[it.id]?.dueEpochDay ?: Long.MIN_VALUE }, { it.level }, { it.word }))
        return due.take(30).ifEmpty { vocabulary.sortedBy { progress[it.id]?.dueEpochDay ?: Long.MAX_VALUE }.take(10) }
    }

    fun review(card: VocabularyCard, rating: ReviewRating): WordProgress {
        val updated = SpacedRepetition.review(database.wordProgress(card.id), rating)
        database.saveWordProgress(updated)
        database.logSession(Skill.VOCABULARY, rating.quality + 3.0, 20)
        return updated
    }

    fun logSession(skill: Skill, score: Double, durationSeconds: Int = 180) =
        database.logSession(skill, score, durationSeconds)

    fun saveEssay(task: EssayTask, essay: String, score: EssayScore) {
        database.saveEssay(task.id, essay, score)
        database.logSession(Skill.WRITING, score.overallBand, 1200)
    }

    fun dashboard(): DashboardSnapshot = database.dashboard(vocabulary.size)

    fun wordbook(): WordbookSnapshot {
        val today = LocalDate.now().toEpochDay()
        val saved = database.allWordProgress()
        val progressInputs = ArrayList<Leap8CoreNative.NativeProgressInput>(saved.size)
        for ((_, row) in saved) {
            progressInputs.add(
                Leap8CoreNative.NativeProgressInput(
                    wordId = row.wordId,
                    repetitions = row.repetitions,
                    intervalDays = row.intervalDays,
                    dueEpochDay = row.dueEpochDay
                )
            )
        }
        val state = Leap8CoreNative.syncWordbookState(progressInputs, today)
        val entries = ArrayList<WordbookEntry>(vocabulary.size)
        val nextPrevious = HashMap<String, WordbookEntry>(vocabulary.size * 2)
        for (pos in state.sortedCardIndices.indices) {
            val card = vocabulary[state.sortedCardIndices[pos]]
            val previous = previousEntries[card.id]
            val progress = saved[card.id]
                ?: previous?.progress?.takeIf { it.repetitions == 0 && it.lastRating == null }
                ?: WordProgress(card.id)
            val learningState = when (state.stateOrdinalAt(pos)) {
                2 -> WordLearningState.MASTERED
                1 -> WordLearningState.LEARNING
                else -> WordLearningState.NEW
            }
            val isDue = state.isDueAt(pos)
            val entry = if (previous != null && previous.progress == progress && previous.isDue == isDue) {
                previous
            } else {
                WordbookEntry(
                    card = card,
                    progress = progress,
                    state = learningState,
                    isDue = isDue
                )
            }
            entries.add(entry)
            nextPrevious[card.id] = entry
        }
        previousEntries = nextPrevious
        NativeWordbookFilter.register(entries, state.generation)
        return WordbookSnapshot(
            entries = entries,
            total = entries.size,
            newCount = state.newCount,
            learningCount = state.learningCount,
            masteredCount = state.masteredCount,
            dueCount = state.dueCount
        )
    }
}
