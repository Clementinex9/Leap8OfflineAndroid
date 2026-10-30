package com.leap8.offline.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.leap8.offline.ai.LocalAiMemoryStage
import com.leap8.offline.ai.LocalAiMemoryStatus
import com.leap8.offline.ui.theme.Leap8Theme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Renders a simulated status only: no AI load, memory pressure or system-setting changes. */
@RunWith(AndroidJUnit4::class)
class LocalAiMemoryPanelTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun blockedStatusAndSafetyLimitsRemainReachableAtLargeFontScale() {
        val message = "自动清理已完成，但当前系统可用内存仍不足以安全加载这个本地模型。" +
            "请先保存其他应用中的工作并关闭大型应用后重试；本应用保留你的作文、学习进度及离线模型，不会自动切换到联网服务。"
        val limits = "不足时自动释放本应用的语音模型和缓存，再次检测；不会删除模型文件、学习记录或 API 密钥，也不会强制关闭其他应用。仍不足时停止本地加载，请关闭其他大型应用后重试；如需 API，可自行切换，不会自动联网。"
        val status = LocalAiMemoryStatus(
            stage = LocalAiMemoryStage.BLOCKED,
            availableBytes = 1024L * 1024 * 1024,
            requiredBytes = 4L * 1024 * 1024 * 1024,
            beforeBytes = 768L * 1024 * 1024,
            cleanupAttempted = true,
            message = message
        )
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 1.6f)) {
                Leap8Theme {
                    Surface(Modifier.fillMaxSize()) {
                        Column(
                            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)
                        ) {
                            LocalAiMemoryPanel(status)
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("local-ai-memory-protection").assertExists()
        compose.onNodeWithText("自动内存保护 · 已开启").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("当前内存仍不足，已阻止高风险加载").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("1.00 GiB / 4.00 GiB").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("清理前可用 / 最近复检可用").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(message).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(limits).performScrollTo().assertIsDisplayed()
    }
}
