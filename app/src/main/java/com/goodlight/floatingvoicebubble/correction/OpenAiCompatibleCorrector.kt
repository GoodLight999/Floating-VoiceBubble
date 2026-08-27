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
            .put("stream", false)
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
        if (result.status in listOf(400, 422) && optionalParameterRejected(result.text)) {
            val explicitReasoning = reasoningEffort != ReasoningEffort.DEFAULT &&
                (body.has("reasoning_effort") || body.has("reasoning") || body.has("thinking"))
            if (explicitReasoning) {
                throw callFailure(
                    message = "選択した推論設定はこのモデル/APIで受け付けられません: HTTP ${result.status} ${compact(result.text)}",
                    stage = "http-unsupported-reasoning",
                    attempts = attempts,
                    status = result.status,
                    responsePresent = result.text.isNotBlank(),
                    timings = timings,
                )
            }

            // Provider-specific non-reasoning conveniences must not break the portable request.
            body.remove("do_sample")
            attempts += 1
            result = executeAttempt(body, options.copy(sendEnglishAcceptLanguage = false), attempts, timings)
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
            val response = JSONObject(result.text)
            val choice = response.optJSONArray("choices")?.optJSONObject(0)
                ?: throw IllegalStateException("BYOK response has no choices")
            val message = choice.optJSONObject("message") ?: throw IllegalStateException("BYOK response has no message")
            val content = message.opt("content")
            when (content) {
                is String -> content
                is JSONArray -> buildString {
                    for (i in 0 until content.length()) {
                        val part = content.optJSONObject(i) ?: continue
                        if (part.optString("type") == "text") append(part.optString("text"))
                    }
                }
                else -> throw IllegalStateException(
                    message.optString("reasoning_content").takeIf(String::isNotBlank)?.let {
                        if (options.zaiProvider) {
                            "Z.AIは思考内容だけを返し、確定本文を返しませんでした。音声補正では『思考OFF』を試してください。"
                        } else {
                            "BYOK response contained reasoning but no final text"
                        }
                    } ?: "BYOK response content is unsupported",
                )
            }.trim().ifBlank {
                if (options.zaiProvider && message.optString("reasoning_content").isNotBlank()) {
                    throw IllegalStateException(
                        "Z.AIは思考内容だけを返し、確定本文を返しませんでした。音声補正では『思考OFF』を試してください。",
                    )
                }
                throw IllegalStateException("BYOK response has no text")
            }
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

    private fun executeAttempt(
        body: JSONObject,
        options: OpenAiProviderOptions,
        attempt: Int,
        timings: MutableList<CorrectionAttemptTiming>,
    ): TimedHttpResponse {
        val deadlineMs = CorrectionTimeoutPolicy.networkReadTimeoutMs(reasoningEffort)
        val connection = try {
            connectionFactory(URL(endpoint)).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = deadlineMs
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                if (options.sendEnglishAcceptLanguage) setRequestProperty("Accept-Language", "en-US,en")
                if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
            }
        } catch (failure: Throwable) {
            throw transportFailure(failure, attempt, timings)
        }

        return try {
            TimedHttpTransport.execute(connection, body.toString(), attempt, deadlineMs.toLong()).also {
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

    private fun optionalParameterRejected(value: String): Boolean {
        val lower = value.lowercase()
        return listOf("reasoning_effort", "reasoning", "thinking", "do_sample", "unknown field", "unsupported parameter")
            .any(lower::contains)
    }

    private fun zaiCorrectionMaxTokens(request: CorrectionRequest, thinkingEnabled: Boolean?): Int {
        val rawChars = request.rawTranscript.length.coerceAtLeast(1)
        val reasoningHeadroom = if (thinkingEnabled == false) 256 else 1_024
        val minimum = if (thinkingEnabled == false) 512 else 2_048
        return (rawChars * 2L + reasoningHeadroom)
            .coerceIn(minimum.toLong(), MAX_ZAI_CORRECTION_TOKENS.toLong())
            .toInt()
    }

    private fun compact(value: String): String = value.take(500).replace(Regex("\\s+"), " ").trim()

    companion object {
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val MAX_ZAI_CORRECTION_TOKENS = 16_384
    }
}
