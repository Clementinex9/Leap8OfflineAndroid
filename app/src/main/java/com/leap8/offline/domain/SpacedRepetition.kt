package com.leap8.offline.domain

import java.time.LocalDate
import kotlin.math.max
import kotlin.math.roundToInt

object SpacedRepetition {
    /** A conservative SM-2 variant that keeps failed cards in the same session. */
    fun review(
        current: WordProgress,
        rating: ReviewRating,
        todayEpochDay: Long = LocalDate.now().toEpochDay()
    ): WordProgress {
        val quality = rating.quality
        val newEase = max(
            1.3,
            current.easeFactor + (0.1 - (5 - quality) * (0.08 + (5 - quality) * 0.02))
        )
        if (quality < 3) {
            return current.copy(
                repetitions = 0,
                intervalDays = 0,
                easeFactor = newEase,
                dueEpochDay = todayEpochDay,
                lapses = current.lapses + 1,
                lastRating = rating
            )
        }

        val repetitions = current.repetitions + 1
        val interval = when (repetitions) {
            1 -> if (rating == ReviewRating.EASY) 2 else 1
            2 -> if (rating == ReviewRating.EASY) 6 else 3
            else -> max(1, (current.intervalDays.coerceAtLeast(1) * newEase * when (rating) {
                ReviewRating.HARD -> 0.72
                ReviewRating.GOOD -> 1.0
                ReviewRating.EASY -> 1.35
                ReviewRating.AGAIN -> 0.0
            }).roundToInt())
        }
        return current.copy(
            repetitions = repetitions,
            intervalDays = interval,
            easeFactor = newEase,
            dueEpochDay = todayEpochDay + interval,
            lastRating = rating
        )
    }
}
