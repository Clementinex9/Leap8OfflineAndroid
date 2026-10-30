package com.leap8.offline.ui

import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Constraints

/**
 * Checks laid-out characters, not the width of an otherwise empty paragraph canvas.
 *
 * Compose 1.8.2 ParagraphLayoutCache.slowCreateTextLayoutResultOrNull synthesizes the
 * semantics MultiParagraph with prevConstraints, but supplies the actual layoutSize.
 * A two-character 96 px label may therefore expose a 304 px paragraph. Its raw
 * didOverflowWidth flag is not proof that any character is clipped. Even that synthetic
 * paragraph's alignment coordinates can differ from what the simple-text node draws.
 * Remeasure identical inputs inside the actual allocated box before inspecting ink
 * advance boxes. In particular keep softWrap=false so genuinely clipped long lines
 * cannot be made to pass by silently wrapping them during this check.
 */
internal object VisibleTextLayoutAudit {
    data class Result(val problems: List<String>, val detail: String)

    fun check(layout: TextLayoutResult): Result {
        val input = layout.layoutInput
        val measured = TextMeasurer(
            defaultFontFamilyResolver = input.fontFamilyResolver,
            defaultDensity = input.density,
            defaultLayoutDirection = input.layoutDirection,
            cacheSize = 0
        ).measure(
            text = input.text,
            style = input.style,
            overflow = input.overflow,
            softWrap = input.softWrap,
            maxLines = input.maxLines,
            placeholders = input.placeholders,
            constraints = Constraints(maxWidth = layout.size.width, maxHeight = layout.size.height),
            skipCache = true
        )
        val problems = mutableListOf<String>()
        val originalEllipsis = (0 until layout.lineCount).any(layout::isLineEllipsized)
        val measuredEllipsis = (0 until measured.lineCount).any(measured::isLineEllipsized)
        if (originalEllipsis || measuredEllipsis) problems += "ellipsized text"
        if (layout.multiParagraph.didExceedMaxLines || measured.multiParagraph.didExceedMaxLines) {
            problems += "text exceeds max lines / available height"
        }
        if (outside(measured.multiParagraph.height, layout.size.height.toFloat())) {
            problems += "paragraph height=${measured.multiParagraph.height} exceeds allocated=${layout.size.height}"
        }
        val coveredEnd = if (measured.lineCount == 0) 0 else measured.getLineEnd(measured.lineCount - 1)
        if (input.text.text.drop(coveredEnd).any { !it.isWhitespace() }) problems += "unlaid-out non-whitespace suffix"

        var contentLeft = Float.POSITIVE_INFINITY
        var contentRight = Float.NEGATIVE_INFINITY
        var contentTop = Float.POSITIVE_INFINITY
        var contentBottom = Float.NEGATIVE_INFINITY
        val outsideCharacters = mutableListOf<String>()
        // Whitespace advance is not visible ink. Do not fail a label merely because a
        // trailing space or tab is wider than its content; every non-whitespace UTF-16
        // offset is checked, including punctuation, IPA, CJK and surrogate pairs.
        for (offset in 0 until minOf(coveredEnd, input.text.length)) {
            if (input.text.text[offset].isWhitespace()) continue
            val box = measured.getBoundingBox(offset)
            contentLeft = minOf(contentLeft, box.left)
            contentRight = maxOf(contentRight, box.right)
            contentTop = minOf(contentTop, box.top)
            contentBottom = maxOf(contentBottom, box.bottom)
            val invalid = !box.left.isFinite() || !box.top.isFinite() || !box.right.isFinite() || !box.bottom.isFinite()
            if (invalid || outside(-box.left, 0f) || outside(-box.top, 0f) ||
                outside(box.right, layout.size.width.toFloat()) ||
                outside(box.bottom, layout.size.height.toFloat())) {
                if (outsideCharacters.size < 8) outsideCharacters += "offset=$offset box=$box"
            }
        }
        if (outsideCharacters.isNotEmpty()) problems += "characters outside allocated box: ${outsideCharacters.joinToString()}"
        val lines = (0 until measured.lineCount).joinToString { line ->
            "$line:[${measured.getLineLeft(line)},${measured.getLineTop(line)},${measured.getLineRight(line)},${measured.getLineBottom(line)}]"
        }
        return Result(problems, "remeasuredSize=${measured.size} content=[$contentLeft,$contentTop,$contentRight,$contentBottom] " +
            "lines=[$lines] coveredEnd=$coveredEnd/${input.text.length} softWrap=${input.softWrap}")
    }

    private fun outside(value: Float, limit: Float): Boolean {
        // Only floating-point arithmetic precision is allowed, not a physical-pixel
        // allowance. Four ULPs at ~1,000 px is <0.00025 px, not 1 px or a dp threshold.
        val arithmeticError = 4f * maxOf(Math.ulp(value), Math.ulp(limit))
        return value - limit > arithmeticError
    }
}
