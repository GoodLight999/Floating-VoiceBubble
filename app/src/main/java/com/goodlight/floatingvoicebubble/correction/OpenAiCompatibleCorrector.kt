package com.goodlight.floatingvoicebubble.correction

import com.goodlight.floatingvoicebubble.ReasoningEffort
import org.json.JSONArray
import org.json.JSONObject
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

class OpenAiCompatibleCorrector(
    private val endpoint: String,
    private val model: String,
    private val apiKey: String,
    private val reasoningEffort: ReasoningEffort = ReasoningEffort.DEFAULT,
    private val connectionFactory: (URL) -> HttpURLConnection = { url -> url.openConnection() as HttpURLConnection },
) : TextCorrector {
    override val id: String = "byok:openai-compatible:$model"

    override fun correct(request: CorrectionRequest): String = correctDetailed(request).text

    override fun correctDetailed(request: CorrectionRequest): CorrectionCallResult {
        require(endpoint.startsWith("https://")) { "BYOK endpoint must use HTTPS" }
        require(model.isNotBlank()) { "BYOK model is not configured" }

        val options = OpenAiProviderCompatibility.resolve(endpoint, model, reasoningEffort)
        val body = JSONObject()
            .put("model", options.requestModel)
            // Streaming is operationally important, not cosmetic: reasoning/content chunks keep the
            // idle timeout alive so an actively responding model is never killed by a short total deadline.
            .put("stream", true)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", CorrectionPrompt.system(request)))
                    .put(JSONObject().put("role", "user").put("content", CorrectionPrompt.user(request))),
            )
        if (options.zaiProvider) {
            body.put("max_tokens", zaiCorrectionMaxTokens(request, options.zaiThinkingEnabled))
        }
        applyProviderOptions(body, options)

        val timings = mutableListOf<CorrectionAttemptTiming>()
        var attempts = 1
        var result = executeAttempt(body, options, attempts, timings)
        val explicitReasoning = reasoningEffort != ReasoningEffort.DEFAULT && hasReasoningField(body)

        // A few nominally OpenAI-compatible gateways do not implement SSE. Prefer streaming, but
        // fall back once to the same semantic request without streaming when the server explicitly
        // rejects only the stream parameter.
        if (result.status in listOf(400, 422) && streamParameterRejected(result.text)) {
            body.put("stream", false)
            attempts += 1
            result = executeAttempt(body, options, attempts, timings)
        }

        if (result.status in listOf(400, 422) && optionalParameterRejected(result.text)) {
            if (explicitReasoning && reasoningParameterRejected(result.text)) {
                throw callFailure(
                    message = "選択した推論設定はこのモデル/APIで受け付けられません: HTTP ${result.status} ${compact(result.text)}",
                    stage = "http-unsupported-reasoning",
                    attempts = attempts,
                    status = result.status,
                    responsePresent = result.text.isNotBlank(),
                    timings = timings,
                )
            }

            // Drop only provider conveniences whose removal does not change the user's requested
            // reasoning semantics. The next request retains reasoning_effort/reasoning/thinking.
            val canRetryPortableConveniences = body.has("do_sample") || options.sendEnglishAcceptLanguage
            if (canRetryPortableConveniences) {
                body.remove("do_sample")
                attempts += 1
                result = executeAttempt(body, options.copy(sendEnglishAcceptLanguage = false), attempts, timings)
            }
        }

        if (
            result.status in listOf(400, 422) &&
            explicitReasoning &&
            reasoningParameterRejected(result.text)
        ) {
            throw callFailure(
                message = "選択した推論設定はこのモデル/APIで受け付けられません: HTTP ${result.status} ${compact(result.text)}",
                stage = "http-unsupported-reasoning",
                attempts = attempts,
                status = result.status,
                responsePresent = result.text.isNotBlank(),
                timings = timings,
            )
        }
        if (result.status !in 200..299) {
            throw callFailure(
                message = "BYOK request failed: HTTP ${result.status} ${compact(result.text)}",
                stage = "http",
                attempts = attempts,
                status = result.status,
                responsePresent = result.text.isNotBlank(),
                timings = timings,
            )
        }
        if (result.text.isBlank()) {
            throw callFailure(
                message = "BYOK response body is empty",
                stage = "empty-response",
                attempts = attempts,
                status = result.status,
                responsePresent = false,
                timings = timings,
            )
        }

        val text = try {
            parseCompletion(result.text, options)
        } catch (failure: Throwable) {
            if (failure is CorrectionCallException) throw failure
            throw CorrectionCallException(
                message = failure.message ?: "BYOK response parse failed",
                stage = "parse-response",
                attempts = attempts,
                httpStatus = result.status,
                responsePresent = true,
                errorClass = failure.javaClass.simpleName,
                attemptTimings = timings.toList(),
                cause = failure,
            )
        }

        return CorrectionCallResult(
            text = text,
            metadata = CorrectionCallMetadata(
                attempts = attempts,
                httpStatus = result.status,
                responsePresent = true,
                attemptTimings = timings.toList(),
            ),
        )
    }

    private fun parseCompletion(value: String, options: OpenAiProviderOptions): String {
        val trimmed = value.trim()
        val looksLikeSse = trimmed.startsWith("data:") || trimmed.contains("\ndata:")
        return if (looksLikeSse) parseSseCompletion(trimmed, options) else parseJsonCompletion(trimmed, options)
    }

    private fun parseSseCompletion(value: String, options: OpenAiProviderOptions): String {
        val finalText = StringBuilder()
        var reasoningSeen = false
        var eventSeen = false
        value.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (!line.startsWith("data:")) return@forEach
            val payload = line.removePrefix("data:").trim()
            if (payload.isBlank() || payload == "[DONE]") return@forEach
            eventSeen = true
            val root = runCatching { JSONObject(payload) }.getOrNull() ?: return@forEach
            val choices = root.optJSONArray("choices") ?: return@forEach
            for (i in 0 until choices.length()) {
                val choice = choices.optJSONObject(i) ?: continue
                val delta = choice.optJSONObject("delta") ?: choice.optJSONObject("message") ?: continue
                appendContent(finalText, delta.opt("content"))
                if (delta.optString("reasoning_content").isNotBlank() ||
                    delta.optString("reasoning").isNotBlank() ||
                    delta.opt("reasoning_details") != null
                ) {
                    reasoningSeen = true
                }
            }
        }
        val text = finalText.toString().trim()
        if (text.isNotBlank()) return text
        if (reasoningSeen) {
            throw IllegalStateException(
                if (options.zaiProvider) zaiReasoningOnlyMessage(options)
                else "BYOK response contained reasoning but no final text",
            )
        }
        if (!eventSeen) throw IllegalStateException("BYOK streaming response contained no data events")
        throw IllegalStateException("BYOK streaming response has no text")
    }

    private fun parseJsonCompletion(value: String, options: OpenAiProviderOptions): String {
        val response = JSONObject(value)
        val choice = response.optJSONArray("choices")?.optJSONObject(0)
            ?: throw IllegalStateException("BYOK response has no choices")
        val message = choice.optJSONObject("message") ?: choice.optJSONObject("delta")
            ?: throw IllegalStateException("BYOK response has no message")
        val content = StringBuilder().also { appendContent(it, message.opt("content")) }.toString().trim()
        if (content.isNotBlank()) return content
        val reasoningSeen = message.optString("reasoning_content").isNotBlank() ||
            message.optString("reasoning").isNotBlank() ||
            message.opt("reasoning_details") != null
        if (reasoningSeen) {
            throw IllegalStateException(
                if (options.zaiProvider) zaiReasoningOnlyMessage(options)
                else "BYOK response contained reasoning but no final text",
            )
        }
        throw IllegalStateException("BYOK response has no text")
    }

    private fun appendContent(target: StringBuilder, content: Any?) {
        when (content) {
            is String -> target.append(content)
            is JSONArray -> {
                for (i in 0 until content.length()) {
                    val part = content.optJSONObject(i) ?: continue
                    when (part.optString("type")) {
                        "text", "output_text" -> target.append(part.optString("text"))
                    }
                }
            }
        }
    }

    private fun applyProviderOptions(body: JSONObject, options: OpenAiProviderOptions) {
        options.openAiReasoningEffort?.let { body.put("reasoning_effort", it) }
        options.openRouterReasoningEffort?.let { effort ->
            body.put("reasoning", JSONObject().put("effort", effort))
        }
        options.zaiThinkingEnabled?.let { enabled ->
            body.put("thinking", JSONObject().put("type", if (enabled) "enabled" else "disabled"))
        }
        options.zaiReasoningEffort?.let { body.put("reasoning_effort", it) }
        if (options.disableSampling) body.put("do_sample", false)
    }

    private fun executeAttempt(
        body: JSONObject,
        options: OpenAiProviderOptions,
        attempt: Int,
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
                if (options.sendEnglishAcceptLanguage) setRequestProperty("Accept-Language", "en-US,en")
                if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
            }
        } catch (failure: Throwable) {
            throw transportFailure(failure, attempt, timings)
        }

        return try {
            TimedHttpTransport.execute(connection, body.toString(), attempt, idleTimeoutMs.toLong()).also {
                timings += it.timing
            }
        } catch (failure: TimedHttpTransportFailure) {
            timings += failure.timing
            throw transportFailure(failure.transportCause, attempt, timings)
        }
    }

    private fun transportFailure(
        failure: Throwable,
        attempt: Int,
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
            attempts = attempt,
            responsePresent = false,
            errorClass = failure.javaClass.simpleName,
            attemptTimings = timings.toList(),
            cause = failure,
        )
    }

    private fun callFailure(
        message: String,
        stage: String,
        attempts: Int,
        status: Int?,
        responsePresent: Boolean,
        timings: List<CorrectionAttemptTiming>,
    ) = CorrectionCallException(
        message = message,
        stage = stage,
        attempts = attempts,
        httpStatus = status,
        responsePresent = responsePresent,
        errorClass = "HttpResponseError",
        attemptTimings = timings.toList(),
    )

    private fun hasReasoningField(body: JSONObject): Boolean =
        body.has("reasoning_effort") || body.has("reasoning") || body.has("thinking")

    private fun reasoningParameterRejected(value: String): Boolean {
        val lower = value.lowercase()
        return listOf("reasoning_effort", "reasoning.effort", "thinking.type", "thinking")
            .any(lower::contains)
    }

    private fun streamParameterRejected(value: String): Boolean {
        val lower = value.lowercase()
        return lower.contains("stream") && listOf(
            "unsupported", "unknown", "unrecognized", "invalid", "not support",
        ).any(lower::contains)
    }

    private fun optionalParameterRejected(value: String): Boolean {
        val lower = value.lowercase()
        return listOf(
            "reasoning_effort",
            "reasoning",
            "thinking",
            "do_sample",
            "unknown field",
            "unsupported parameter",
            "unrecognized field",
        ).any(lower::contains)
    }

    private fun zaiCorrectionMaxTokens(request: CorrectionRequest, thinkingEnabled: Boolean?): Int {
        val rawChars = request.rawTranscript.length.coerceAtLeast(1)
        val thinking = thinkingEnabled != false
        // Z.AI's own GLM-4.7 thinking example uses a 4096-token output cap. Keeping only 2048 here
        // made a short correction vulnerable to consuming the whole cap in reasoning_content before
        // a final content field was produced. This remains a cap, not a requested output length.
        val reasoningHeadroom = if (thinking) 2_048 else 256
        val minimum = if (thinking) 4_096 else 512
        return (rawChars * 2L + reasoningHeadroom)
            .coerceIn(minimum.toLong(), MAX_ZAI_CORRECTION_TOKENS.toLong())
            .toInt()
    }

    private fun zaiReasoningOnlyMessage(options: OpenAiProviderOptions): String =
        if (options.zaiReasoningEffort != null) {
            "Z.AIは思考内容だけを返し、確定本文を返しませんでした。音声補正では推論深度を『低』またはモデル既定へ下げてください。"
        } else {
            "Z.AIは思考内容だけを返し、確定本文を返しませんでした。音声補正では『思考OFF』またはモデル既定を試してください。"
        }

    private fun compact(value: String): String = value.take(500).replace(Regex("\\s+"), " ").trim()

    companion object {
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val MAX_ZAI_CORRECTION_TOKENS = 16_384
    }
}
