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
    val zaiReasoningEffort: String? = null,
    val zaiThinkingEnabled: Boolean? = null,
    val disableSampling: Boolean = false,
    val sendEnglishAcceptLanguage: Boolean = false,
    val zaiProvider: Boolean = false,
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
        val normalizedEffort = ReasoningCapabilities.effortString(endpoint, model, reasoningEffort)
        // OpenRouter's current reasoning abstraction supports `max` as a gateway effort even when
        // an older local capability table would conservatively normalize MAX to XHIGH. The model
        // catalog carries exact supported_efforts and the UI filters against that metadata.
        val openRouterEffort = if (isOpenRouter && reasoningEffort == ReasoningEffort.MAX) {
            "max"
        } else {
            normalizedEffort
        }

        return OpenAiProviderOptions(
            requestModel = if (isZai) model.lowercase(Locale.ROOT) else model,
            openAiReasoningEffort = if (isOpenAi) normalizedEffort else null,
            openRouterReasoningEffort = if (isOpenRouter) openRouterEffort else null,
            zaiReasoningEffort = if (isZai) ReasoningCapabilities.zaiReasoningEffort(endpoint, model, reasoningEffort) else null,
            zaiThinkingEnabled = if (isZai) ReasoningCapabilities.zaiThinking(endpoint, model, reasoningEffort) else null,
            // Z.AI documents do_sample=false as its deterministic path for the compatible endpoint.
            // Keep provider-specific sampling options away from generic compatible endpoints.
            disableSampling = isZai,
            sendEnglishAcceptLanguage = isZai,
            zaiProvider = isZai,
            zaiCodingPlanEndpoint = isZai && path.contains("/api/coding/paas/v4"),
        )
    }
}
