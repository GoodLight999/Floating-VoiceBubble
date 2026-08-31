package com.goodlight.floatingvoicebubble

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CorrectionSetupDraftRecreationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<CorrectionSetupActivity>()

    @Test
    fun nonSecretByokDraftSurvivesActivityRecreation() {
        val field = composeRule.onAllNodes(hasSetTextAction())[0]
        field.performTextClearance()
        field.performTextInput("https://example.invalid/v1")
        field.assertTextContains("https://example.invalid/v1")

        composeRule.activityRule.scenario.recreate()

        composeRule.onAllNodes(hasSetTextAction())[0]
            .assertTextContains("https://example.invalid/v1")
    }
}
