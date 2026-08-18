package com.goodlight.floatingvoicebubble

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DictionaryDraftRecreationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<DictionaryActivity>()

    @Test
    fun unsavedDictionaryDraftSurvivesActivityRecreation() {
        composeRule.onNodeWithText("＋ 新規登録").performClick()
        composeRule.onNodeWithTag("dictionary-editor-title").assertIsDisplayed()
        composeRule.onNodeWithTag("dictionary-term-field").performTextInput("再生成テスト語")
        composeRule.onNodeWithTag("dictionary-term-field").assertTextContains("再生成テスト語")

        composeRule.activityRule.scenario.recreate()

        composeRule.onNodeWithTag("dictionary-editor-title").assertIsDisplayed()
        composeRule.onNodeWithTag("dictionary-term-field").assertTextContains("再生成テスト語")
    }
}
