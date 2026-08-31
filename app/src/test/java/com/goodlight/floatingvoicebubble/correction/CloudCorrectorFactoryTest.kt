package com.goodlight.floatingvoicebubble.correction

import org.junit.Assert.assertEquals
import org.junit.Test

class CloudCorrectorFactoryTest {
    @Test
    fun detectsAnthropicMessagesApi() {
        assertEquals(
            CloudCorrectorFactory.Protocol.ANTHROPIC,
            CloudCorrectorFactory.protocolFor("https://api.anthropic.com/v1/messages"),
        )
    }

    @Test
    fun detectsGeminiGenerateContentApi() {
        assertEquals(
            CloudCorrectorFactory.Protocol.GEMINI,
            CloudCorrectorFactory.protocolFor("https://generativelanguage.googleapis.com/v1beta"),
        )
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.6-flash:generateContent",
            GeminiApiCorrector.targetUrl(
                "https://generativelanguage.googleapis.com/v1beta/",
                "gemini-3.6-flash",
            ),
        )
    }

    @Test
    fun preservesOpenAiCompatibleAsDefault() {
        assertEquals(
            CloudCorrectorFactory.Protocol.OPENAI_COMPATIBLE,
            CloudCorrectorFactory.protocolFor("https://openrouter.ai/api/v1/chat/completions"),
        )
    }
}
