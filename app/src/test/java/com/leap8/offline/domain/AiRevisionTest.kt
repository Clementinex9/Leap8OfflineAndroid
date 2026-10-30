package com.leap8.offline.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiRevisionTest {
    @Test
    fun parsesCompleteRevisionAndExactChanges() {
        val result = parseAiRevision(
            raw = """
                {
                  "revisedText":"People are increasingly dependent on public transport.",
                  "summaryZh":"修正主谓一致并提升搭配。",
                  "changes":[
                    {
                      "before":"People is more depend on public transport.",
                      "after":"People are increasingly dependent on public transport.",
                      "reasonZh":"People 是复数；dependent 应与 on 搭配。",
                      "category":"语法"
                    }
                  ]
                }
            """.trimIndent(),
            mode = AiRevisionMode.GRAMMAR,
            original = "People is more depend on public transport.",
            engine = "test",
            processor = AiProcessor.CPU,
            elapsedMs = 12
        )

        assertEquals("People are increasingly dependent on public transport.", result.revisedText)
        assertEquals(1, result.changes.size)
        assertEquals("语法", result.changes.single().category)
        assertEquals(AiProcessor.CPU, result.processor)
    }

    @Test
    fun ignoresThinkingAndHandlesBracesInsideJsonStrings() {
        val result = parseAiRevision(
            raw = """
                <think>{this is not the answer}</think>
                ```json
                {"revisedText":"This {example} is clear.","summaryZh":"保留花括号。","changes":[]}
                ```
            """.trimIndent(),
            mode = AiRevisionMode.NATURAL,
            original = "This {example} is clear.",
            engine = "test",
            processor = AiProcessor.NPU,
            elapsedMs = 8
        )

        assertEquals("This {example} is clear.", result.revisedText)
        assertTrue(result.changes.isEmpty())
    }

    @Test
    fun fallsBackToTruthfulWholeTextComparisonWhenLocationsAreInvalid() {
        val result = parseAiRevision(
            raw = """
                {
                  "revisedText":"The policy has several benefits.",
                  "summaryZh":"表达更准确。",
                  "changes":[
                    {"before":"invented source text","after":"better text","reasonZh":"原因","category":"表达"}
                  ]
                }
            """.trimIndent(),
            mode = AiRevisionMode.ACADEMIC,
            original = "The policy have many good points.",
            engine = "test",
            processor = AiProcessor.CLOUD,
            elapsedMs = 20
        )

        assertEquals(1, result.changes.size)
        assertEquals("The policy have many good points.", result.changes.single().before)
        assertEquals("全文精修", result.changes.single().category)
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsResponsesWithoutRevisionJson() {
        parseAiRevision(
            raw = "Here is a suggestion without structured output.",
            mode = AiRevisionMode.IELTS,
            original = "Original text.",
            engine = "test",
            processor = AiProcessor.CPU,
            elapsedMs = 1
        )
    }
}
