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

        // Sonnet 4.5 uses manual extended-thinking budgets rather than the adaptive
        // output_config.effort ladder. Floating VoiceBubble deliberately does not invent a
        // fake depth mapping for that API generation, so only provider/model default is exposed.
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
