package com.goodlight.floatingvoicebubble.correction

import org.junit.Assert.assertEquals
import org.junit.Test

class ByokEndpointResolverTest {
    @Test
    fun normalizesOpenAiCompatibleBaseAndCompletionUrls() {
        val base = ByokEndpointResolver.resolve("https://openrouter.ai/api/v1")
        assertEquals("https://openrouter.ai/api/v1/chat/completions", base.generationUrl)
        assertEquals("https://openrouter.ai/api/v1/models", base.modelsUrl)

        val full = ByokEndpointResolver.resolve("https://api.openai.com/v1/chat/completions")
        assertEquals("https://api.openai.com/v1/chat/completions", full.generationUrl)
        assertEquals("https://api.openai.com/v1/models", full.modelsUrl)
    }

    @Test
    fun normalizesAnthropicAndGeminiOfficialBases() {
        val anthropic = ByokEndpointResolver.resolve("https://api.anthropic.com")
        assertEquals("https://api.anthropic.com/v1/messages", anthropic.generationUrl)
        assertEquals("https://api.anthropic.com/v1/models", anthropic.modelsUrl)

        val gemini = ByokEndpointResolver.resolve("https://generativelanguage.googleapis.com/v1beta")
        assertEquals("https://generativelanguage.googleapis.com/v1beta", gemini.generationUrl)
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models", gemini.modelsUrl)
    }

    @Test
    fun googleOpenAiCompatibilityStaysOpenAiProtocol() {
        val resolved = ByokEndpointResolver.resolve("https://generativelanguage.googleapis.com/v1beta/openai")
        assertEquals(CloudCorrectorFactory.Protocol.OPENAI_COMPATIBLE, resolved.protocol)
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
            resolved.generationUrl,
        )
    }
}
