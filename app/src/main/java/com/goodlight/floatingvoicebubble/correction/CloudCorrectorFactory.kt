package com.goodlight.floatingvoicebubble.correction

import java.net.URI

object CloudCorrectorFactory {
    enum class Protocol { OPENAI_COMPATIBLE, ANTHROPIC, GEMINI }

    fun create(endpoint: String, model: String, apiKey: String): TextCorrector {
        val resolved = ByokEndpointResolver.resolve(endpoint)
        return when (resolved.protocol) {
            Protocol.OPENAI_COMPATIBLE -> OpenAiCompatibleCorrector(resolved.generationUrl, model, apiKey)
            Protocol.ANTHROPIC -> AnthropicCorrector(resolved.generationUrl, model, apiKey)
            Protocol.GEMINI -> GeminiApiCorrector(resolved.generationUrl, model, apiKey)
        }
    }

    fun protocolFor(endpoint: String): Protocol {
        val uri = runCatching { URI(endpoint.trim()) }.getOrNull()
        val host = uri?.host.orEmpty().lowercase()
        val path = uri?.path.orEmpty().lowercase()
        return when {
            host == "generativelanguage.googleapis.com" && path.contains("/openai") -> Protocol.OPENAI_COMPATIBLE
            host == "api.anthropic.com" || host.endsWith(".anthropic.com") -> Protocol.ANTHROPIC
            host == "generativelanguage.googleapis.com" -> Protocol.GEMINI
            else -> Protocol.OPENAI_COMPATIBLE
        }
    }
}
