package com.goodlight.floatingvoicebubble

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeSettingsUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun selectedCloudModelShowsCatalogReasoningChoicesAndPersistsSelection() {
        val store = SettingsStore(composeRule.activity)
        val previous = store.load()
        try {
            store.update {
                it.copy(
                    correctionMode = CorrectionMode.BYOK,
                    byokEndpoint = "https://openrouter.ai/api/v1/chat/completions",
                    byokModel = "provider/reasoning-model",
                    reasoningEffort = ReasoningEffort.DEFAULT,
                    byokReasoningMetadataKnown = true,
                    byokReasoningEfforts = setOf(ReasoningEffort.LOW, ReasoningEffort.HIGH),
                )
            }
            composeRule.activityRule.scenario.recreate()
            composeRule.waitForIdle()

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

            composeRule.activityRule.scenario.recreate()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("reasoning-effort-control")
                .assertIsDisplayed()
                .assertTextContains("高")
        } finally {
            store.update { previous }
        }
    }

    @Test
    fun repairStrengthExposesAllSixLevelsAndPersistsMaximum() {
        val store = SettingsStore(composeRule.activity)
        val previous = store.load()
        try {
            store.update { it.copy(recognitionRepairMode = RecognitionRepairMode.NORMAL) }
            composeRule.activityRule.scenario.recreate()
            composeRule.waitForIdle()

            composeRule.onNodeWithTag("repair-strength-control").assertIsDisplayed().performClick()
            listOf(
                "語句は直さない",
                "確信できる誤認だけ",
                "明らかな誤認を修復",
                "文脈・候補から積極修復",
                "音と文脈から大胆に置換",
                "誤認と判断した語句を最大限修復",
            ).forEach { label ->
                assertTrue(
                    "repair-strength menu is missing '$label'",
                    composeRule.onAllNodesWithText(label, useUnmergedTree = true)
                        .fetchSemanticsNodes()
                        .isNotEmpty(),
                )
            }
            composeRule.onNodeWithText("誤認と判断した語句を最大限修復", useUnmergedTree = true).performClick()

            composeRule.waitUntil(timeoutMillis = 5_000) {
                SettingsStore(composeRule.activity).load().recognitionRepairMode == RecognitionRepairMode.MAXIMUM
            }
            assertEquals(RecognitionRepairMode.MAXIMUM, SettingsStore(composeRule.activity).load().recognitionRepairMode)
            composeRule.onNodeWithTag("repair-strength-control")
                .assertIsDisplayed()
                .assertTextContains("誤認と判断した語句を最大限修復")
        } finally {
            store.update { previous }
        }
    }

    @Test
    fun manualOpenRouterModelDoesNotInventUnverifiedReasoningDepths() {
        val store = SettingsStore(composeRule.activity)
        val previous = store.load()
        try {
            store.update {
                it.copy(
                    correctionMode = CorrectionMode.BYOK,
                    byokEndpoint = "https://openrouter.ai/api/v1/chat/completions",
                    byokModel = "manual/unverified-model",
                    reasoningEffort = ReasoningEffort.HIGH,
                    byokReasoningMetadataKnown = false,
                    byokReasoningEfforts = emptySet(),
                )
            }
            composeRule.activityRule.scenario.recreate()
            composeRule.waitForIdle()

            composeRule.onNodeWithTag("reasoning-effort-control")
                .assertIsDisplayed()
                .assertTextContains("モデル既定")
            composeRule.onNodeWithTag("reasoning-effort-control").performClick()
            composeRule.onNodeWithText("高", useUnmergedTree = true).assertDoesNotExist()
            composeRule.onNodeWithTag("reasoning-effort-control")
                .assertIsDisplayed()
                .assertTextContains("モデル既定")
        } finally {
            store.update { previous }
        }
    }

    @Test
    fun persistedCorrectionFailureRemainsVisibleWithReasonFallbackAndTransportEvidence() {
        val status = CorrectionStatusStore(composeRule.activity)
        try {
            status.saveFailure(
                LastCorrectionFailure(
                    occurredAtMs = System.currentTimeMillis(),
                    provider = "openai_compatible",
                    model = "glm-4.7",
                    reasoning = "思考ON",
                    latencyMs = 12_345L,
                    reason = "Read timed out",
                    fallback = "音声認識結果",
                    attempts = 1,
                    failureStage = "network-timeout",
                    errorClass = "SocketTimeoutException",
                    responsePresent = false,
                    integrityResult = "not-run",
                    endpoint = "https://api.z.ai/api/coding/paas/v4/chat/completions",
                    reasoningWire = "thinking.type=enabled",
                    attemptTimingSummary = "attempt=1 connect=18ms write=2ms headers=11001ms total=12000ms",
                ),
            )
            composeRule.activityRule.scenario.recreate()
            composeRule.waitForIdle()

            composeRule.onNodeWithTag("last-correction-failure").assertIsDisplayed()
            composeRule.onNodeWithText("前回の文章補正に失敗しました", substring = false).assertIsDisplayed()
            composeRule.onNodeWithText("Read timed out", substring = false).assertIsDisplayed()
            composeRule.onNodeWithText("送信設定: thinking.type=enabled", substring = false).assertIsDisplayed()
            composeRule.onNodeWithText("通信計測:", substring = true).assertIsDisplayed()
            composeRule.onNodeWithText("応答開始=11001ms", substring = true).assertIsDisplayed()
            composeRule.onNodeWithText("結果: 音声認識結果", substring = false).assertIsDisplayed()
            composeRule.onNodeWithText("instrumentation-only-key", substring = true).assertDoesNotExist()
        } finally {
            status.clearFailure()
        }
    }

    @Test
    fun mainSettingsDoNotExposeLegacyOrAmbiguousDeveloperLanguage() {
        composeRule.onNodeWithText("詳細", substring = false).assertDoesNotExist()
        composeRule.onNodeWithText("フィラー", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("安全ガード", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("ASR", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("読点「、」を追加", substring = false).assertIsDisplayed()
        composeRule.onNodeWithText("句点「。」を追加", substring = false).assertIsDisplayed()
        composeRule.onNodeWithText("「えー」「あのー」等を削除", substring = false).assertIsDisplayed()
    }
}
