package com.goodlight.floatingvoicebubble.correction

import java.net.URI

object CloudCorrectorFactory {
    enum class Protocol { OPENAI_COMPATIBLE, ANTHROPIC, GEMINI }

    fun create(endpoint: String, model: String, apiKey: String): TextCorrector {
        val protocol = protocolFor(endpoint)
        return when (protocol) {
            Protocol.OPENAI_COMPATIBLE -> OpenAiCompatibleCorrector(endpoint, model, apiKey)
            Protocol.ANTHROPIC -> AnthropicCorrector(endpoint, model, apiKey)
            Protocol.GEMINI -> GeminiApiCorrector(endpoint, model, apiKey)
        }
    }

    fun protocolFor(endpoint: String): Protocol {
        val host = runCatching { URI(endpoint).host.orEmpty().lowercase() }.getOrDefault("")
        return when {
            host == "api.anthropic.com" || host.endsWith(".anthropic.com") -> Protocol.ANTHROPIC
            host == "generativelanguage.googleapis.com" -> Protocol.GEMINI
            else -> Protocol.OPENAI_COMPATIBLE
        }
    }
}
