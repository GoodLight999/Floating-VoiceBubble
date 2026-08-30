package com.goodlight.floatingvoicebubble.correction

import com.goodlight.floatingvoicebubble.ReasoningEffort
import org.json.JSONArray
import org.json.JSONObject
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException

class GeminiApiCorrector(
    private val endpoint: String,
    private val model: String,
    private val apiKey: String,
    private val reasoningEffort: ReasoningEffort = ReasoningEffort.DEFAULT,
    private val connectionFactory: (URL) -> HttpURLConnection = { url -> url.openConnection() as HttpURLConnection },
) : TextCorrector {
    override val id: String = "byok:gemini:$model"

    override fun correct(request: CorrectionRequest): String = correctDetailed(request).text

    override fun correctDetailed(request: CorrectionRequest): CorrectionCallResult {
        require(endpoint.startsWith("https://")) { "BYOK endpoint must use HTTPS" }
        require(model.isNotBlank()) { "BYOK model is not configured" }
        require(apiKey.isNotBlank()) { "Gemini API key is not configured" }

        val target = streamingTargetUrl(endpoint, model)
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

        val timings = mutableListOf<CorrectionAttemptTiming>()
        val response = executeAttempt(target, body, timings)
        if (response.status !in 200..299) {
            val unsupportedReasoning = reasoningEffort != ReasoningEffort.DEFAULT && response.status in listOf(400, 422)
            throw CorrectionCallException(
                message = if (unsupportedReasoning) {
                    "選択したGemini推論設定を適用できません: HTTP ${response.status} ${compact(response.text)}"
                } else {
                    "Gemini request failed: HTTP ${response.status} ${compact(response.text)}"
                },
                stage = if (unsupportedReasoning) "http-unsupported-reasoning" else "http",
                attempts = 1,
                httpStatus = response.status,
                responsePresent = response.text.isNotBlank(),
                errorClass = "HttpResponseError",
                attemptTimings = timings.toList(),
            )
        }
        if (response.text.isBlank()) {
            throw CorrectionCallException(
                message = "Gemini response body is empty",
                stage = "empty-response",
                attempts = 1,
                httpStatus = response.status,
                responsePresent = false,
                errorClass = "EmptyResponse",
                attemptTimings = timings.toList(),
            )
        }

        val text = try {
            parseCompletion(response.text)
        } catch (failure: Throwable) {
            if (failure is CorrectionCallException) throw failure
            throw CorrectionCallException(
                message = failure.message ?: "Gemini response parse failed",
                stage = "parse-response",
                attempts = 1,
                httpStatus = response.status,
                responsePresent = true,
                errorClass = failure.javaClass.simpleName,
                attemptTimings = timings.toList(),
                cause = failure,
            )
        }

        return CorrectionCallResult(
            text = text,
            metadata = CorrectionCallMetadata(
                attempts = 1,
                httpStatus = response.status,
                responsePresent = true,
                attemptTimings = timings.toList(),
            ),
        )
    }

    private fun parseCompletion(value: String): String {
        val trimmed = value.trim()
        val looksLikeSse = trimmed.startsWith("data:") || trimmed.contains("\ndata:")
        return if (looksLikeSse) parseSseCompletion(trimmed) else parseJsonCompletion(trimmed)
    }

    private fun parseSseCompletion(value: String): String {
        val finalText = StringBuilder()
        var eventSeen = false
        value.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (!line.startsWith("data:")) return@forEach
            val payload = line.removePrefix("data:").trim()
            if (payload.isBlank() || payload == "[DONE]") return@forEach
            eventSeen = true
            val root = runCatching { JSONObject(payload) }.getOrNull() ?: return@forEach
            appendCandidateText(finalText, root)
        }
        val text = finalText.toString().trim()
        if (text.isNotBlank()) return text
        if (!eventSeen) throw IllegalStateException("Gemini streaming response contained no data events")
        throw IllegalStateException("Gemini streaming response has no text")
    }

    private fun parseJsonCompletion(value: String): String {
        val finalText = StringBuilder()
        appendCandidateText(finalText, JSONObject(value))
        return finalText.toString().trim().ifBlank { throw IllegalStateException("Gemini response has no text") }
    }

    private fun appendCandidateText(target: StringBuilder, root: JSONObject) {
        val candidates = root.optJSONArray("candidates") ?: return
        for (candidateIndex in 0 until candidates.length()) {
            val parts = candidates.optJSONObject(candidateIndex)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?: continue
            for (partIndex in 0 until parts.length()) {
                val part = parts.optJSONObject(partIndex) ?: continue
                if (part.optBoolean("thought", false)) continue
                val text = part.optString("text")
                if (text.isNotBlank()) target.append(text)
            }
        }
    }

    private fun executeAttempt(
        target: String,
        body: JSONObject,
        timings: MutableList<CorrectionAttemptTiming>,
    ): TimedHttpResponse {
        val idleTimeoutMs = CorrectionTimeoutPolicy.networkIdleTimeoutMs(reasoningEffort)
        val connection = try {
            connectionFactory(URL(target)).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = idleTimeoutMs
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "text/event-stream, application/json")
                setRequestProperty("x-goog-api-key", apiKey)
            }
        } catch (failure: Throwable) {
            throw transportFailure(failure, timings)
        }
        return try {
            TimedHttpTransport.execute(connection, body.toString(), 1, idleTimeoutMs.toLong()).also {
                timings += it.timing
            }
        } catch (failure: TimedHttpTransportFailure) {
            timings += failure.timing
            throw transportFailure(failure.transportCause, timings)
        }
    }

    private fun transportFailure(
        failure: Throwable,
        timings: List<CorrectionAttemptTiming>,
    ): CorrectionCallException {
        val stage = when (failure) {
            is UnknownHostException -> "dns"
            is ConnectException -> "connect"
            is SocketTimeoutException -> "network-timeout"
            else -> "network"
        }
        return CorrectionCallException(
            message = failure.message ?: failure.javaClass.simpleName,
            stage = stage,
            attempts = 1,
            responsePresent = false,
            errorClass = failure.javaClass.simpleName,
            attemptTimings = timings.toList(),
            cause = failure,
        )
    }

    private fun compact(value: String): String = value.take(500).replace(Regex("\\s+"), " ").trim()

    companion object {
        fun targetUrl(endpoint: String, model: String): String {
            val normalized = endpoint.trimEnd('/')
            if (normalized.contains(":generateContent")) return normalized
            val modelName = model.removePrefix("models/")
            val encoded = URLEncoder.encode(modelName, Charsets.UTF_8.name()).replace("+", "%20")
            return "$normalized/models/$encoded:generateContent"
        }

        fun streamingTargetUrl(endpoint: String, model: String): String {
            val normalized = endpoint.trimEnd('/')
            val streamTarget = when {
                normalized.contains(":streamGenerateContent") -> normalized
                normalized.contains(":generateContent") -> normalized.replace(":generateContent", ":streamGenerateContent")
                else -> {
                    val modelName = model.removePrefix("models/")
                    val encoded = URLEncoder.encode(modelName, Charsets.UTF_8.name()).replace("+", "%20")
                    "$normalized/models/$encoded:streamGenerateContent"
                }
            }
            if (Regex("(?:\\?|&)alt=sse(?:&|$)").containsMatchIn(streamTarget)) return streamTarget
            return streamTarget + if (streamTarget.contains('?')) "&alt=sse" else "?alt=sse"
        }

        private const val CONNECT_TIMEOUT_MS = 8_000
    }
}
