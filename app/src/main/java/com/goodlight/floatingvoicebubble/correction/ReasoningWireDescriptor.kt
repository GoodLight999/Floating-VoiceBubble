package com.goodlight.floatingvoicebubble.correction

import com.goodlight.floatingvoicebubble.ReasoningEffort
import java.net.URI
import java.util.Locale

/**
 * Redacted description of the optional reasoning fields that will actually be emitted on the wire.
 * This intentionally reuses ReasoningCapabilities so diagnostics cannot drift from provider bodies.
 */
object ReasoningWireDescriptor {
    fun describe(endpoint: String, model: String, requested: ReasoningEffort): String {
        val host = runCatching { URI(endpoint.trim()).host.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")
        val normalized = ReasoningCapabilities.normalize(endpoint, model, requested)
        if (normalized == ReasoningEffort.DEFAULT) return "optional-reasoning=<omitted:model-default>"

        return when {
            host == "openrouter.ai" ->
                ReasoningCapabilities.effortString(endpoint, model, normalized)
                    ?.let { "reasoning.effort=$it" }
                    ?: "optional-reasoning=<omitted>"

            host == "api.openai.com" || host.endsWith(".openai.azure.com") ->
                ReasoningCapabilities.effortString(endpoint, model, normalized)
                    ?.let { "reasoning_effort=$it" }
                    ?: "optional-reasoning=<omitted>"

            host == "api.z.ai" -> buildList {
                ReasoningCapabilities.zaiThinking(endpoint, model, normalized)?.let { enabled ->
                    add("thinking.type=${if (enabled) "enabled" else "disabled"}")
                }
                ReasoningCapabilities.zaiReasoningEffort(endpoint, model, normalized)?.let { effort ->
                    add("reasoning_effort=$effort")
                }
            }.joinToString(",").ifBlank { "optional-reasoning=<omitted>" }

            host == "api.anthropic.com" || host.endsWith(".anthropic.com") -> {
                val type = ReasoningCapabilities.anthropicThinkingType(endpoint, model, normalized)
                val effort = ReasoningCapabilities.anthropicEffort(endpoint, model, normalized)
                buildList {
                    type?.let { add("thinking.type=$it") }
                    effort?.let { add("output_config.effort=$it") }
                }.joinToString(",").ifBlank { "optional-reasoning=<omitted>" }
            }

            host == "generativelanguage.googleapis.com" -> {
                val control = ReasoningCapabilities.geminiThinking(endpoint, model, normalized)
                when {
                    control?.level != null -> "thinkingConfig.thinkingLevel=${control.level}"
                    control?.budgetTokens != null -> "thinkingConfig.thinkingBudget=${control.budgetTokens}"
                    else -> "optional-reasoning=<omitted>"
                }
            }

            else -> "optional-reasoning=<omitted:unknown-compatible-api>"
        }
    }
}
