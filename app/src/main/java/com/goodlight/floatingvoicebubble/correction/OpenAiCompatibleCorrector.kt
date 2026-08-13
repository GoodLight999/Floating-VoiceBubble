package com.goodlight.floatingvoicebubble.correction

import com.goodlight.floatingvoicebubble.ReasoningEffort
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

class OpenAiCompatibleCorrector(
    private val endpoint: String,
    private val model: String,
    private val apiKey: String,
    private val reasoningEffort: ReasoningEffort = ReasoningEffort.DEFAULT,
) : TextCorrector {
    override val id: String = "byok:openai-compatible:$model"

    override fun correct(request: CorrectionRequest): String {
        require(endpoint.startsWith("https://")) { "BYOK endpoint must use HTTPS" }
        require(model.isNotBlank()) { "BYOK model is not configured" }
        val body = JSONObject()
            .put("model", model)
            .put("temperature", 0)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", CorrectionPrompt.system(request)))
                    .put(JSONObject().put("role", "user").put("content", CorrectionPrompt.user(request))),
            )
        applyReasoning(body)

        var result = execute(body)
        if (
            result.status in listOf(400, 422) &&
            result.text.contains("temperature", ignoreCase = true)
        ) {
            // Reasoning-oriented and newer models often reject temperature. Retrying the same
            // semantic request without this optional sampling parameter keeps compatibility.
            body.remove("temperature")
            result = execute(body)
        }
        if (result.status !in 200..299) {
            error("BYOK request failed: HTTP ${result.status} ${compact(result.text)}")
        }

        val response = JSONObject(result.text)
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
        }.trim().ifBlank { error("BYOK response has no text") }
    }

    private fun applyReasoning(body: JSONObject) {
        if (reasoningEffort == ReasoningEffort.DEFAULT) return
        val host = runCatching { URI(endpoint).host.orEmpty() }.getOrDefault("")
        if (host.equals("openrouter.ai", ignoreCase = true)) {
            body.put("reasoning", JSONObject().put("effort", openRouterEffort(reasoningEffort)))
        } else {
            body.put("reasoning_effort", openAiEffort(reasoningEffort))
        }
    }

    private fun execute(body: JSONObject): HttpResult {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
        }
        return try {
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }
            val status = connection.responseCode
            val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            HttpResult(status, text)
        } finally {
            connection.disconnect()
        }
    }

    private fun openAiEffort(value: ReasoningEffort): String = when (value) {
        ReasoningEffort.DEFAULT -> error("DEFAULT is omitted")
        ReasoningEffort.NONE -> "none"
        ReasoningEffort.MINIMAL -> "minimal"
        ReasoningEffort.LOW -> "low"
        ReasoningEffort.MEDIUM -> "medium"
        ReasoningEffort.HIGH -> "high"
        ReasoningEffort.XHIGH, ReasoningEffort.MAX -> "xhigh"
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

    private fun compact(value: String): String = value.take(500).replace(Regex("\\s+"), " ").trim()

    private data class HttpResult(val status: Int, val text: String)

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 35_000
    }
}