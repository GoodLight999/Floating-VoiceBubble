package com.goodlight.floatingvoicebubble.correction

import com.goodlight.floatingvoicebubble.ReasoningEffort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReasoningEffortProviderTest {
    private val openAiEndpoint = "https://api.openai.com/v1/chat/completions"
    private val anthropicEndpoint = "https://api.anthropic.com/v1/messages"

    @Test
    fun legacyGpt5UsesOnlyMinimalLowMediumHigh() {
        assertEquals(
            listOf(
                ReasoningEffort.DEFAULT,
                ReasoningEffort.MINIMAL,
                ReasoningEffort.LOW,
                ReasoningEffort.MEDIUM,
                ReasoningEffort.HIGH,
            ),
            ReasoningCapabilities.capability(openAiEndpoint, "gpt-5").choices,
        )
        assertNull(OpenAiProviderCompatibility.resolve(openAiEndpoint, "gpt-5", ReasoningEffort.DEFAULT).openAiReasoningEffort)
        assertEquals("minimal", OpenAiProviderCompatibility.resolve(openAiEndpoint, "gpt-5", ReasoningEffort.MINIMAL).openAiReasoningEffort)
        assertEquals("high", OpenAiProviderCompatibility.resolve(openAiEndpoint, "gpt-5", ReasoningEffort.MAX).openAiReasoningEffort)
        assertNull(OpenAiProviderCompatibility.resolve(openAiEndpoint, "gpt-5", ReasoningEffort.NONE).openAiReasoningEffort)
    }

    @Test
    fun gpt52RemovesMinimalAndSupportsXHigh() {
        assertEquals(
            listOf(
                ReasoningEffort.DEFAULT,
                ReasoningEffort.NONE,
                ReasoningEffort.LOW,
                ReasoningEffort.MEDIUM,
                ReasoningEffort.HIGH,
                ReasoningEffort.XHIGH,
            ),
            ReasoningCapabilities.capability(openAiEndpoint, "gpt-5.2").choices,
        )
        assertEquals("low", OpenAiProviderCompatibility.resolve(openAiEndpoint, "gpt-5.2", ReasoningEffort.MINIMAL).openAiReasoningEffort)
        assertEquals("xhigh", OpenAiProviderCompatibility.resolve(openAiEndpoint, "gpt-5.2", ReasoningEffort.XHIGH).openAiReasoningEffort)
    }

    @Test
    fun gpt56SupportsMaxAsDistinctWireValue() {
        assertEquals(
            listOf(
                ReasoningEffort.DEFAULT,
                ReasoningEffort.NONE,
                ReasoningEffort.LOW,
                ReasoningEffort.MEDIUM,
                ReasoningEffort.HIGH,
                ReasoningEffort.XHIGH,
                ReasoningEffort.MAX,
            ),
            ReasoningCapabilities.capability(openAiEndpoint, "gpt-5.6-terra").choices,
        )
        assertEquals("max", OpenAiProviderCompatibility.resolve(openAiEndpoint, "gpt-5.6-terra", ReasoningEffort.MAX).openAiReasoningEffort)
        assertEquals("low", OpenAiProviderCompatibility.resolve(openAiEndpoint, "gpt-5.6-terra", ReasoningEffort.MINIMAL).openAiReasoningEffort)
    }

    @Test
    fun proFamiliesExposeOnlyDocumentedHigherEfforts() {
        val expected = listOf(ReasoningEffort.DEFAULT, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH, ReasoningEffort.XHIGH)
        assertEquals(expected, ReasoningCapabilities.capability(openAiEndpoint, "gpt-5.2-pro").choices)
        assertEquals(expected, ReasoningCapabilities.capability(openAiEndpoint, "gpt-5.4-pro").choices)
        assertEquals(expected, ReasoningCapabilities.capability(openAiEndpoint, "gpt-5.5-pro").choices)
    }

    @Test
    fun unknownOpenAiModelDoesNotReceiveGuessedReasoningParameter() {
        val options = OpenAiProviderCompatibility.resolve(openAiEndpoint, "unknown-model", ReasoningEffort.HIGH)
        assertNull(options.openAiReasoningEffort)
    }

    @Test
    fun openRouterOmitsDefaultAndPreservesGatewayMax() {
        val endpoint = "https://openrouter.ai/api/v1/chat/completions"
        assertNull(
            OpenAiProviderCompatibility.resolve(endpoint, "provider/model", ReasoningEffort.DEFAULT)
                .openRouterReasoningEffort,
        )
        assertEquals(
            "max",
            OpenAiProviderCompatibility.resolve(endpoint, "provider/model", ReasoningEffort.MAX)
                .openRouterReasoningEffort,
        )
    }

    @Test
    fun zaiUsesDocumentedBinaryThinkingAndDefaultMeansOmit() {
        val endpoint = "https://api.z.ai/api/paas/v4/chat/completions"
        assertNull(OpenAiProviderCompatibility.resolve(endpoint, "GLM-4.7", ReasoningEffort.DEFAULT).zaiThinkingEnabled)
        assertEquals(false, OpenAiProviderCompatibility.resolve(endpoint, "GLM-4.7", ReasoningEffort.NONE).zaiThinkingEnabled)
        assertEquals(true, OpenAiProviderCompatibility.resolve(endpoint, "GLM-4.7", ReasoningEffort.HIGH).zaiThinkingEnabled)

        val choices = ReasoningCapabilities.capability(endpoint, "GLM-4.7").choices
        assertEquals(listOf(ReasoningEffort.DEFAULT, ReasoningEffort.NONE, ReasoningEffort.HIGH), choices)
    }

    @Test
    fun fableAndMythosDoNotOfferUnsupportedThinkingOff() {
        val fable = ReasoningCapabilities.capability(anthropicEndpoint, "claude-fable-5")
        assertFalse(ReasoningEffort.NONE in fable.choices)
        assertTrue(ReasoningEffort.XHIGH in fable.choices)
        assertTrue(ReasoningEffort.MAX in fable.choices)
        assertNull(ReasoningCapabilities.anthropicThinkingType(anthropicEndpoint, "claude-fable-5", ReasoningEffort.NONE))

        val mythosPreview = ReasoningCapabilities.capability(anthropicEndpoint, "claude-mythos-preview")
        assertFalse(ReasoningEffort.NONE in mythosPreview.choices)
        assertFalse(ReasoningEffort.XHIGH in mythosPreview.choices)
        assertTrue(ReasoningEffort.MAX in mythosPreview.choices)
    }

    @Test
    fun opus5AllowsThinkingOffAndFullEffortLadder() {
        val choices = ReasoningCapabilities.capability(anthropicEndpoint, "claude-opus-5").choices
        assertTrue(ReasoningEffort.NONE in choices)
        assertTrue(ReasoningEffort.XHIGH in choices)
        assertTrue(ReasoningEffort.MAX in choices)
        assertEquals("disabled", ReasoningCapabilities.anthropicThinkingType(anthropicEndpoint, "claude-opus-5", ReasoningEffort.NONE))
        assertEquals("adaptive", ReasoningCapabilities.anthropicThinkingType(anthropicEndpoint, "claude-opus-5", ReasoningEffort.MAX))
        assertEquals("max", ReasoningCapabilities.anthropicEffort(anthropicEndpoint, "claude-opus-5", ReasoningEffort.MAX))
    }

    @Test
    fun genericCompatibleEndpointNeverReceivesProviderSpecificReasoning() {
        val options = OpenAiProviderCompatibility.resolve(
            "https://example.invalid/v1/chat/completions",
            "whatever-model",
            ReasoningEffort.HIGH,
        )
        assertNull(options.openAiReasoningEffort)
        assertNull(options.openRouterReasoningEffort)
        assertNull(options.zaiThinkingEnabled)
        assertTrue(ReasoningCapabilities.capability("https://example.invalid/v1/chat/completions", "whatever-model").choices == listOf(ReasoningEffort.DEFAULT))
    }
}
