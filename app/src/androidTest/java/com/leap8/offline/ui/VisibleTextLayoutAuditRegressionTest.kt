package com.leap8.offline.ui

import androidx.compose.ui.text.MultiParagraph
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Proves the audit rejects real clipping rather than just suppressing overflow flags. */
@RunWith(AndroidJUnit4::class)
class VisibleTextLayoutAuditRegressionTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val measurer = TextMeasurer(createFontFamilyResolver(context), Density(context), LayoutDirection.Ltr, 0)
    private val style = TextStyle(fontSize = 12.sp)

    @Test fun widerSyntheticParagraphDoesNotClipCenteredShortLabel() {
        val actual = measurer.measure("Ab", style.copy(textAlign = TextAlign.Center), constraints = Constraints(maxWidth = 1000))
        val synthetic = TextLayoutResult(
            actual.layoutInput,
            MultiParagraph(actual.multiParagraph.intrinsics, Constraints(maxWidth = 1000),
                actual.layoutInput.maxLines, actual.layoutInput.overflow),
            actual.size
        )
        assertTrue("Fixture must reproduce the misleading flag", synthetic.didOverflowWidth)
        val audit = VisibleTextLayoutAudit.check(synthetic)
        assertTrue("${audit.problems}\n${audit.detail}", audit.problems.isEmpty())
    }

    @Test fun genuinelyClippedNonWrappingTextIsRejected() {
        val clipped = measurer.measure("This long sentence cannot fit in 32 pixels", style,
            overflow = TextOverflow.Clip, softWrap = false, maxLines = 1,
            constraints = Constraints(maxWidth = 32))
        assertTrue(VisibleTextLayoutAudit.check(clipped).problems.isNotEmpty())
    }

    @Test fun ellipsizedTextIsRejected() {
        val ellipsized = measurer.measure("An ellipsis hides some learning content", style,
            overflow = TextOverflow.Ellipsis, maxLines = 1, constraints = Constraints(maxWidth = 40))
        assertTrue(VisibleTextLayoutAudit.check(ellipsized).problems.isNotEmpty())
    }

    @Test fun insufficientAllocatedHeightIsRejected() {
        val full = measurer.measure("First line\nSecond line\nThird line", style, constraints = Constraints(maxWidth = 1000))
        val clipped = TextLayoutResult(full.layoutInput, full.multiParagraph,
            IntSize(full.size.width, (full.size.height / 3).coerceAtLeast(1)))
        assertTrue(VisibleTextLayoutAudit.check(clipped).problems.isNotEmpty())
    }
}
