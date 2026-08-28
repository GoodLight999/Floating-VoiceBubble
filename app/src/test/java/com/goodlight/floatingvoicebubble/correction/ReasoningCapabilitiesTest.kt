package com.goodlight.floatingvoicebubble.correction

import com.goodlight.floatingvoicebubble.ReasoningEffort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReasoningCapabilitiesTest {
    @Test
    fun defaultNeverForcesOptionalReasoningControls() {
        assertNull(
            ReasoningCapabilities.effortString(
                "https://api.openai.com/v1/chat/completions",
                "gpt-5",
                ReasoningEffort.DEFAULT,
            ),
        )
        assertNull(
            ReasoningCapabilities.effortString(
                "https://openrouter.ai/api/v1/chat/completions",
                "provider/model",
                ReasoningEffort.DEFAULT,
            ),
        )
        assertNull(
            ReasoningCapabilities.zaiThinking(
                "https://api.z.ai/api/paas/v4/chat/completions",
                "glm-4.7",
                ReasoningEffort.DEFAULT,
            ),
        )
        assertNull(
            ReasoningCapabilities.zaiReasoningEffort(
                "https://api.z.ai/api/paas/v4/chat/completions",
                "glm-5.3",
                ReasoningEffort.DEFAULT,
            ),
        )
        assertNull(
            ReasoningCapabilities.anthropicThinkingType(
                "https://api.anthropic.com/v1/messages",
                "claude-sonnet-4-6",
                ReasoningEffort.DEFAULT,
            ),
        )
        assertNull(
            ReasoningCapabilities.anthropicEffort(
                "https://api.anthropic.com/v1/messages",
                "claude-sonnet-4-6",
                ReasoningEffort.DEFAULT,
            ),
        )
        assertNull(
            ReasoningCapabilities.geminiThinking(
                "https://generativelanguage.googleapis.com/v1beta",
                "gemini-2.5-flash",
                ReasoningEffort.DEFAULT,
            ),
        )
    }

    @Test
    fun zai47KeepsBinaryThinkingContract() {
        val endpoint = "https://api.z.ai/api/paas/v4/chat/completions"
        val capability = ReasoningCapabilities.capability(endpoint, "glm-4.7")
        assertEquals(
            listOf(ReasoningEffort.DEFAULT, ReasoningEffort.NONE, ReasoningEffort.HIGH),
            capability.choices,
        )
        assertEquals(false, ReasoningCapabilities.zaiThinking(endpoint, "glm-4.7", ReasoningEffort.NONE))
        assertEquals(true, ReasoningCapabilities.zaiThinking(endpoint, "glm-4.7", ReasoningEffort.HIGH))
        assertNull(ReasoningCapabilities.zaiReasoningEffort(endpoint, "glm-4.7", ReasoningEffort.HIGH))
    }

    @Test
    fun zai53CannotDisableThinkingAndUsesDocumentedEffortLevels() {
        val endpoint = "https://api.z.ai/api/paas/v4/chat/completions"
        val capability = ReasoningCapabilities.capability(endpoint, "glm-5.3")
        assertEquals(
            listOf(ReasoningEffort.DEFAULT, ReasoningEffort.LOW, ReasoningEffort.HIGH, ReasoningEffort.MAX),
            capability.choices,
        )
        assertFalse(ReasoningEffort.NONE in capability.choices)
        assertEquals(true, ReasoningCapabilities.zaiThinking(endpoint, "glm-5.3", ReasoningEffort.LOW))
        assertEquals(true, ReasoningCapabilities.zaiThinking(endpoint, "glm-5.3", ReasoningEffort.MAX))
        assertEquals("low", ReasoningCapabilities.zaiReasoningEffort(endpoint, "glm-5.3", ReasoningEffort.LOW))
        assertEquals("high", ReasoningCapabilities.zaiReasoningEffort(endpoint, "glm-5.3", ReasoningEffort.HIGH))
        assertEquals("max", ReasoningCapabilities.zaiReasoningEffort(endpoint, "glm-5.3", ReasoningEffort.MAX))
        assertEquals(ReasoningEffort.DEFAULT, ReasoningCapabilities.normalize(endpoint, "glm-5.3", ReasoningEffort.NONE))
    }

    @Test
    fun unknownZaiModelDoesNotReceiveGuessedReasoningControls() {
        val endpoint = "https://api.z.ai/api/paas/v4/chat/completions"
        val capability = ReasoningCapabilities.capability(endpoint, "glm-future-unknown")
        assertEquals(listOf(ReasoningEffort.DEFAULT), capability.choices)
        assertNull(ReasoningCapabilities.zaiThinking(endpoint, "glm-future-unknown", ReasoningEffort.HIGH))
        assertNull(ReasoningCapabilities.zaiReasoningEffort(endpoint, "glm-future-unknown", ReasoningEffort.HIGH))
    }

    @Test
    fun anthropicAdaptiveModelsUseAdaptiveThinkingPlusEffort() {
        val endpoint = "https://api.anthropic.com/v1/messages"
        assertEquals(
            "adaptive",
            ReasoningCapabilities.anthropicThinkingType(endpoint, "claude-sonnet-4-6", ReasoningEffort.HIGH),
        )
        assertEquals(
            "high",
            ReasoningCapabilities.anthropicEffort(endpoint, "claude-sonnet-4-6", ReasoningEffort.HIGH),
        )
        assertEquals(
            "disabled",
            ReasoningCapabilities.anthropicThinkingType(endpoint, "claude-sonnet-4-6", ReasoningEffort.NONE),
        )
        assertNull(ReasoningCapabilities.anthropicEffort(endpoint, "claude-sonnet-4-6", ReasoningEffort.NONE))
    }

    @Test
    fun anthropicLegacyModelDoesNotPretendToHaveDepthLadder() {
        val endpoint = "https://api.anthropic.com/v1/messages"
        val capability = ReasoningCapabilities.capability(endpoint, "claude-sonnet-4-5")

        assertEquals(listOf(ReasoningEffort.DEFAULT), capability.choices)
        assertNull(
            ReasoningCapabilities.anthropicEffort(
                endpoint,
                "claude-sonnet-4-5",
                ReasoningEffort.HIGH,
            ),
        )
        assertNull(
            ReasoningCapabilities.anthropicThinkingType(
                endpoint,
                "claude-sonnet-4-5",
                ReasoningEffort.NONE,
            ),
        )
    }

    @Test
    fun gemini25FlashUsesThinkingBudgetAndCanDisable() {
        val endpoint = "https://generativelanguage.googleapis.com/v1beta"
        val none = ReasoningCapabilities.geminiThinking(endpoint, "gemini-2.5-flash", ReasoningEffort.NONE)
        assertEquals(0, none?.budgetTokens)
        assertNull(none?.level)

        val high = ReasoningCapabilities.geminiThinking(endpoint, "gemini-2.5-flash", ReasoningEffort.HIGH)
        assertEquals(8192, high?.budgetTokens)
        assertNull(high?.level)
    }

    @Test
    fun gemini25ProDoesNotOfferNone() {
        val choices = ReasoningCapabilities.capability(
            "https://generativelanguage.googleapis.com/v1beta",
            "gemini-2.5-pro",
        ).choices
        assertFalse(ReasoningEffort.NONE in choices)
        assertTrue(ReasoningEffort.LOW in choices)
        assertTrue(ReasoningEffort.HIGH in choices)
    }

    @Test
    fun gemini3UsesThinkingLevelInsteadOfBudget() {
        val control = ReasoningCapabilities.geminiThinking(
            "https://generativelanguage.googleapis.com/v1beta",
            "gemini-3-flash",
            ReasoningEffort.MEDIUM,
        )
        assertEquals("medium", control?.level)
        assertNull(control?.budgetTokens)
    }

    @Test
    fun unknownGenericCompatibleApiHasProviderDefaultOnly() {
        val capability = ReasoningCapabilities.capability(
            "https://llm.example.com/v1/chat/completions",
            "mystery-model",
        )
        assertEquals(listOf(ReasoningEffort.DEFAULT), capability.choices)
    }
}
