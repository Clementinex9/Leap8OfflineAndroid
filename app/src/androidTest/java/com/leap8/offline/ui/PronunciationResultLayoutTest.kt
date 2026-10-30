package com.leap8.offline.ui

import android.os.ParcelFileDescriptor
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.leap8.offline.MainActivity
import com.leap8.offline.speech.PronunciationResult
import com.leap8.offline.ui.theme.Leap8Theme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Pure result-state rendering: no microphone, AI request or learning-history writes. */
@RunWith(AndroidJUnit4::class)
class PronunciationResultLayoutTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test(timeout = 120_000L)
    fun longFeedbackAndMaximumScoreRemainReadableAtCurrentFontScale() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val launch = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(
                "am start -W -n com.leap8.offline/.MainActivity -f 0x10008000"
            )
        ).use { it.bufferedReader().readText() }
        assertTrue("MainActivity launch failed: $launch", !launch.contains("Error", true))
        var activity: MainActivity? = null
        instrumentation.runOnMainSync {
            activity = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().singleOrNull()
        }
        val target = requireNotNull(activity) { "MainActivity was not resumed" }
        val samples = listOf(
            PronunciationResult(
                transcript = "Good pronunciation makes learning English more enjoyable.",
                accuracy = 100, matchedWords = listOf("pronunciation"), missingWords = emptyList(),
                feedback = "词序与识别完整。请继续保持自然的重音和连读，在解释原因时使用一个具体例子，让表达准确而连贯。"
            ),
            PronunciationResult(
                transcript = "", accuracy = 0, matchedWords = emptyList(),
                missingWords = listOf("pronunciation", "communication", "environmentally"),
                feedback = "尚未识别到完整句子。请在安静环境中靠近麦克风重试，先听示范，再分成短语跟读；这个提示必须完整显示，不能依赖固定高度。"
            )
        )
        try {
            instrumentation.runOnMainSync {
                target.setContent {
                    Leap8Theme {
                        Surface(Modifier.fillMaxSize()) {
                            Column(
                                Modifier.fillMaxSize().safeDrawingPadding()
                                    .verticalScroll(rememberScrollState()).padding(20.dp)
                            ) {
                                samples.forEach { sample ->
                                    PronunciationResultCard(sample)
                                    Spacer(Modifier.height(16.dp))
                                }
                            }
                        }
                    }
                }
            }
            compose.waitForIdle()
            samples.forEach { sample ->
                compose.onNodeWithText("${sample.accuracy}%").performScrollTo().assertIsDisplayed()
                compose.onNodeWithText(sample.feedback).performScrollTo().assertIsDisplayed()
            }
            val nodes = compose.onAllNodes(
                SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true
            ).fetchSemanticsNodes()
            var checked = 0
            compose.runOnIdle {
                nodes.forEach { node ->
                    val layouts = mutableListOf<TextLayoutResult>()
                    node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
                    layouts.forEach { layout ->
                        checked++
                        val text = layout.layoutInput.text.text
                        val audit = VisibleTextLayoutAudit.check(layout)
                        assertTrue(
                            "Result text clipping: $text\n${audit.detail}\n${audit.problems.joinToString()}",
                            audit.problems.isEmpty()
                        )
                    }
                }
            }
            assertTrue("No result text layouts checked", checked >= 8)
        } finally {
            // Restore the actual application, retaining its existing ViewModel/data.
            instrumentation.runOnMainSync { target.setContent { Leap8Theme { Leap8App() } } }
            compose.waitForIdle()
        }
    }
}
