package com.leap8.offline.ui

import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale
import kotlin.math.abs

/**
 * Run this class on the debug build. No ActivityScenario is used: API 37 devices can
 * hang in ActivityScenario's lifecycle wait although the Activity is already resumed.
 *
 * Font scale is intentionally owned by the caller. Run separately at the original
 * scale, 1.3 and 1.6 (adb shell settings put system font_scale ...), restoring the
 * ORIGINAL value in a finally block. This test neither changes settings nor sends
 * an AI request, records speech, imports a model, or submits learning history.
 *
 * All five main tabs, nine practice modules, and the wordbook are visited. Ordinary
 * pages are scanned to their end; the 5,048-word list is sampled over six viewports
 * by default (instrumentation argument auditWordbookViewports can increase this).
 * Every visible unmerged text node exposing GetTextLayoutResult is checked, including
 * text intentionally ellipsized by production code: truncation is an audit failure.
 */
@RunWith(AndroidJUnit4::class)
class UiLayoutAuditTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val problems = linkedSetOf<String>()
    private val report = mutableListOf<String>()
    private val visitedPages = linkedSetOf<String>()
    private var checkedTextNodes = 0
    private var editableScrollChecks = 0
    private var emptyEditableChecks = 0
    private var rawOverflowLayouts = 0
    private var paragraphCanvasOnlyReports = 0
    private var maximumWidthDeltaPx = 0f
    private var maximumHeightDeltaPx = 0f
    private val whitespace = Regex("\\s+")
    private val verticalScroll = SemanticsMatcher("vertical scroll container") {
        it.config.contains(SemanticsActions.ScrollBy) &&
            it.config.contains(SemanticsProperties.VerticalScrollAxisRange)
    }

    @Test(timeout = 480_000L)
    fun allEntrancesHaveUntruncatedReachableTextAtCurrentFontScale() {
        val scale = instrumentation.targetContext.resources.configuration.fontScale
        report += "UI_LAYOUT_AUDIT fontScale=$scale sdk=${android.os.Build.VERSION.SDK_INT}"
        try {
            launchMainActivity()
            for (tab in listOf("今日", "路径", "练习", "进度", "我的")) {
                checkpoint("tab:$tab") {
                    openTab(tab)
                    auditPage("tab:$tab")
                }
            }

            checkpoint("profile:API provider interfaces") { auditProviderInterfaces() }

            val modules = listOf(
                Module("记忆工坊", listOf("查看总词本", "显示答案")),
                Module("精听训练", listOf("检查证据", "查看原文与翻译", "下一课")),
                Module("口语实验室", listOf("开始跟读", "下一句")),
                Module("阅读训练", listOf("提交答案")),
                Module("写作评阅", listOf("换题", "评分", "精修并对比")),
                Module("AI精修对比", listOf("精修")),
                Module("离线AI教练", listOf("讲解")),
                Module("语法诊所", listOf("检查")),
                Module("模考中心", listOf("开始"))
            )
            for (module in modules) checkpoint("module:${module.title}") {
                openTab("练习")
                reachable(text(module.title) and hasClickAction()).assertIsEnabled().performClick()
                compose.waitForIdle()
                assertTrue("Missing back button for ${module.title}", exists(hasContentDescription("返回")))
                auditPage(module.title)
                module.controls.forEach { label ->
                    checkpoint("control:${module.title}:$label") {
                        val control = reachable(
                            text(label, substring = true) and hasClickAction() and
                                SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
                        )
                        control.assertHasClickAction().assertIsDisplayed()
                        val node = control.fetchSemanticsNode()
                        // Input/model prerequisites legitimately disable submit/generate buttons.
                        // We check the action and reachability without invoking their side effects.
                        report += "CONTROL page=${module.title} label=$label " +
                            "enabled=${!node.config.contains(SemanticsProperties.Disabled)} bounds=${node.boundsInRoot}"
                        auditVisibleText("${module.title}:control:$label")
                    }
                }
                if (module.title == "记忆工坊") {
                    reachable(text("显示答案") and hasClickAction()).performClick()
                    auditPage("记忆工坊:答案")
                    reachable(text("查看总词本") and hasClickAction()).performClick()
                    val wordbookSteps = InstrumentationRegistry.getArguments()
                        .getString("auditWordbookViewports")?.toIntOrNull()?.coerceIn(1, 2000) ?: 6
                    auditPage("IELTS总词本", wordbookSteps, sampleOnly = true)
                    back()
                }
                if (module.title == "精听训练") {
                    reachable(text("查看原文与翻译") and hasClickAction()).performClick()
                    auditPage("精听训练:原文与翻译")
                }
            }
            openTab("今日")
        } catch (error: Throwable) {
            problem("FATAL ${error.javaClass.simpleName}: ${error.message}")
        } finally {
            report += "SUMMARY pages=${visitedPages.size} textChecks=$checkedTextNodes editableScrollChecks=$editableScrollChecks emptyEditableChecks=$emptyEditableChecks problems=${problems.size} " +
                "rawOverflowLayouts=$rawOverflowLayouts paragraphCanvasOnlyReports=$paragraphCanvasOnlyReports " +
                "maxRawWidthDeltaPx=${precise(maximumWidthDeltaPx)} " +
                "maxRawHeightDeltaPx=${precise(maximumHeightDeltaPx)}"
            report += "VISITED ${visitedPages.joinToString()}"
            report += problems
            val destination = File(
                instrumentation.targetContext.getExternalFilesDir(null)
                    ?: instrumentation.targetContext.filesDir,
                "ui-layout-audit-font-${String.format(Locale.US, "%.2f", scale)}.txt"
            )
            destination.writeText(report.joinToString("\n") + "\n")
            Log.i(TAG, "Report: ${destination.absolutePath}")
            Log.i(TAG, report.takeLast(3).joinToString("\n"))
        }
        assertTrue(
            "Layout audit found ${problems.size} problems at fontScale=$scale. " +
                "See external-files ui-layout-audit-font-*.txt and logcat -s $TAG.\n" +
                problems.take(25).joinToString("\n"),
            problems.isEmpty()
        )
    }

    private fun launchMainActivity() {
        val output = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(
                "am start -W -n com.leap8.offline/.MainActivity -f 0x10008000"
            )
        ).use { it.bufferedReader().readText() }
        report += "LAUNCH ${output.replace('\n', ' ')}"
        assertTrue("MainActivity did not launch: $output", !output.contains("Error", true))
        compose.waitUntil(timeoutMillis = 20_000L) {
            compose.onAllNodes(text("今日") and hasClickAction())
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
    }

    private fun text(label: String, substring: Boolean = false) =
        SemanticsMatcher("text(normalized) ${if (substring) "contains" else "equals"} $label") { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().any {
                val candidate = it.text.replace(whitespace, "")
                val expected = label.replace(whitespace, "")
                if (substring) candidate.contains(expected) else candidate == expected
            }
        }

    private fun exists(matcher: SemanticsMatcher): Boolean =
        compose.onAllNodes(matcher).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()

    private fun openTab(label: String) {
        repeat(3) {
            if (exists(hasContentDescription("返回"))) back()
        }
        compose.onNode(text(label) and hasClickAction()).assertIsEnabled().performClick()
        compose.waitForIdle()
    }

    private fun back() {
        compose.onNode(hasContentDescription("返回") and hasClickAction()).performClick()
        compose.waitForIdle()
    }

    private fun auditProviderInterfaces() {
        openTab("我的")
        val providers = listOf("本地模型", "OpenAI", "DeepSeek", "Qwen")
        reachable(text("本地模型") and hasClickAction())
        val selected = SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)
        val original = providers.firstOrNull { exists(text(it) and hasClickAction() and selected) }
            ?: error("Could not identify selected AI provider; refusing to alter preferences")
        try {
            for (provider in providers.drop(1)) checkpoint("profile:$provider") {
                reachable(text(provider) and hasClickAction()).performClick()
                compose.waitUntil(timeoutMillis = 10_000L) { exists(text(provider) and hasClickAction() and selected) }
                auditPage("我的:$provider")
                // Inspect only: never save, delete keys or request any provider.
                reachable(text("安全保存") and hasClickAction()).assertIsDisplayed()
            }
        } finally {
            reachable(text(original) and hasClickAction()).performClick()
            compose.waitUntil(timeoutMillis = 10_000L) { exists(text(original) and hasClickAction() and selected) }
            report += "PROVIDER_RESTORED $original"
        }
    }

    private fun mainScroller(): SemanticsNodeInteraction? {
        val nodes = compose.onAllNodes(verticalScroll).fetchSemanticsNodes()
        val largest = nodes.maxByOrNull { it.boundsInRoot.width * it.boundsInRoot.height } ?: return null
        return compose.onNode(SemanticsMatcher("scroll container id=${largest.id}") { it.id == largest.id })
    }

    private fun reachable(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        if (!exists(matcher)) {
            val scroll = mainScroller() ?: error("Missing control and scroll container: ${matcher.description}")
            scroll.performScrollToNode(matcher)
        }
        val control = compose.onAllNodes(matcher)[0]
        val node = control.fetchSemanticsNode()
        val hasScrollingAncestor = generateSequence(node.parent) { it.parent }.any {
            it.config.contains(SemanticsActions.ScrollBy) &&
                it.config.contains(SemanticsProperties.VerticalScrollAxisRange)
        }
        // isDisplayed permits a partially clipped node. At large font scales a chip
        // can have one visible edge while its click center is outside the viewport.
        // Bring its entire rectangle into view before the actual input click.
        if (hasScrollingAncestor || !control.isDisplayed()) control.performScrollTo()
        return control.assertIsDisplayed()
    }

    private fun auditPage(page: String, maxViewports: Int = 60, sampleOnly: Boolean = false) {
        visitedPages += page
        val initialTextChecks = checkedTextNodes
        val scroll = mainScroller()
        if (scroll != null && scroll.fetchSemanticsNode().config.contains(SemanticsActions.ScrollToIndex)) {
            scroll.performScrollToIndex(0)
        }
        var reachedEnd = scroll == null
        var scannedViewports = 0
        for (step in 0 until maxViewports) {
            compose.waitForIdle()
            auditVisibleText(page)
            scannedViewports++
            val current = mainScroller() ?: break
            val before = scrollPosition(current.fetchSemanticsNode())
            val distance = current.fetchSemanticsNode().boundsInRoot.height * .72f
            if (distance <= 0f) error("Zero-height scrolling viewport: $page")
            current.performSemanticsAction(SemanticsActions.ScrollBy) { action -> action(0f, distance) }
            compose.waitForIdle()
            val after = scrollPosition(current.fetchSemanticsNode())
            if (abs(after - before) < .001f) {
                reachedEnd = true
                break
            }
        }
        report += "PAGE $page viewports=$scannedViewports end=$reachedEnd sampled=$sampleOnly"
        if (checkedTextNodes == initialTextChecks) problem("COVERAGE page=$page exposed no visible text layouts")
        if (!sampleOnly && !reachedEnd) problem("COVERAGE page=$page did not reach end in $maxViewports viewports")
    }

    private fun scrollPosition(node: SemanticsNode): Float = compose.runOnIdle {
        node.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.value?.invoke() ?: 0f
    }

    private fun auditVisibleText(page: String) {
        compose.waitForIdle()
        val nodes = compose.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true
        ).fetchSemanticsNodes().filter { node ->
            // Lazy prefetch and TextField decoration can expose semantics before a
            // measured node is placed. They are not yet visible text to audit.
            node.layoutInfo.isAttached && node.layoutInfo.isPlaced &&
                compose.onNode(SemanticsMatcher("visible node id=${node.id}") { it.id == node.id },
                    useUnmergedTree = true).isDisplayed()
        }
        compose.runOnIdle {
            for (node in nodes) {
                val bounds = node.boundsInRoot
                if (bounds.width <= 0f || bounds.height <= 0f) continue
                val editableText = node.config.getOrNull(SemanticsProperties.EditableText)
                if (editableText != null) {
                    val scrollOwner = generateSequence(node) { it.parent }.firstOrNull {
                        it.config.contains(SemanticsProperties.HorizontalScrollAxisRange)
                    }
                    if (scrollOwner != null) {
                        val range = scrollOwner.config[SemanticsProperties.HorizontalScrollAxisRange]
                        val value = range.value()
                        val maximum = range.maxValue()
                        if (!value.isFinite() || !maximum.isFinite() || value < 0f || maximum < value) {
                            problem("EDITABLE_SCROLL_INVALID page=$page value=$value maximum=$maximum bounds=$bounds")
                        }
                        editableScrollChecks++
                        report += "EDITABLE_HORIZONTAL_SCROLL page=$page node=${node.id} value=$value maximum=$maximum bounds=$bounds"
                        // A single-line editable value is intentionally scrollable. Its
                        // label/hint are separate text nodes and remain audited normally.
                        // Do not log its value (it may be a credential).
                        continue
                    }
                    if (editableText.isEmpty()) {
                        // An empty editable value has no glyphs and BasicTextField may
                        // legitimately not expose a TextLayoutResult. Its nonempty label
                        // and placeholder are independent nodes and are still checked.
                        emptyEditableChecks++
                        report += "EMPTY_EDITABLE page=$page node=${node.id} bounds=$bounds"
                        continue
                    }
                }
                val results = mutableListOf<TextLayoutResult>()
                val action = node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action ?: continue
                if (!action(results) || results.isEmpty()) {
                    val semanticTextLength = node.config.getOrNull(SemanticsProperties.Text)
                        .orEmpty().sumOf { it.length }
                    problem("TEXT_LAYOUT_UNAVAILABLE page=$page node=${node.id} " +
                        "editable=${editableText != null} editableLength=${editableText?.length ?: -1} " +
                        "semanticTextLength=$semanticTextLength placed=${node.layoutInfo.isPlaced} bounds=$bounds")
                    continue
                }
                for (layout in results) {
                    checkedTextNodes++
                    val ellipsis = (0 until layout.lineCount).any(layout::isLineEllipsized)
                    val paragraph = layout.multiParagraph
                    val widthDelta = paragraph.width - layout.size.width.toFloat()
                    val heightDelta = paragraph.height - layout.size.height.toFloat()
                    maximumWidthDeltaPx = maxOf(maximumWidthDeltaPx, widthDelta)
                    maximumHeightDeltaPx = maxOf(maximumHeightDeltaPx, heightDelta)
                    val exceededMaxLines = paragraph.didExceedMaxLines
                    val visibility = VisibleTextLayoutAudit.check(layout)
                    val materialOverflow = visibility.problems.isNotEmpty()
                    if (layout.hasVisualOverflow || layout.didOverflowWidth || layout.didOverflowHeight ||
                        ellipsis || exceededMaxLines || materialOverflow) {
                        rawOverflowLayouts++
                        val snippet = layout.layoutInput.text.text.replace('\n', ' ').take(300)
                        val details = "page=$page text=[$snippet] " +
                            "visual=${layout.hasVisualOverflow} width=${layout.didOverflowWidth} " +
                            "height=${layout.didOverflowHeight} ellipsis=$ellipsis " +
                            "exceededMaxLines=$exceededMaxLines size=${layout.size} " +
                            "paragraphWidthPx=${precise(paragraph.width)} paragraphHeightPx=${precise(paragraph.height)} " +
                            "widthDeltaPx=${precise(widthDelta)} heightDeltaPx=${precise(heightDelta)} " +
                            "maxLines=${layout.layoutInput.maxLines} bounds=$bounds ${visibility.detail}"
                        if (materialOverflow) problem("OVERFLOW $details reasons=${visibility.problems.joinToString()}")
                        else {
                            paragraphCanvasOnlyReports++
                            report += "PARAGRAPH_CANVAS_ONLY $details"
                        }
                    }
                }
            }
        }
    }

    private fun checkpoint(name: String, block: () -> Unit) {
        try {
            block()
        } catch (error: Throwable) {
            problem("CHECKPOINT $name ${error.javaClass.simpleName}: ${error.message?.take(1000)}")
        }
    }

    private fun problem(message: String) {
        if (problems.add(message)) Log.e(TAG, message)
    }

    private fun precise(value: Float): String = String.format(Locale.US, "%.9f", value)

    private data class Module(val title: String, val controls: List<String>)

    private companion object {
        const val TAG = "Leap8LayoutAudit"
    }
}
