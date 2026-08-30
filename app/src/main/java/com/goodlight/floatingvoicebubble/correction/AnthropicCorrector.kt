package com.goodlight.floatingvoicebubble.correction

import com.goodlight.floatingvoicebubble.ReasoningEffort
import org.json.JSONArray
import org.json.JSONObject
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

class AnthropicCorrector(
    private val endpoint: String,
    private val model: String,
    private val apiKey: String,
    private val reasoningEffort: ReasoningEffort = ReasoningEffort.DEFAULT,
    private val connectionFactory: (URL) -> HttpURLConnection = { url -> url.openConnection() as HttpURLConnection },
) : TextCorrector {
    override val id: String = "byok:anthropic:$model"

    override fun correct(request: CorrectionRequest): String = correctDetailed(request).text

    override fun correctDetailed(request: CorrectionRequest): CorrectionCallResult {
        require(endpoint.startsWith("https://")) { "BYOK endpoint must use HTTPS" }
        require(model.isNotBlank()) { "BYOK model is not configured" }
        require(apiKey.isNotBlank()) { "Anthropic API key is not configured" }

        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", outputTokenBudget())
            .put("stream", true)
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

        val timings = mutableListOf<CorrectionAttemptTiming>()
        val response = executeAttempt(body, timings)
        if (response.status !in 200..299) {
            val detail = compact(response.text)
            throw CorrectionCallException(
                message = if (reasoningEffort != ReasoningEffort.DEFAULT && response.status in listOf(400, 422)) {
                    "選択したClaude推論設定を適用できません: HTTP ${response.status} $detail"
                } else {
                    "Anthropic request failed: HTTP ${response.status} $detail"
                },
                stage = if (reasoningEffort != ReasoningEffort.DEFAULT && response.status in listOf(400, 422)) {
                    "http-unsupported-reasoning"
                } else {
                    "http"
                },
                attempts = 1,
                httpStatus = response.status,
                responsePresent = response.text.isNotBlank(),
                errorClass = "HttpResponseError",
                attemptTimings = timings.toList(),
            )
        }
        if (response.text.isBlank()) {
            throw CorrectionCallException(
                message = "Anthropic response body is empty",
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
                message = failure.message ?: "Anthropic response parse failed",
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
            if (root.optString("type") == "error") {
                val error = root.optJSONObject("error")
                throw IllegalStateException(
                    error?.optString("message").orEmpty().ifBlank { "Anthropic streaming error" },
                )
            }
            if (root.optString("type") != "content_block_delta") return@forEach
            val delta = root.optJSONObject("delta") ?: return@forEach
            if (delta.optString("type") == "text_delta") {
                finalText.append(delta.optString("text"))
            }
        }
        val text = finalText.toString().trim()
        if (text.isNotBlank()) return text
        if (!eventSeen) throw IllegalStateException("Anthropic streaming response contained no data events")
        throw IllegalStateException("Anthropic streaming response has no text")
    }

    private fun parseJsonCompletion(value: String): String {
        val content = JSONObject(value).optJSONArray("content")
            ?: throw IllegalStateException("Anthropic response has no content")
        return buildString {
            for (i in 0 until content.length()) {
                val part = content.optJSONObject(i) ?: continue
                if (part.optString("type") == "text") append(part.optString("text"))
            }
        }.trim().ifBlank { throw IllegalStateException("Anthropic response has no text") }
    }

    private fun executeAttempt(
        body: JSONObject,
        timings: MutableList<CorrectionAttemptTiming>,
    ): TimedHttpResponse {
        val idleTimeoutMs = CorrectionTimeoutPolicy.networkIdleTimeoutMs(reasoningEffort)
        val connection = try {
            connectionFactory(URL(endpoint)).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = idleTimeoutMs
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "text/event-stream, application/json")
                setRequestProperty("x-api-key", apiKey)
                setRequestProperty("anthropic-version", "2023-06-01")
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

    private fun outputTokenBudget(): Int = when (ReasoningCapabilities.normalize(endpoint, model, reasoningEffort)) {
        ReasoningEffort.XHIGH, ReasoningEffort.MAX -> 16_384
        ReasoningEffort.HIGH -> 8_192
        ReasoningEffort.MEDIUM -> 4_096
        else -> 2_048
    }

    private fun compact(value: String): String = value.take(500).replace(Regex("\\s+"), " ").trim()

    companion object {
        private const val CONNECT_TIMEOUT_MS = 8_000
    }
}
