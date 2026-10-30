package com.leap8.offline.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.leap8.offline.diagnostics.DeveloperTelemetrySnapshot
import com.leap8.offline.diagnostics.InvocationMode
import com.leap8.offline.diagnostics.InvocationOutcome
import com.leap8.offline.diagnostics.InvocationRecord
import com.leap8.offline.diagnostics.PerformanceSample
import com.leap8.offline.domain.AiProcessor
import com.leap8.offline.domain.AiProvider
import com.leap8.offline.ui.theme.Leap8Theme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** In-memory fixtures only: never runs a model, calls API or touches user diagnostics/settings. */
@RunWith(AndroidJUnit4::class)
class DeveloperScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun content(snapshot: DeveloperTelemetrySnapshot, enabled: (Boolean) -> Unit = {}, clear: () -> Unit = {}) {
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 1.6f)) {
                Leap8Theme {
                    Surface(Modifier.fillMaxSize()) { DeveloperDashboard(snapshot, enabled, clear) }
                }
            }
        }
    }

    private fun revealText(text: String) {
        compose.onNodeWithTag("developer-dashboard").performScrollToNode(hasText(text))
        // LazyColumn first composes/brings the containing item into range. A detail card can
        // exceed one viewport at large fonts; now bring the actual descendant into view.
        compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()
    }

    @Test fun emptyStateControlsAndConfirmationOnlyClearDiagnostics() {
        var setting = true
        var clears = 0
        content(DeveloperTelemetrySnapshot(), { setting = it }, { clears++ })
        compose.onNodeWithContentDescription("记录调用性能").performClick()
        compose.runOnIdle { assertFalse(setting) }
        revealText("清空已完成的诊断记录")
        compose.onNodeWithText("清空已完成的诊断记录").performClick()
        compose.onNodeWithText("清空诊断记录？").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, clears) }
        compose.onNodeWithText("仅清空诊断").performClick()
        compose.runOnIdle { assertEquals(1, clears) }
        revealText("尚无调用记录")
    }

    @Test fun missingTokenUsageAndSensorsRemainUnknownAtLargeFont() {
        val record = InvocationRecord(
            id = "unknown", startedAtMs = 0, mode = InvocationMode.REVISION, provider = AiProvider.QWEN,
            processor = AiProcessor.CLOUD, outcome = InvocationOutcome.FAILED, elapsedMs = 2400,
            tokenSource = "UNAVAILABLE", samples = listOf(PerformanceSample(0), PerformanceSample(2400))
        )
        content(DeveloperTelemetrySnapshot(recent = listOf(record)))
        revealText("输入 token：未知 · 输出 token：未知")
        revealText("计数来源：未报告（不使用字符数估算）")
        compose.onNodeWithTag("developer-dashboard").performScrollToNode(hasTestTag("battery-temperature-chart"))
        compose.onNodeWithTag("battery-temperature-chart").performScrollTo().assertIsDisplayed()
        revealText("最近系统热状态：不可读/未采样")
    }

    @Test fun actualSamplesRenderAndDecodeRateIsDistinctFromEndToEnd() {
        val record = InvocationRecord(
            id = "measured", startedAtMs = 0, mode = InvocationMode.WRITING, provider = AiProvider.LOCAL,
            processor = AiProcessor.NPU, outcome = InvocationOutcome.SUCCESS, elapsedMs = 5000,
            inputTokens = 40, outputTokens = 10, decodeMs = 2000, tokenSource = "LOCAL_TOKENIZER",
            samples = listOf(
                PerformanceSample(0, 28f, 4_294_967_296, 104_857_600, 0),
                PerformanceSample(2000, null, 3_221_225_472, 115_343_360, 1),
                PerformanceSample(5000, 30f, 3_758_096_384, 110_100_480, 1)
            )
        )
        content(DeveloperTelemetrySnapshot(recent = listOf(record)))
        revealText("原生生成阶段速度：5.00 token/s（含原生采样/流回调，非纯内核时间）")
        revealText("端到端输出吞吐：2.00 token/s（不是纯解码速度）")
        compose.onNodeWithTag("developer-dashboard").performScrollToNode(hasTestTag("battery-temperature-chart"))
        compose.onNodeWithTag("battery-temperature-chart").performScrollTo().assertIsDisplayed()
        revealText("最近系统热状态：LIGHT · 轻度")
    }
}
