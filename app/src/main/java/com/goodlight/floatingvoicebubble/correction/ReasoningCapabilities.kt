package com.goodlight.floatingvoicebubble.correction

import com.goodlight.floatingvoicebubble.ReasoningEffort
import java.net.URI
import java.util.Locale

/**
 * Normalizes the product-level "reasoning depth" choice onto controls that providers actually
 * document. DEFAULT always means provider/model default and therefore emits no optional reasoning
 * parameter. Unknown OpenAI-compatible endpoints never receive guessed proprietary parameters.
 */
data class ReasoningCapability(
    val choices: List<ReasoningEffort>,
    val note: String,
)

object ReasoningCapabilities {
    fun capability(endpoint: String, model: String): ReasoningCapability {
        val host = host(endpoint)
        val normalizedModel = model.lowercase(Locale.ROOT).removePrefix("models/")
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
                ),
                note = "OpenRouterのreasoning.effortへ送信します。モデル非対応時はAPIエラーを明示します。",
            )
            host == "api.z.ai" -> ReasoningCapability(
                choices = listOf(ReasoningEffort.DEFAULT, ReasoningEffort.NONE, ReasoningEffort.HIGH),
                note = "Z.AIの公開APIは思考のON/OFFです。段階的な深さは表示しません。",
            )
            host == "api.openai.com" || host.endsWith(".openai.azure.com") -> openAiCapability(normalizedModel)
            host == "api.anthropic.com" || host.endsWith(".anthropic.com") -> anthropicCapability(normalizedModel)
            host == "generativelanguage.googleapis.com" -> geminiCapability(normalizedModel)
            else -> ReasoningCapability(
                choices = listOf(ReasoningEffort.DEFAULT),
                note = "この互換APIの推論制御仕様を確認できないため、モデル既定だけを使います。",
            )
        }
    }

    fun normalize(endpoint: String, model: String, requested: ReasoningEffort): ReasoningEffort {
        val choices = capability(endpoint, model).choices
        if (requested in choices) return requested
        if (requested == ReasoningEffort.DEFAULT) return ReasoningEffort.DEFAULT

        // Preserve old persisted settings without inventing an unsupported wire value. When an old
        // level disappears, move to the nearest conservative documented level rather than increasing
        // work unexpectedly.
        return when {
            requested == ReasoningEffort.MAX && ReasoningEffort.XHIGH in choices -> ReasoningEffort.XHIGH
            requested in listOf(ReasoningEffort.MAX, ReasoningEffort.XHIGH) && ReasoningEffort.HIGH in choices -> ReasoningEffort.HIGH
            requested == ReasoningEffort.MINIMAL && ReasoningEffort.LOW in choices -> ReasoningEffort.LOW
            requested == ReasoningEffort.MINIMAL && ReasoningEffort.NONE in choices -> ReasoningEffort.NONE
            requested == ReasoningEffort.MINIMAL && ReasoningEffort.MEDIUM in choices -> ReasoningEffort.MEDIUM
            requested == ReasoningEffort.LOW && ReasoningEffort.NONE in choices -> ReasoningEffort.NONE
            else -> ReasoningEffort.DEFAULT
        }
    }

    fun label(endpoint: String, model: String, value: ReasoningEffort): String {
        val normalized = normalize(endpoint, model, value)
        if (host(endpoint) == "api.z.ai") {
            return when (normalized) {
                ReasoningEffort.DEFAULT -> "モデル既定"
                ReasoningEffort.NONE -> "思考OFF"
                else -> "思考ON"
            }
        }
        return when (normalized) {
            ReasoningEffort.DEFAULT -> "モデル既定"
            ReasoningEffort.NONE -> "なし"
            ReasoningEffort.MINIMAL -> "最小"
            ReasoningEffort.LOW -> "低"
            ReasoningEffort.MEDIUM -> "中"
            ReasoningEffort.HIGH -> "高"
            ReasoningEffort.XHIGH -> "xhigh"
            ReasoningEffort.MAX -> "max"
        }
    }

    /** OpenAI/OpenRouter effort value. DEFAULT and unsupported providers omit the field. */
    fun effortString(endpoint: String, model: String, requested: ReasoningEffort): String? {
        val host = host(endpoint)
        if (host != "openrouter.ai" && host != "api.openai.com" && !host.endsWith(".openai.azure.com")) return null
        return when (normalize(endpoint, model, requested)) {
            ReasoningEffort.DEFAULT -> null
            ReasoningEffort.NONE -> "none"
            ReasoningEffort.MINIMAL -> "minimal"
            ReasoningEffort.LOW -> "low"
            ReasoningEffort.MEDIUM -> "medium"
            ReasoningEffort.HIGH -> "high"
            ReasoningEffort.XHIGH -> "xhigh"
            ReasoningEffort.MAX -> "max"
        }
    }

    /** Z.AI documents thinking.type as binary. DEFAULT intentionally omits it. */
    fun zaiThinking(endpoint: String, model: String, requested: ReasoningEffort): Boolean? {
        if (host(endpoint) != "api.z.ai") return null
        return when (normalize(endpoint, model, requested)) {
            ReasoningEffort.DEFAULT -> null
            ReasoningEffort.NONE, ReasoningEffort.MINIMAL, ReasoningEffort.LOW -> false
            ReasoningEffort.MEDIUM, ReasoningEffort.HIGH, ReasoningEffort.XHIGH, ReasoningEffort.MAX -> true
        }
    }

    /** Anthropic output_config.effort for models where the documented effort control is available. */
    fun anthropicEffort(endpoint: String, model: String, requested: ReasoningEffort): String? {
        val host = host(endpoint)
        if (host != "api.anthropic.com" && !host.endsWith(".anthropic.com")) return null
        val normalizedModel = model.lowercase(Locale.ROOT)
        if (!supportsAnthropicAdaptive(normalizedModel)) return null
        return when (normalize(endpoint, model, requested)) {
            ReasoningEffort.DEFAULT, ReasoningEffort.NONE -> null
            ReasoningEffort.MINIMAL, ReasoningEffort.LOW -> "low"
            ReasoningEffort.MEDIUM -> "medium"
            ReasoningEffort.HIGH -> "high"
            ReasoningEffort.XHIGH -> "xhigh"
            ReasoningEffort.MAX -> "max"
        }
    }

    /** Anthropic thinking mode. Explicit depth uses adaptive thinking on models that support it. */
    fun anthropicThinkingType(endpoint: String, model: String, requested: ReasoningEffort): String? {
        val host = host(endpoint)
        if (host != "api.anthropic.com" && !host.endsWith(".anthropic.com")) return null
        val normalizedModel = model.lowercase(Locale.ROOT)
        return when (normalize(endpoint, model, requested)) {
            ReasoningEffort.DEFAULT -> null
            ReasoningEffort.NONE -> if (supportsAnthropicThinkingDisabled(normalizedModel)) "disabled" else null
            else -> if (supportsAnthropicAdaptive(normalizedModel)) "adaptive" else null
        }
    }

    fun geminiThinking(endpoint: String, model: String, requested: ReasoningEffort): GeminiThinkingControl? {
        if (host(endpoint) != "generativelanguage.googleapis.com") return null
        val clean = model.lowercase(Locale.ROOT).removePrefix("models/")
        val normalized = normalize(endpoint, model, requested)
        if (normalized == ReasoningEffort.DEFAULT) return null

        return if (clean.startsWith("gemini-2.5")) {
            val canDisable = !clean.contains("2.5-pro")
            val budget = when (normalized) {
                ReasoningEffort.NONE -> if (canDisable) 0 else -1
                ReasoningEffort.MINIMAL -> 512
                ReasoningEffort.LOW -> 1024
                ReasoningEffort.MEDIUM -> 4096
                ReasoningEffort.HIGH -> 8192
                ReasoningEffort.XHIGH, ReasoningEffort.MAX -> 16384
                ReasoningEffort.DEFAULT -> -1
            }
            GeminiThinkingControl(budgetTokens = budget)
        } else {
            val level = when (normalized) {
                ReasoningEffort.NONE, ReasoningEffort.MINIMAL -> "minimal"
                ReasoningEffort.LOW -> "low"
                ReasoningEffort.MEDIUM -> "medium"
                ReasoningEffort.HIGH, ReasoningEffort.XHIGH, ReasoningEffort.MAX -> "high"
                ReasoningEffort.DEFAULT -> return null
            }
            GeminiThinkingControl(level = level)
        }
    }

    private fun openAiCapability(model: String): ReasoningCapability = when {
        model.startsWith("gpt-5.6") -> effortCapability(
            ReasoningEffort.NONE, ReasoningEffort.LOW, ReasoningEffort.MEDIUM,
            ReasoningEffort.HIGH, ReasoningEffort.XHIGH, ReasoningEffort.MAX,
        )
        model.startsWith("gpt-5.5-pro") || model.startsWith("gpt-5.4-pro") || model.startsWith("gpt-5.2-pro") ->
            effortCapability(ReasoningEffort.MEDIUM, ReasoningEffort.HIGH, ReasoningEffort.XHIGH)
        model.startsWith("gpt-5.5") || model.startsWith("gpt-5.4") || model.startsWith("gpt-5.2") -> effortCapability(
            ReasoningEffort.NONE, ReasoningEffort.LOW, ReasoningEffort.MEDIUM,
            ReasoningEffort.HIGH, ReasoningEffort.XHIGH,
        )
        model.startsWith("gpt-5.1") -> effortCapability(
            ReasoningEffort.NONE, ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH,
        )
        model == "gpt-5-pro" || model.startsWith("gpt-5-pro-") -> effortCapability(ReasoningEffort.HIGH)
        model == "gpt-5" || GPT5_SNAPSHOT.matches(model) -> effortCapability(
            ReasoningEffort.MINIMAL, ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH,
        )
        model.startsWith("o1") || model.startsWith("o3") || model.startsWith("o4") -> effortCapability(
            ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH,
        )
        else -> ReasoningCapability(
            listOf(ReasoningEffort.DEFAULT),
            "このOpenAIモデルの推論深度対応を確定できないため、モデル既定だけを使います。",
        )
    }

    private fun anthropicCapability(model: String): ReasoningCapability = when {
        supportsAnthropicAdaptive(model) -> {
            val efforts = mutableListOf<ReasoningEffort>()
            if (supportsAnthropicThinkingDisabled(model)) efforts += ReasoningEffort.NONE
            efforts += listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH)
            if (supportsAnthropicXHigh(model)) efforts += ReasoningEffort.XHIGH
            if (supportsAnthropicMax(model)) efforts += ReasoningEffort.MAX
            ReasoningCapability(
                choices = listOf(ReasoningEffort.DEFAULT) + efforts,
                note = if (supportsAnthropicThinkingDisabled(model)) {
                    "Claudeのadaptive thinkingとoutput_config.effortへ送信します。"
                } else {
                    "このClaudeモデルは思考をOFFにできません。adaptive thinkingとoutput_config.effortを使います。"
                },
            )
        }
        model.contains("-4-5") -> ReasoningCapability(
            choices = listOf(ReasoningEffort.DEFAULT),
            note = "この世代は固定thinking budget方式です。現在の音声補正UIではモデル既定だけを使います。",
        )
        else -> ReasoningCapability(
            listOf(ReasoningEffort.DEFAULT),
            "このClaudeモデルのthinking仕様を確定できないため、モデル既定だけを使います。",
        )
    }

    private fun geminiCapability(model: String): ReasoningCapability = when {
        model.startsWith("gemini-2.5-pro") -> effortCapability(
            ReasoningEffort.MINIMAL, ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH,
            note = "Gemini 2.5 Proは思考を完全にはOFFにできません。thinkingBudgetへ変換します。",
        )
        model.startsWith("gemini-2.5") -> effortCapability(
            ReasoningEffort.NONE, ReasoningEffort.MINIMAL, ReasoningEffort.LOW,
            ReasoningEffort.MEDIUM, ReasoningEffort.HIGH,
            note = "Gemini 2.5のthinkingBudgetへ変換します。",
        )
        model.startsWith("gemini-3.7-flash") -> effortCapability(
            ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH,
            note = "Gemini 3.7 FlashのthinkingLevelに合わせています。",
        )
        model.startsWith("gemini-3.1-pro") -> effortCapability(
            ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH,
            note = "Gemini 3.1 ProのthinkingLevelに合わせています。",
        )
        model.startsWith("gemini-3-pro") -> effortCapability(
            ReasoningEffort.LOW, ReasoningEffort.HIGH,
            note = "このGemini Proモデルが公開しているthinkingLevelだけを表示します。",
        )
        model.startsWith("gemini-3") -> effortCapability(
            ReasoningEffort.MINIMAL, ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH,
            note = "Gemini 3系のthinkingLevelへ送信します。",
        )
        else -> ReasoningCapability(
            listOf(ReasoningEffort.DEFAULT),
            "このGeminiモデルのthinking仕様を確定できないため、モデル既定だけを使います。",
        )
    }

    private fun supportsAnthropicAdaptive(model: String): Boolean =
        model.contains("sonnet-4-6") || model.contains("opus-4-6") || model.contains("opus-4-7") ||
            model.contains("opus-4-8") || model.contains("sonnet-5") || model.contains("opus-5") ||
            model.contains("fable-5") || model.contains("mythos")

    private fun supportsAnthropicThinkingDisabled(model: String): Boolean =
        supportsAnthropicAdaptive(model) && !model.contains("fable-5") && !model.contains("mythos")

    private fun supportsAnthropicXHigh(model: String): Boolean =
        model.contains("opus-4-7") || model.contains("opus-4-8") || model.contains("sonnet-5") ||
            model.contains("opus-5") || model.contains("fable-5") || model.contains("mythos-5")

    private fun supportsAnthropicMax(model: String): Boolean =
        supportsAnthropicXHigh(model) || model.contains("sonnet-4-6") || model.contains("opus-4-6") ||
            model.contains("mythos-preview")

    private fun effortCapability(
        vararg efforts: ReasoningEffort,
        note: String = "モデルが公開している推論深度だけを表示します。",
    ) = ReasoningCapability(listOf(ReasoningEffort.DEFAULT) + efforts.toList(), note)

    private fun host(endpoint: String): String = runCatching {
        URI(endpoint.trim()).host.orEmpty().lowercase(Locale.ROOT)
    }.getOrDefault("")

    private val GPT5_SNAPSHOT = Regex("^gpt-5-\\d{4}-\\d{2}-\\d{2}$")
}

data class GeminiThinkingControl(
    val level: String? = null,
    val budgetTokens: Int? = null,
)
