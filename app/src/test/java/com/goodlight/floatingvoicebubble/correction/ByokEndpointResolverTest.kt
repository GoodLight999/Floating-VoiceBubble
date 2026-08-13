package com.goodlight.floatingvoicebubble.correction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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
    fun addsV1ForVersionlessOpenAiCompatibleRoots() {
        val root = ByokEndpointResolver.resolve("https://llm.example.com")
        assertEquals("https://llm.example.com/v1/chat/completions", root.generationUrl)
        assertEquals("https://llm.example.com/v1/models", root.modelsUrl)

        val apiRoot = ByokEndpointResolver.resolve("https://openrouter.ai/api")
        assertEquals("https://openrouter.ai/api/v1/chat/completions", apiRoot.generationUrl)
        assertEquals("https://openrouter.ai/api/v1/models", apiRoot.modelsUrl)
    }

    @Test
    fun repairsCommonOpenAiEndpointMistakes() {
        val singular = ByokEndpointResolver.resolve("https://llm.example.com/v1/chat/completion")
        assertEquals("https://llm.example.com/v1/chat/completions", singular.generationUrl)
        assertEquals("https://llm.example.com/v1/models", singular.modelsUrl)

        val legacy = ByokEndpointResolver.resolve("https://llm.example.com/v1/completions")
        assertEquals("https://llm.example.com/v1/chat/completions", legacy.generationUrl)
        assertEquals("https://llm.example.com/v1/models", legacy.modelsUrl)

        val models = ByokEndpointResolver.resolve("https://llm.example.com/v1/models?foo=bar#ignored")
        assertEquals("https://llm.example.com/v1/chat/completions", models.generationUrl)
        assertEquals("https://llm.example.com/v1/models", models.modelsUrl)
    }

    @Test
    fun normalizesAnthropicBaseMessagesAndModelsUrls() {
        val root = ByokEndpointResolver.resolve("https://api.anthropic.com")
        assertEquals("https://api.anthropic.com/v1/messages", root.generationUrl)
        assertEquals("https://api.anthropic.com/v1/models", root.modelsUrl)

        val messages = ByokEndpointResolver.resolve("https://api.anthropic.com/v1/messages")
        assertEquals("https://api.anthropic.com/v1/messages", messages.generationUrl)
        assertEquals("https://api.anthropic.com/v1/models", messages.modelsUrl)

        val models = ByokEndpointResolver.resolve("https://api.anthropic.com/v1/models")
        assertEquals("https://api.anthropic.com/v1/messages", models.generationUrl)
        assertEquals("https://api.anthropic.com/v1/models", models.modelsUrl)
    }

    @Test
    fun normalizesGeminiOfficialBase() {
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

    @Test
    fun rejectsInsecureOrCredentialBearingEndpoints() {
        assertThrows(IllegalArgumentException::class.java) {
            ByokEndpointResolver.resolve("http://llm.example.com/v1")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ByokEndpointResolver.resolve("https://user:secret@llm.example.com/v1")
        }
    }
}
