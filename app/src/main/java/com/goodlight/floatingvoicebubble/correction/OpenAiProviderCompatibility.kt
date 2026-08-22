package com.goodlight.floatingvoicebubble.correction

import com.goodlight.floatingvoicebubble.ReasoningEffort
import java.net.URI
import java.util.Locale

/**
 * Provider-specific options for APIs that expose an OpenAI-compatible Chat Completions surface.
 *
 * "OpenAI compatible" guarantees only the core request shape. Optional reasoning controls are
 * provider/model contracts and must never be sprayed at an unknown endpoint.
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
        val effectiveEffort = ReasoningCapabilities.effortString(endpoint, model, reasoningEffort)

        return OpenAiProviderOptions(
            requestModel = if (isZai) model.lowercase(Locale.ROOT) else model,
            openAiReasoningEffort = if (isOpenAi) effectiveEffort else null,
            openRouterReasoningEffort = if (isOpenRouter) effectiveEffort else null,
            zaiThinkingEnabled = if (isZai) ReasoningCapabilities.zaiThinking(endpoint, model, reasoningEffort) else null,
            // Z.AI documents do_sample=false as its deterministic path. Keep provider-specific
            // sampling options away from generic OpenAI-compatible endpoints.
            disableSampling = isZai,
            sendEnglishAcceptLanguage = isZai,
            zaiCodingPlanEndpoint = isZai && path.contains("/api/coding/paas/v4"),
        )
    }
}
