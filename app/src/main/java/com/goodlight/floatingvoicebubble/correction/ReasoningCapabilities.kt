package com.goodlight.floatingvoicebubble.correction

import com.goodlight.floatingvoicebubble.ReasoningEffort
import java.net.URI
import java.util.Locale

/**
 * User-facing reasoning is a product-level intent. Provider/model APIs do not share one ladder, so
 * every request must be normalized before it reaches a provider-specific adapter.
 *
 * DEFAULT means VoiceBubble's latency-oriented automatic choice, not "spray whatever the provider
 * happens to default to". Voice correction is a short post-editing task, so AUTO prefers low/no
 * thinking unless a provider/model has no explicit control.
 */
data class ReasoningCapability(
    val choices: List<ReasoningEffort>,
    val note: String,
)

object ReasoningCapabilities {
    fun capability(endpoint: String, model: String): ReasoningCapability {
        val host = host(endpoint)
        val normalizedModel = model.lowercase(Locale.ROOT)
        return when {
            host == "openrouter.ai" -> ReasoningCapability(
                choices = listOf(
                    ReasoningEffort.DEFAULT,
                    ReasoningEffort.NONE,
                    ReasoningEffort.MINIMAL,
                    ReasoningEffort.LOW,
                    ReasoningEffort.MEDIUM,
                    ReasoningEffort.HIGH,
                    ReasoningEffort.XHIGH,
                    ReasoningEffort.MAX,
                ),
                note = "OpenRouter経由。モデルが対応しない深さはOpenRouter側で近い値へ変換される場合があります。",
            )
            host == "api.z.ai" -> ReasoningCapability(
                choices = listOf(ReasoningEffort.DEFAULT, ReasoningEffort.NONE, ReasoningEffort.HIGH),
                note = "Z.AIはこの用途では思考のON/OFFとして扱います。段階的な深さ指定は表示しません。",
            )
            host == "api.openai.com" || host.endsWith(".openai.azure.com") -> openAiCapability(normalizedModel)
            host == "api.anthropic.com" || host.endsWith(".anthropic.com") -> anthropicCapability(normalizedModel)
            host == "generativelanguage.googleapis.com" -> geminiCapability(normalizedModel)
            else -> ReasoningCapability(
                choices = listOf(ReasoningEffort.DEFAULT),
                note = "この互換APIの推論深度仕様は確認できないため、追加パラメータを送りません。",
            )
        }
    }

    fun normalize(endpoint: String, model: String, requested: ReasoningEffort): ReasoningEffort {
        val capability = capability(endpoint, model)
        if (requested in capability.choices) return requested
        return when {
            requested == ReasoningEffort.MINIMAL && ReasoningEffort.LOW in capability.choices -> ReasoningEffort.LOW
            requested == ReasoningEffort.MAX && ReasoningEffort.XHIGH in capability.choices -> ReasoningEffort.XHIGH
            requested == ReasoningEffort.XHIGH && ReasoningEffort.HIGH in capability.choices -> ReasoningEffort.HIGH
            requested == ReasoningEffort.MEDIUM && ReasoningEffort.HIGH in capability.choices -> ReasoningEffort.HIGH
            requested == ReasoningEffort.LOW && ReasoningEffort.NONE in capability.choices -> ReasoningEffort.NONE
            else -> ReasoningEffort.DEFAULT
        }
    }

    fun label(endpoint: String, model: String, value: ReasoningEffort): String {
        val host = host(endpoint)
        if (host == "api.z.ai") {
            return when (normalize(endpoint, model, value)) {
                ReasoningEffort.DEFAULT -> "自動（高速）"
                ReasoningEffort.NONE -> "思考なし"
                else -> "思考あり"
            }
        }
        return when (normalize(endpoint, model, value)) {
            ReasoningEffort.DEFAULT -> "自動（高速）"
            ReasoningEffort.NONE -> "なし"
            ReasoningEffort.MINIMAL -> "最小"
            ReasoningEffort.LOW -> "低"
            ReasoningEffort.MEDIUM -> "中"
            ReasoningEffort.HIGH -> "高"
            ReasoningEffort.XHIGH -> "xhigh"
            ReasoningEffort.MAX -> "max"
        }
    }

    /** The OpenAI/OpenRouter effort string after capability normalization. null means omit. */
    fun effortString(endpoint: String, model: String, requested: ReasoningEffort): String? {
        val host = host(endpoint)
        if (host == "api.z.ai" || host == "generativelanguage.googleapis.com" ||
            host == "api.anthropic.com" || host.endsWith(".anthropic.com")) return null
        if (host != "openrouter.ai" && host != "api.openai.com" && !host.endsWith(".openai.azure.com")) return null
        return when (val value = normalize(endpoint, model, requested)) {
            ReasoningEffort.DEFAULT -> if (host == "openrouter.ai" || supportsOpenAiLow(model)) "low" else null
            ReasoningEffort.NONE -> "none"
            ReasoningEffort.MINIMAL -> "minimal"
            ReasoningEffort.LOW -> "low"
            ReasoningEffort.MEDIUM -> "medium"
            ReasoningEffort.HIGH -> "high"
            ReasoningEffort.XHIGH -> "xhigh"
            ReasoningEffort.MAX -> "max"
        }
    }

    /** Z.AI's documented control is binary. null means this is not a Z.AI endpoint. */
    fun zaiThinking(endpoint: String, model: String, requested: ReasoningEffort): Boolean? {
        if (host(endpoint) != "api.z.ai") return null
        return when (normalize(endpoint, model, requested)) {
            ReasoningEffort.DEFAULT, ReasoningEffort.NONE, ReasoningEffort.MINIMAL, ReasoningEffort.LOW -> false
            ReasoningEffort.MEDIUM, ReasoningEffort.HIGH, ReasoningEffort.XHIGH, ReasoningEffort.MAX -> true
        }
    }

    /** Anthropic output_config.effort. null means unsupported/unknown or another provider. */
    fun anthropicEffort(endpoint: String, model: String, requested: ReasoningEffort): String? {
        val host = host(endpoint)
        if (host != "api.anthropic.com" && !host.endsWith(".anthropic.com")) return null
        val normalized = normalize(endpoint, model, requested)
        return when (normalized) {
            ReasoningEffort.DEFAULT -> if (capability(endpoint, model).choices.size > 1) "low" else null
            ReasoningEffort.LOW -> "low"
            ReasoningEffort.MEDIUM -> "medium"
            ReasoningEffort.HIGH -> "high"
            ReasoningEffort.XHIGH -> "xhigh"
            ReasoningEffort.MAX -> "max"
            ReasoningEffort.NONE, ReasoningEffort.MINIMAL -> null
        }
    }

    private fun openAiCapability(model: String): ReasoningCapability = when {
        model.startsWith("gpt-5.6") -> effortCapability(
            ReasoningEffort.NONE, ReasoningEffort.LOW, ReasoningEffort.MEDIUM,
            ReasoningEffort.HIGH, ReasoningEffort.XHIGH, ReasoningEffort.MAX,
        )
        model.startsWith("gpt-5.5") || model.startsWith("gpt-5.4") || model.startsWith("gpt-5.2") -> effortCapability(
            ReasoningEffort.NONE, ReasoningEffort.LOW, ReasoningEffort.MEDIUM,
            ReasoningEffort.HIGH, ReasoningEffort.XHIGH,
        )
        model.startsWith("gpt-5.1") -> effortCapability(
            ReasoningEffort.NONE, ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH,
        )
        model == "gpt-5" || model.startsWith("gpt-5-") -> effortCapability(
            ReasoningEffort.MINIMAL, ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH,
        )
        model.startsWith("o3") || model.startsWith("o4") || model.startsWith("o1") -> effortCapability(
            ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH,
        )
        else -> ReasoningCapability(
            listOf(ReasoningEffort.DEFAULT),
            "このOpenAIモデルの推論深度対応を確定できないため、追加パラメータを送りません。",
        )
    }

    private fun anthropicCapability(model: String): ReasoningCapability = when {
        model.contains("sonnet-5") || model.contains("opus-5") || model.contains("fable-5") || model.contains("mythos-5") -> effortCapability(
            ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH,
            ReasoningEffort.XHIGH, ReasoningEffort.MAX,
            note = "Claude effort対応。音声補正では低い深さから使うのを推奨します。",
        )
        model.contains("opus-4-8") || model.contains("opus-4-7") -> effortCapability(
            ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH,
            ReasoningEffort.XHIGH, ReasoningEffort.MAX,
            note = "Claude effort対応。音声補正では低い深さから使うのを推奨します。",
        )
        model.contains("sonnet-4-6") || model.contains("opus-4-6") || model.contains("opus-4-5") -> effortCapability(
            ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH, ReasoningEffort.MAX,
            note = "Claude effort対応。音声補正では低い深さから使うのを推奨します。",
        )
        else -> ReasoningCapability(
            listOf(ReasoningEffort.DEFAULT),
            "このClaudeモデルのeffort対応を確定できないため、追加パラメータを送りません。",
        )
    }

    private fun geminiCapability(model: String): ReasoningCapability {
        val clean = model.removePrefix("models/")
        return when {
            clean.contains("gemini-3.1-pro") -> effortCapability(
                ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH,
                note = "Gemini 3.1 Proのthinking levelに合わせて表示しています。",
            )
            clean.contains("gemini-3.1-flash-lite") || clean.contains("gemini-3-flash") -> effortCapability(
                ReasoningEffort.MINIMAL, ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH,
                note = "Geminiのthinking levelに合わせて表示しています。",
            )
            clean.contains("gemini-2.5-pro") -> effortCapability(
                ReasoningEffort.MINIMAL, ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH,
                note = "Gemini 2.5 Proは思考を完全にはOFFにできません。",
            )
            clean.contains("gemini-2.5") -> effortCapability(
                ReasoningEffort.NONE, ReasoningEffort.MINIMAL, ReasoningEffort.LOW,
                ReasoningEffort.MEDIUM, ReasoningEffort.HIGH,
                note = "Gemini 2.5のthinking budgetへ変換します。",
            )
            else -> ReasoningCapability(
                listOf(ReasoningEffort.DEFAULT),
                "このGeminiモデルのthinking設定を確定できないため、モデル既定に任せます。",
            )
        }
    }

    private fun effortCapability(vararg efforts: ReasoningEffort, note: String = "モデルが対応する推論深度だけを表示しています。") =
        ReasoningCapability(listOf(ReasoningEffort.DEFAULT) + efforts.toList(), note)

    private fun supportsOpenAiLow(model: String): Boolean = openAiCapability(model.lowercase(Locale.ROOT)).choices.any {
        it == ReasoningEffort.LOW
    }

    private fun host(endpoint: String): String = runCatching { URI(endpoint.trim()).host.orEmpty().lowercase(Locale.ROOT) }
        .getOrDefault("")
}
