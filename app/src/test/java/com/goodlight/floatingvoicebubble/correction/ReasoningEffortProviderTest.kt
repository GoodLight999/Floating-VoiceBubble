package com.goodlight.floatingvoicebubble.correction

import com.goodlight.floatingvoicebubble.ReasoningEffort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReasoningEffortProviderTest {
    @Test
    fun openAiUsesOnlyDocumentedDepthsAndOmitsDefault() {
        val endpoint = "https://api.openai.com/v1/chat/completions"
        assertNull(OpenAiProviderCompatibility.resolve(endpoint, "gpt-5", ReasoningEffort.DEFAULT).openAiReasoningEffort)
        assertEquals("minimal", OpenAiProviderCompatibility.resolve(endpoint, "gpt-5", ReasoningEffort.MINIMAL).openAiReasoningEffort)
        assertEquals("high", OpenAiProviderCompatibility.resolve(endpoint, "gpt-5", ReasoningEffort.HIGH).openAiReasoningEffort)
        assertEquals("xhigh", OpenAiProviderCompatibility.resolve(endpoint, "gpt-5", ReasoningEffort.MAX).openAiReasoningEffort)
    }

    @Test
    fun unknownOpenAiModelDoesNotReceiveGuessedReasoningParameter() {
        val endpoint = "https://api.openai.com/v1/chat/completions"
        val options = OpenAiProviderCompatibility.resolve(endpoint, "unknown-model", ReasoningEffort.HIGH)
        assertNull(options.openAiReasoningEffort)
    }

    @Test
    fun openRouterOmitsDefaultAndNormalizesLegacyMaxToXHigh() {
        val endpoint = "https://openrouter.ai/api/v1/chat/completions"
        assertNull(
            OpenAiProviderCompatibility.resolve(endpoint, "provider/model", ReasoningEffort.DEFAULT)
                .openRouterReasoningEffort,
        )
        assertEquals(
            "xhigh",
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
