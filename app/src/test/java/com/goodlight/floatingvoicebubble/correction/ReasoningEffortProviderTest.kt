package com.goodlight.floatingvoicebubble.correction

import com.goodlight.floatingvoicebubble.ReasoningEffort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReasoningEffortProviderTest {
    @Test
    fun openAiMapsEveryExplicitDepthAndOmitsDefault() {
        val endpoint = "https://api.openai.com/v1/chat/completions"
        val expected = mapOf(
            ReasoningEffort.DEFAULT to null,
            ReasoningEffort.NONE to "none",
            ReasoningEffort.MINIMAL to "minimal",
            ReasoningEffort.LOW to "low",
            ReasoningEffort.MEDIUM to "medium",
            ReasoningEffort.HIGH to "high",
            ReasoningEffort.XHIGH to "xhigh",
            ReasoningEffort.MAX to "xhigh",
        )
        expected.forEach { (effort, wireValue) ->
            assertEquals(wireValue, OpenAiProviderCompatibility.resolve(endpoint, "gpt-test", effort).openAiReasoningEffort)
        }
    }

    @Test
    fun openRouterPreservesMaxDepth() {
        val options = OpenAiProviderCompatibility.resolve(
            "https://openrouter.ai/api/v1/chat/completions",
            "provider/model",
            ReasoningEffort.MAX,
        )
        assertEquals("max", options.openRouterReasoningEffort)
        assertNull(options.openAiReasoningEffort)
    }

    @Test
    fun zaiUsesDocumentedBinaryThinkingBoundary() {
        val endpoint = "https://api.z.ai/api/paas/v4/chat/completions"
        listOf(ReasoningEffort.DEFAULT, ReasoningEffort.NONE, ReasoningEffort.MINIMAL).forEach { effort ->
            assertEquals(false, OpenAiProviderCompatibility.resolve(endpoint, "GLM-4.7", effort).zaiThinkingEnabled)
        }
        listOf(
            ReasoningEffort.LOW,
            ReasoningEffort.MEDIUM,
            ReasoningEffort.HIGH,
            ReasoningEffort.XHIGH,
            ReasoningEffort.MAX,
        ).forEach { effort ->
            assertEquals(true, OpenAiProviderCompatibility.resolve(endpoint, "GLM-4.7", effort).zaiThinkingEnabled)
        }
    }
}
