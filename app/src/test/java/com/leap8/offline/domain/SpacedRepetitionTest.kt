package com.leap8.offline.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpacedRepetitionTest {
    @Test fun goodReviewsExpandTheInterval() {
        val first = SpacedRepetition.review(WordProgress("word"), ReviewRating.GOOD, 100)
        val second = SpacedRepetition.review(first, ReviewRating.GOOD, 101)
        val third = SpacedRepetition.review(second, ReviewRating.GOOD, 104)
        assertEquals(1, first.intervalDays)
        assertEquals(3, second.intervalDays)
        assertTrue(third.intervalDays > second.intervalDays)
        assertEquals(104L + third.intervalDays, third.dueEpochDay)
    }

    @Test fun againResetsRepetitionsAndCountsALapse() {
        val learned = WordProgress("word", repetitions = 4, intervalDays = 18, dueEpochDay = 80)
        val failed = SpacedRepetition.review(learned, ReviewRating.AGAIN, 100)
        assertEquals(0, failed.repetitions)
        assertEquals(0, failed.intervalDays)
        assertEquals(1, failed.lapses)
        assertEquals(100, failed.dueEpochDay)
    }
}
