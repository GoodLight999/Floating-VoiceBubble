package com.goodlight.floatingvoicebubble.correction

import com.goodlight.floatingvoicebubble.ReasoningEffort
import org.junit.Assert.assertEquals
import org.junit.Test

class ReasoningWireDescriptorTest {
    @Test
    fun describesExactProviderWireControlsWithoutSecrets() {
        assertEquals(
            "reasoning_effort=max",
            ReasoningWireDescriptor.describe(
                "https://api.openai.com/v1/chat/completions",
                "gpt-5.6-terra",
                ReasoningEffort.MAX,
            ),
        )
        assertEquals(
            "reasoning.effort=high",
            ReasoningWireDescriptor.describe(
                "https://openrouter.ai/api/v1/chat/completions",
                "provider/model",
                ReasoningEffort.HIGH,
            ),
        )
        assertEquals(
            "thinking.type=disabled",
            ReasoningWireDescriptor.describe(
                "https://api.z.ai/api/paas/v4/chat/completions",
                "glm-4.7",
                ReasoningEffort.NONE,
            ),
        )
        assertEquals(
            "thinking.type=adaptive,output_config.effort=xhigh",
            ReasoningWireDescriptor.describe(
                "https://api.anthropic.com/v1/messages",
                "claude-opus-5",
                ReasoningEffort.XHIGH,
            ),
        )
        assertEquals(
            "thinkingConfig.thinkingBudget=0",
            ReasoningWireDescriptor.describe(
                "https://generativelanguage.googleapis.com/v1beta",
                "gemini-2.5-flash",
                ReasoningEffort.NONE,
            ),
        )
        assertEquals(
            "thinkingConfig.thinkingLevel=medium",
            ReasoningWireDescriptor.describe(
                "https://generativelanguage.googleapis.com/v1beta",
                "gemini-3.7-flash",
                ReasoningEffort.MEDIUM,
            ),
        )
    }

    @Test
    fun providerDefaultAndUnknownCompatibleApisExplicitlyShowOmission() {
        assertEquals(
            "optional-reasoning=<omitted:model-default>",
            ReasoningWireDescriptor.describe(
                "https://api.z.ai/api/paas/v4/chat/completions",
                "glm-4.7",
                ReasoningEffort.DEFAULT,
            ),
        )
        assertEquals(
            "optional-reasoning=<omitted:model-default>",
            ReasoningWireDescriptor.describe(
                "https://llm.example.test/v1/chat/completions",
                "custom-model",
                ReasoningEffort.HIGH,
            ),
        )
    }
}
