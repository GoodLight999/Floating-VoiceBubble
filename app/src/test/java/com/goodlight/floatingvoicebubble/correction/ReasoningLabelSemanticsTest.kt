package com.goodlight.floatingvoicebubble.correction

import com.goodlight.floatingvoicebubble.ReasoningEffort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReasoningLabelSemanticsTest {
    @Test
    fun openRouterMaxStaysMaxFromCapabilityThroughWireLabel() {
        val endpoint = "https://openrouter.ai/api/v1/chat/completions"
        val model = "deepseek/deepseek-v4-flash-0731"

        assertTrue(ReasoningEffort.MAX in ReasoningCapabilities.capability(endpoint, model).choices)
        assertEquals(ReasoningEffort.MAX, ReasoningCapabilities.normalize(endpoint, model, ReasoningEffort.MAX))
        assertEquals("max", ReasoningCapabilities.label(endpoint, model, ReasoningEffort.MAX))
        assertEquals("max", ReasoningCapabilities.effortString(endpoint, model, ReasoningEffort.MAX))
        assertEquals(
            "max",
            OpenAiProviderCompatibility.resolve(endpoint, model, ReasoningEffort.MAX).openRouterReasoningEffort,
        )
    }

    @Test
    fun gemini25LabelsExposeActualNumericBudgetRatherThanInventedDepthNames() {
        val endpoint = "https://generativelanguage.googleapis.com/v1beta"
        val model = "gemini-2.5-flash"

        assertEquals("思考OFF（0）", ReasoningCapabilities.label(endpoint, model, ReasoningEffort.NONE))
        assertEquals("512トークン", ReasoningCapabilities.label(endpoint, model, ReasoningEffort.MINIMAL))
        assertEquals("1,024トークン", ReasoningCapabilities.label(endpoint, model, ReasoningEffort.LOW))
        assertEquals("4,096トークン", ReasoningCapabilities.label(endpoint, model, ReasoningEffort.MEDIUM))
        assertEquals("8,192トークン", ReasoningCapabilities.label(endpoint, model, ReasoningEffort.HIGH))

        assertEquals(0, ReasoningCapabilities.geminiThinking(endpoint, model, ReasoningEffort.NONE)?.budgetTokens)
        assertEquals(8192, ReasoningCapabilities.geminiThinking(endpoint, model, ReasoningEffort.HIGH)?.budgetTokens)
    }

    @Test
    fun gemini25ProDoesNotPretendThinkingCanBeDisabled() {
        val endpoint = "https://generativelanguage.googleapis.com/v1beta"
        val model = "gemini-2.5-pro"
        val choices = ReasoningCapabilities.capability(endpoint, model).choices

        assertFalse(ReasoningEffort.NONE in choices)
        assertEquals(ReasoningEffort.DEFAULT, ReasoningCapabilities.normalize(endpoint, model, ReasoningEffort.NONE))
        assertEquals("モデル既定", ReasoningCapabilities.label(endpoint, model, ReasoningEffort.NONE))
    }

    @Test
    fun gemini3FamiliesExposeOnlyDocumentedThinkingLevels() {
        val endpoint = "https://generativelanguage.googleapis.com/v1beta"

        val flash37 = ReasoningCapabilities.capability(endpoint, "gemini-3.7-flash").choices
        assertEquals(
            listOf(
                ReasoningEffort.DEFAULT,
                ReasoningEffort.LOW,
                ReasoningEffort.MEDIUM,
                ReasoningEffort.HIGH,
            ),
            flash37,
        )
        assertFalse(ReasoningEffort.MINIMAL in flash37)

        val flash35 = ReasoningCapabilities.capability(endpoint, "gemini-3.5-flash").choices
        assertTrue(ReasoningEffort.MINIMAL in flash35)
        assertTrue(ReasoningEffort.LOW in flash35)
        assertTrue(ReasoningEffort.MEDIUM in flash35)
        assertTrue(ReasoningEffort.HIGH in flash35)
    }
}
