package com.leap8.offline.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EssayScoringTest {
    private val task = EssayTask(
        "test", "Task 2", "Transport",
        "Cities should invest in public transport rather than new roads. Do you agree or disagree?",
        120, emptyList()
    )

    @Test fun scorerReturnsFourHalfBandCriteriaWithoutNetwork() {
        val essay = """
            I strongly agree that cities should invest in public transport rather than new roads.
            Although roads can remove a temporary bottleneck, they often encourage additional car journeys.

            Firstly, reliable rail and bus services make commuting feasible for a larger proportion of residents.
            When services arrive frequently, workers can leave their cars at home; consequently, the same street
            space carries substantially more people. This approach can mitigate congestion without consuming scarce land.

            Secondly, sustainable transport produces broader benefits. Electric rail can reduce local pollution,
            whereas another urban motorway may undermine neighbourhoods and create a long-term maintenance burden.
            Admittedly, some road repairs remain crucial, but maintenance is different from continuous expansion.

            In conclusion, public transport is the more beneficial investment because it improves access while
            limiting the demand that causes congestion. Cities should therefore prioritise dependable networks.
        """.trimIndent()
        val score = LocalRubricEssayScorer().score(task, essay)
        assertEquals(4, score.criteria.size)
        assertTrue(score.overallBand in 1.0..9.0)
        assertEquals(0.0, (score.overallBand * 2) % 1, 0.0)
        assertEquals("本地语言量表引擎", score.engine)
    }

    @Test fun blankEssayGetsZeroAndActionableMessage() {
        val score = LocalRubricEssayScorer().score(task, "")
        assertEquals(0.0, score.overallBand, 0.0)
        assertEquals(0, score.wordCount)
        assertTrue(score.priorities.isNotEmpty())
    }
}
