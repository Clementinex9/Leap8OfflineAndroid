package com.leap8.offline.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.leap8.offline.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Only navigates: never changes diagnostics recording, clears history, runs AI or edits keys. */
@RunWith(AndroidJUnit4::class)
class DeveloperNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun profileEntryOpensDeveloperDashboardAndHeaderBackReturnsToProfile() {
        compose.onNodeWithText("我的").assertIsDisplayed().performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("developer-entry").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("developer-entry").performScrollTo().assertIsDisplayed().performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("developer-dashboard").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("developer-dashboard").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").assertIsDisplayed().performClick()
        compose.onNodeWithTag("developer-entry").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("你的学习设置").performScrollTo().assertIsDisplayed()
    }
}
