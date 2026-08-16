package com.goodlight.floatingvoicebubble.correction

import com.goodlight.floatingvoicebubble.ReasoningEffort
import java.net.URI
import java.util.Locale

/**
 * Provider-specific options for APIs that expose an OpenAI-compatible Chat Completions surface.
 *
 * "OpenAI compatible" only guarantees the core request shape. Optional fields such as
 * reasoning_effort are not portable, so they must never be sprayed at every provider.
 */
data class OpenAiProviderOptions(
    val requestModel: String,
    val openAiReasoningEffort: String? = null,
    val openRouterReasoningEffort: String? = null,
    val zaiThinkingEnabled: Boolean? = null,
    val disableSampling: Boolean = false,
    val sendEnglishAcceptLanguage: Boolean = false,
    val zaiCodingPlanEndpoint: Boolean = false,
)

object OpenAiProviderCompatibility {
    fun resolve(endpoint: String, model: String, reasoningEffort: ReasoningEffort): OpenAiProviderOptions {
        val uri = runCatching { URI(endpoint.trim()) }.getOrNull()
        val host = uri?.host.orEmpty().lowercase(Locale.ROOT)
        val path = uri?.path.orEmpty().lowercase(Locale.ROOT)
        val isOpenRouter = host == "openrouter.ai"
        val isZai = host == "api.z.ai"
        val isOpenAi = host == "api.openai.com" || host.endsWith(".openai.azure.com")

        val effort = reasoningEffort.takeUnless { it == ReasoningEffort.DEFAULT }
        return OpenAiProviderOptions(
            requestModel = if (isZai) model.lowercase(Locale.ROOT) else model,
            openAiReasoningEffort = if (isOpenAi) effort?.let(::openAiEffort) else null,
            openRouterReasoningEffort = if (isOpenRouter) effort?.let(::openRouterEffort) else null,
            // Z.AI's thinking control is binary. Voice correction is a low-latency editing task, so
            // DEFAULT must not silently inherit a provider-side thinking default. Users can still
            // explicitly request LOW or deeper reasoning and receive thinking mode.
            zaiThinkingEnabled = if (isZai) zaiThinkingEnabled(reasoningEffort) else null,
            // Z.AI documents do_sample=false as its deterministic path. This avoids relying on
            // temperature semantics and keeps lightweight GLM variants compatible.
            disableSampling = isZai,
            sendEnglishAcceptLanguage = isZai,
            zaiCodingPlanEndpoint = isZai && path.contains("/api/coding/paas/v4"),
        )
    }

    private fun zaiThinkingEnabled(value: ReasoningEffort): Boolean = when (value) {
        ReasoningEffort.DEFAULT,
        ReasoningEffort.NONE,
        ReasoningEffort.MINIMAL -> false
        ReasoningEffort.LOW,
        ReasoningEffort.MEDIUM,
        ReasoningEffort.HIGH,
        ReasoningEffort.XHIGH,
        ReasoningEffort.MAX -> true
    }

    private fun openAiEffort(value: ReasoningEffort): String = when (value) {
        ReasoningEffort.DEFAULT -> error("DEFAULT is omitted")
        ReasoningEffort.NONE -> "none"
        ReasoningEffort.MINIMAL -> "minimal"
        ReasoningEffort.LOW -> "low"
        ReasoningEffort.MEDIUM -> "medium"
        ReasoningEffort.HIGH -> "high"
        ReasoningEffort.XHIGH,
        ReasoningEffort.MAX -> "xhigh"
    }

    private fun openRouterEffort(value: ReasoningEffort): String = when (value) {
        ReasoningEffort.DEFAULT -> error("DEFAULT is omitted")
        ReasoningEffort.NONE -> "none"
        ReasoningEffort.MINIMAL -> "minimal"
        ReasoningEffort.LOW -> "low"
        ReasoningEffort.MEDIUM -> "medium"
        ReasoningEffort.HIGH -> "high"
        ReasoningEffort.XHIGH -> "xhigh"
        ReasoningEffort.MAX -> "max"
    }
}