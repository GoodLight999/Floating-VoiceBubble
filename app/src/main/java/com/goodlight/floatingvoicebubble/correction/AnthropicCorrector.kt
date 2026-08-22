package com.goodlight.floatingvoicebubble.correction

import com.goodlight.floatingvoicebubble.ReasoningEffort
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class AnthropicCorrector(
    private val endpoint: String,
    private val model: String,
    private val apiKey: String,
    private val reasoningEffort: ReasoningEffort = ReasoningEffort.DEFAULT,
) : TextCorrector {
    override val id: String = "byok:anthropic:$model"

    override fun correct(request: CorrectionRequest): String {
        require(endpoint.startsWith("https://")) { "BYOK endpoint must use HTTPS" }
        require(model.isNotBlank()) { "BYOK model is not configured" }
        require(apiKey.isNotBlank()) { "Anthropic API key is not configured" }

        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", outputTokenBudget())
            .put("system", CorrectionPrompt.system(request))
            .put(
                "messages",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", CorrectionPrompt.user(request)),
                ),
            )

        ReasoningCapabilities.anthropicThinkingType(endpoint, model, reasoningEffort)?.let { type ->
            body.put("thinking", JSONObject().put("type", type))
        }
        ReasoningCapabilities.anthropicEffort(endpoint, model, reasoningEffort)?.let { effort ->
            body.put("output_config", JSONObject().put("effort", effort))
        }

        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = CorrectionTimeoutPolicy.networkReadTimeoutMs(reasoningEffort)
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("x-api-key", apiKey)
            setRequestProperty("anthropic-version", "2023-06-01")
        }
        try {
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }
            val status = connection.responseCode
            val responseText = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val detail = responseText.take(500).replace(Regex("\\s+"), " ")
                if (reasoningEffort != ReasoningEffort.DEFAULT && status in listOf(400, 422)) {
                    error("選択したClaude推論設定を適用できません: HTTP $status $detail")
                }
                error("Anthropic request failed: HTTP $status $detail")
            }

            val content = JSONObject(responseText).optJSONArray("content")
                ?: error("Anthropic response has no content")
            return buildString {
                for (i in 0 until content.length()) {
                    val part = content.optJSONObject(i) ?: continue
                    if (part.optString("type") == "text") append(part.optString("text"))
                }
            }.trim().ifBlank { error("Anthropic response has no text") }
        } finally {
            connection.disconnect()
        }
    }

    private fun outputTokenBudget(): Int = when (ReasoningCapabilities.normalize(endpoint, model, reasoningEffort)) {
        ReasoningEffort.XHIGH, ReasoningEffort.MAX -> 16_384
        ReasoningEffort.HIGH -> 8_192
        ReasoningEffort.MEDIUM -> 4_096
        else -> 2_048
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 8_000
    }
}
