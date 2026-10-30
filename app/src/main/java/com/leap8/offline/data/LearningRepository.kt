package com.leap8.offline.data

import android.content.Context
import com.leap8.offline.domain.*
import java.time.LocalDate

class LearningRepository(context: Context) {
    private val database = OfflineDatabase(context.applicationContext)
    // Static lexical ordering is computed once, not for every answered flashcard.
    private val vocabulary = VocabularyCatalog(context).cards.sortedWith(compareBy({ it.level }, { it.word }))
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
        val entries = vocabulary.map { card ->
            val previous = previousEntries[card.id]
            val progress = saved[card.id] ?: previous?.progress?.takeIf { it.repetitions == 0 && it.lastRating == null }
                ?: WordProgress(card.id)
            val state = when {
                progress.repetitions >= 5 || progress.intervalDays >= 21 -> WordLearningState.MASTERED
                progress.repetitions > 0 -> WordLearningState.LEARNING
                else -> WordLearningState.NEW
            }
            val isDue = progress.repetitions == 0 || progress.dueEpochDay <= today
            // A review changes one card. Reuse the other ~5,000 entries to avoid
            // large bursts of short-lived UI model allocations and subsequent GC.
            if (previous != null && previous.progress == progress && previous.isDue == isDue) previous
            else WordbookEntry(
                card = card,
                progress = progress,
                state = state,
                isDue = isDue
            )
        }.sortedBy { it.state.ordinal }
        previousEntries = entries.associateBy { it.card.id }
        return WordbookSnapshot(
            entries = entries,
            total = entries.size,
            newCount = entries.count { it.state == WordLearningState.NEW },
            learningCount = entries.count { it.state == WordLearningState.LEARNING },
            masteredCount = entries.count { it.state == WordLearningState.MASTERED },
            dueCount = entries.count { it.isDue }
        )
    }
}
