package com.goodlight.floatingvoicebubble

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeSettingsUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun reasoningControlIsVisibleOnFirstPaintAndActuallyPersistsSelection() {
        val store = SettingsStore(composeRule.activity)
        val previous = store.load()
        try {
            composeRule.onNodeWithTag("compact-home-header").assertIsDisplayed()
            composeRule.onNodeWithTag("primary-correction-card").assertIsDisplayed()
            composeRule.onNodeWithTag("reasoning-effort-control").assertIsDisplayed()
            composeRule.onNodeWithTag("repair-strength-control").assertIsDisplayed()

            composeRule.onNodeWithTag("reasoning-effort-control").performClick()
            composeRule.onNodeWithText("高", useUnmergedTree = true).performClick()
            composeRule.waitUntil(timeoutMillis = 5_000) {
                SettingsStore(composeRule.activity).load().reasoningEffort == ReasoningEffort.HIGH
            }
            assertEquals(ReasoningEffort.HIGH, SettingsStore(composeRule.activity).load().reasoningEffort)
        } finally {
            store.update { previous }
        }
    }
}
