package com.goodlight.floatingvoicebubble.correction

import com.goodlight.floatingvoicebubble.ReasoningEffort
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class GeminiApiCorrector(
    private val endpoint: String,
    private val model: String,
    private val apiKey: String,
    private val reasoningEffort: ReasoningEffort = ReasoningEffort.DEFAULT,
) : TextCorrector {
    override val id: String = "byok:gemini:$model"

    override fun correct(request: CorrectionRequest): String {
        require(endpoint.startsWith("https://")) { "BYOK endpoint must use HTTPS" }
        require(model.isNotBlank()) { "BYOK model is not configured" }
        require(apiKey.isNotBlank()) { "Gemini API key is not configured" }

        val target = targetUrl(endpoint, model)
        val generationConfig = JSONObject().put("maxOutputTokens", 2048)
        ReasoningCapabilities.geminiThinking(endpoint, model, reasoningEffort)?.let { control ->
            val thinking = JSONObject()
            control.level?.let { thinking.put("thinkingLevel", it) }
            control.budgetTokens?.let { thinking.put("thinkingBudget", it) }
            if (thinking.length() > 0) generationConfig.put("thinkingConfig", thinking)
        }
        val body = JSONObject()
            .put(
                "systemInstruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", CorrectionPrompt.system(request))),
                ),
            )
            .put(
                "contents",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put(
                            "parts",
                            JSONArray().put(JSONObject().put("text", CorrectionPrompt.user(request))),
                        ),
                ),
            )
            .put("generationConfig", generationConfig)

        val connection = (URL(target).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = CorrectionTimeoutPolicy.networkReadTimeoutMs(reasoningEffort)
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("x-goog-api-key", apiKey)
        }
        try {
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }
            val status = connection.responseCode
            val responseText = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val detail = responseText.take(500).replace(Regex("\\s+"), " ")
                if (reasoningEffort != ReasoningEffort.DEFAULT && status in listOf(400, 422)) {
                    error("選択したGemini推論設定を適用できません: HTTP $status $detail")
                }
                error("Gemini request failed: HTTP $status $detail")
            }

            val candidates = JSONObject(responseText).optJSONArray("candidates")
                ?: error("Gemini response has no candidates")
            val parts = candidates.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?: error("Gemini response has no content")
            return buildString {
                for (i in 0 until parts.length()) {
                    val text = parts.optJSONObject(i)?.optString("text").orEmpty()
                    if (text.isNotBlank()) append(text)
                }
            }.trim().ifBlank { error("Gemini response has no text") }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        fun targetUrl(endpoint: String, model: String): String {
            val normalized = endpoint.trimEnd('/')
            if (normalized.contains(":generateContent")) return normalized
            val modelName = model.removePrefix("models/")
            val encoded = URLEncoder.encode(modelName, Charsets.UTF_8.name()).replace("+", "%20")
            return "$normalized/models/$encoded:generateContent"
        }

        private const val CONNECT_TIMEOUT_MS = 8_000
    }
}
