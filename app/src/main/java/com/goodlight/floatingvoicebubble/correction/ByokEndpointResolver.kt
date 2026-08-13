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
        require(uri.userInfo.isNullOrBlank()) { "BYOK endpoint must not contain credentials" }
        val protocol = CloudCorrectorFactory.protocolFor(raw)
        return when (protocol) {
            CloudCorrectorFactory.Protocol.ANTHROPIC -> resolveAnthropic(uri)
            CloudCorrectorFactory.Protocol.GEMINI -> resolveGemini(uri)
            CloudCorrectorFactory.Protocol.OPENAI_COMPATIBLE -> resolveOpenAiCompatible(uri)
        }
    }

    private fun resolveOpenAiCompatible(uri: URI): ResolvedByokEndpoint {
        val base = withoutQueryOrFragment(uri).trimEnd('/')
        val path = uri.path.orEmpty().trimEnd('/')
        val lowerPath = path.lowercase()
        val versioned = VERSION_SEGMENT.containsMatchIn(lowerPath)

        val generation = when {
            lowerPath.endsWith("/chat/completions") -> base
            // A common hand-entered typo. The request body used by VoiceBubble is Chat Completions,
            // so silently repair the missing trailing 's' rather than sending a guaranteed 404.
            lowerPath.endsWith("/chat/completion") -> base.dropLast("/chat/completion".length) + "/chat/completions"
            // Legacy OpenAI text-completions uses a different request schema. If the user pastes it,
            // route to the sibling Chat Completions endpoint that accepts our messages payload.
            lowerPath.endsWith("/completions") -> base.dropLast("/completions".length) + "/chat/completions"
            lowerPath.endsWith("/models") -> base.removeSuffix(path.takeLast("/models".length)) + "/chat/completions"
            lowerPath.endsWith("/v1") || lowerPath.endsWith("/api/v1") -> "$base/chat/completions"
            // Google exposes an OpenAI-compatible path under /v1beta/openai. Do not inject another /v1.
            versioned -> "$base/chat/completions"
            // Most OpenAI-compatible servers expose their compatibility layer below /v1. Previously
            // VoiceBubble appended only /chat/completions here, which breaks root URLs such as
            // https://host.example or https://host.example/api when the real route is /v1/....
            else -> "$base/v1/chat/completions"
        }
        val models = generation.removeSuffix("/chat/completions") + "/models"
        return ResolvedByokEndpoint(CloudCorrectorFactory.Protocol.OPENAI_COMPATIBLE, generation, models)
    }

    private fun resolveAnthropic(uri: URI): ResolvedByokEndpoint {
        val origin = origin(uri)
        val path = uri.path.orEmpty().trimEnd('/')
        val generation = when {
            path.endsWith("/v1/messages") -> "$origin$path"
            path.endsWith("/v1/models") -> "$origin${path.removeSuffix("/models")}/messages"
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

    private val VERSION_SEGMENT = Regex("/(?:v\\d+(?:beta\\d*)?|v\\d+beta)(?:/|$)", RegexOption.IGNORE_CASE)
}
