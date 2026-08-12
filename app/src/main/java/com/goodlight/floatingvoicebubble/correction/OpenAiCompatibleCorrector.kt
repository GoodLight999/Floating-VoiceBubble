package com.goodlight.floatingvoicebubble.correction

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class OpenAiCompatibleCorrector(
    private val endpoint: String,
    private val model: String,
    private val apiKey: String,
) : TextCorrector {
    private val protocol = CloudCorrectorFactory.protocolFor(endpoint)

    override val id: String = when (protocol) {
        CloudCorrectorFactory.Protocol.OPENAI_COMPATIBLE -> "byok:openai-compatible:$model"
        CloudCorrectorFactory.Protocol.ANTHROPIC -> "byok:anthropic:$model"
        CloudCorrectorFactory.Protocol.GEMINI -> "byok:gemini:$model"
    }

    override fun correct(request: CorrectionRequest): String = when (protocol) {
        CloudCorrectorFactory.Protocol.ANTHROPIC -> AnthropicCorrector(endpoint, model, apiKey).correct(request)
        CloudCorrectorFactory.Protocol.GEMINI -> GeminiApiCorrector(endpoint, model, apiKey).correct(request)
        CloudCorrectorFactory.Protocol.OPENAI_COMPATIBLE -> correctOpenAiCompatible(request)
    }

    private fun correctOpenAiCompatible(request: CorrectionRequest): String {
        require(endpoint.startsWith("https://")) { "BYOK endpoint must use HTTPS" }
        require(model.isNotBlank()) { "BYOK model is not configured" }
        val body = JSONObject().put("model", model).put("temperature", 0).put(
            "messages",
            JSONArray()
                .put(JSONObject().put("role", "system").put("content", CorrectionPrompt.SYSTEM.trim()))
                .put(JSONObject().put("role", "user").put("content", CorrectionPrompt.user(request))),
        )
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 45_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
        }
        try {
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }
            val status = connection.responseCode
            val responseText = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                error("BYOK request failed: HTTP $status ${responseText.take(500).replace(Regex("\\s+"), " ")}")
            }
            val response = JSONObject(responseText)
            val choice = response.optJSONArray("choices")?.optJSONObject(0)
                ?: error("BYOK response has no choices")
            val message = choice.optJSONObject("message") ?: error("BYOK response has no message")
            val content = message.opt("content")
            return when (content) {
                is String -> content
                is JSONArray -> buildString {
                    for (i in 0 until content.length()) {
                        val part = content.optJSONObject(i) ?: continue
                        if (part.optString("type") == "text") append(part.optString("text"))
                    }
                }
                else -> error("BYOK response content is unsupported")
            }.trim()
        } finally {
            connection.disconnect()
        }
    }
}
