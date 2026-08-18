package com.goodlight.floatingvoicebubble

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppProfileDraftRecreationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<AppProfilesActivity>()

    @Test
    fun unsavedProfileDraftSurvivesActivityRecreation() {
        val packageName = "com.example.voicebubble.draft${System.nanoTime()}"
        val store = AppProfileStore(composeRule.activity)
        try {
            composeRule.onAllNodes(hasSetTextAction())[0].performTextInput(packageName)
            composeRule.onNodeWithText("追加").performClick()
            composeRule.onNodeWithText("補正なし").performClick()
            composeRule.onNodeWithText("補正なし").assertIsSelected()

            composeRule.activityRule.scenario.recreate()

            composeRule.onNodeWithText("補正なし").assertIsSelected()
        } finally {
            store.delete(packageName)
        }
    }
}
