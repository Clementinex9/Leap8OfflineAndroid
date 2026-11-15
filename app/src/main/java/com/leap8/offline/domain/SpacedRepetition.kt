package com.leap8.offline.domain

import com.leap8.offline.core.Leap8CoreNative
import java.time.LocalDate

object SpacedRepetition {
    /** A conservative SM-2 variant that keeps failed cards in the same session. */
    fun review(
        current: WordProgress,
        rating: ReviewRating,
        todayEpochDay: Long = LocalDate.now().toEpochDay()
    ): WordProgress {
        val result = Leap8CoreNative.reviewWord(
            repetitions = current.repetitions,
            intervalDays = current.intervalDays,
            easeFactor = current.easeFactor,
            lapses = current.lapses,
            quality = rating.quality,
            todayEpochDay = todayEpochDay
        )
        return current.copy(
            repetitions = result.repetitions,
            intervalDays = result.intervalDays,
            easeFactor = result.easeFactor,
            dueEpochDay = result.dueEpochDay,
            lapses = result.lapses,
            lastRating = rating
        )
    }
}
