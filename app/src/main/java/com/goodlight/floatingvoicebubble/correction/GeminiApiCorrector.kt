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
        thinkingConfig()?.let { generationConfig.put("thinkingConfig", it) }
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
            readTimeout = READ_TIMEOUT_MS
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
                error("Gemini request failed: HTTP $status ${responseText.take(500).replace(Regex("\\s+"), " ")}")
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

    private fun thinkingConfig(): JSONObject? {
        if (reasoningEffort == ReasoningEffort.DEFAULT) return null
        val normalizedModel = model.removePrefix("models/").lowercase()
        return if (normalizedModel.startsWith("gemini-2.5")) {
            JSONObject().put("thinkingBudget", gemini25Budget(reasoningEffort))
        } else {
            JSONObject().put("thinkingLevel", gemini3Level(reasoningEffort))
        }
    }

    private fun gemini3Level(value: ReasoningEffort): String = when (value) {
        ReasoningEffort.DEFAULT -> error("DEFAULT is omitted")
        ReasoningEffort.NONE, ReasoningEffort.MINIMAL -> "minimal"
        ReasoningEffort.LOW -> "low"
        ReasoningEffort.MEDIUM -> "medium"
        ReasoningEffort.HIGH, ReasoningEffort.XHIGH, ReasoningEffort.MAX -> "high"
    }

    private fun gemini25Budget(value: ReasoningEffort): Int = when (value) {
        ReasoningEffort.DEFAULT -> error("DEFAULT is omitted")
        ReasoningEffort.NONE -> 0
        ReasoningEffort.MINIMAL -> 512
        ReasoningEffort.LOW -> 1024
        ReasoningEffort.MEDIUM -> 4096
        ReasoningEffort.HIGH -> 8192
        ReasoningEffort.XHIGH, ReasoningEffort.MAX -> 16384
    }

    companion object {
        fun targetUrl(endpoint: String, model: String): String {
            val normalized = endpoint.trimEnd('/')
            if (normalized.contains(":generateContent")) return normalized
            val modelName = model.removePrefix("models/")
            val encoded = URLEncoder.encode(modelName, Charsets.UTF_8.name()).replace("+", "%20")
            return "$normalized/models/$encoded:generateContent"
        }

        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 35_000
    }
}