package com.goodlight.floatingvoicebubble.correction

import com.goodlight.floatingvoicebubble.ReasoningEffort
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
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

        val options = OpenAiProviderCompatibility.resolve(endpoint, model, reasoningEffort)
        val body = JSONObject()
            .put("model", options.requestModel)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", CorrectionPrompt.system(request)))
                    .put(JSONObject().put("role", "user").put("content", CorrectionPrompt.user(request))),
            )
        applyProviderOptions(body, options)

        var result = execute(body, options)
        if (result.status in listOf(400, 422) && optionalParameterRejected(result.text)) {
            // Optional provider controls are never allowed to make the core correction request unusable.
            // Retry once with the portable Chat Completions subset only.
            body.remove("reasoning_effort")
            body.remove("reasoning")
            body.remove("thinking")
            body.remove("do_sample")
            result = execute(body, options.copy(sendEnglishAcceptLanguage = false))
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
            else -> error(
                message.optString("reasoning_content").takeIf(String::isNotBlank)?.let {
                    "BYOK response contained reasoning but no final text"
                } ?: "BYOK response content is unsupported",
            )
        }.trim().ifBlank { error("BYOK response has no text") }
    }

    private fun applyProviderOptions(body: JSONObject, options: OpenAiProviderOptions) {
        options.openAiReasoningEffort?.let { body.put("reasoning_effort", it) }
        options.openRouterReasoningEffort?.let { effort ->
            body.put("reasoning", JSONObject().put("effort", effort))
        }
        options.zaiThinkingEnabled?.let { enabled ->
            body.put("thinking", JSONObject().put("type", if (enabled) "enabled" else "disabled"))
        }
        if (options.disableSampling) body.put("do_sample", false)
    }

    private fun execute(body: JSONObject, options: OpenAiProviderOptions): HttpResult {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            if (options.sendEnglishAcceptLanguage) setRequestProperty("Accept-Language", "en-US,en")
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

    private fun optionalParameterRejected(value: String): Boolean {
        val lower = value.lowercase()
        return listOf("reasoning_effort", "reasoning", "thinking", "do_sample", "unknown field", "unsupported parameter")
            .any(lower::contains)
    }

    private fun compact(value: String): String = value.take(500).replace(Regex("\\s+"), " ").trim()

    private data class HttpResult(val status: Int, val text: String)

    companion object {
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 25_000
    }
}