package com.goodlight.floatingvoicebubble.correction

import java.net.URI

/** Normalizes a user-supplied BYOK URL into generation and model-list endpoints. */
data class ResolvedByokEndpoint(
    val protocol: CloudCorrectorFactory.Protocol,
    val generationUrl: String,
    val modelsUrl: String,
)

object ByokEndpointResolver {
    fun resolve(rawEndpoint: String): ResolvedByokEndpoint {
        val raw = rawEndpoint.trim().trimEnd('/')
        require(raw.startsWith("https://")) { "BYOK endpoint must use HTTPS" }
        val uri = URI(raw)
        require(!uri.host.isNullOrBlank()) { "BYOK endpoint host is missing" }
        val protocol = CloudCorrectorFactory.protocolFor(raw)
        return when (protocol) {
            CloudCorrectorFactory.Protocol.ANTHROPIC -> resolveAnthropic(uri)
            CloudCorrectorFactory.Protocol.GEMINI -> resolveGemini(uri)
            CloudCorrectorFactory.Protocol.OPENAI_COMPATIBLE -> resolveOpenAiCompatible(uri)
        }
    }

    private fun resolveOpenAiCompatible(uri: URI): ResolvedByokEndpoint {
        val base = withoutQueryOrFragment(uri).trimEnd('/')
        val generation = when {
            base.endsWith("/chat/completions") -> base
            base.endsWith("/models") -> base.removeSuffix("/models") + "/chat/completions"
            base.endsWith("/v1") || base.endsWith("/api/v1") -> "$base/chat/completions"
            uri.host.equals("api.openai.com", ignoreCase = true) && uri.path.orEmpty().isBlank() -> "$base/v1/chat/completions"
            else -> "$base/chat/completions"
        }
        val models = generation.removeSuffix("/chat/completions") + "/models"
        return ResolvedByokEndpoint(CloudCorrectorFactory.Protocol.OPENAI_COMPATIBLE, generation, models)
    }

    private fun resolveAnthropic(uri: URI): ResolvedByokEndpoint {
        val origin = origin(uri)
        val path = uri.path.orEmpty().trimEnd('/')
        val generation = when {
            path.endsWith("/v1/messages") -> "$origin$path"
            path.endsWith("/v1") -> "$origin$path/messages"
            path.isBlank() || path == "/" -> "$origin/v1/messages"
            else -> "$origin$path"
        }
        return ResolvedByokEndpoint(
            CloudCorrectorFactory.Protocol.ANTHROPIC,
            generation,
            "$origin/v1/models",
        )
    }

    private fun resolveGemini(uri: URI): ResolvedByokEndpoint {
        val origin = origin(uri)
        val path = uri.path.orEmpty().trimEnd('/')
        val generation = when {
            path.contains(":generateContent") -> "$origin$path"
            path.endsWith("/v1beta") || path.endsWith("/v1") -> "$origin$path"
            path.isBlank() || path == "/" -> "$origin/v1beta"
            path.endsWith("/models") -> "$origin${path.removeSuffix("/models")}"
            else -> "$origin$path"
        }
        val versionBase = when {
            path.contains("/v1beta") -> "$origin/v1beta"
            path.contains("/v1/") || path == "/v1" -> "$origin/v1"
            else -> "$origin/v1beta"
        }
        return ResolvedByokEndpoint(
            CloudCorrectorFactory.Protocol.GEMINI,
            generation,
            "$versionBase/models",
        )
    }

    private fun withoutQueryOrFragment(uri: URI): String = URI(
        uri.scheme,
        uri.userInfo,
        uri.host,
        uri.port,
        uri.path,
        null,
        null,
    ).toString()

    private fun origin(uri: URI): String = buildString {
        append(uri.scheme)
        append("://")
        append(uri.host)
        if (uri.port != -1) append(":${uri.port}")
    }
}
