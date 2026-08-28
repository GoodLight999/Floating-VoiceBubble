package com.goodlight.floatingvoicebubble.correction

import com.goodlight.floatingvoicebubble.ReasoningEffort
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

class OpenAiWireBodyTest {
    private val request = CorrectionRequest(
        rawTranscript = "えー今日はテストです",
        alternatives = listOf("えー今日はテストです"),
        surroundingContext = "",
        dictionaryTerms = emptyList(),
    )

    @Test
    fun openAiNativeUsesReasoningEffortOnly() {
        val capture = CaptureConnection()
        OpenAiCompatibleCorrector(
            endpoint = "https://api.openai.com/v1/chat/completions",
            model = "gpt-5.6-terra",
            apiKey = "secret",
            reasoningEffort = ReasoningEffort.MAX,
            connectionFactory = { capture },
        ).correctDetailed(request)

        val body = capture.body()
        assertEquals("gpt-5.6-terra", body.getString("model"))
        assertEquals("max", body.getString("reasoning_effort"))
        assertFalse(body.has("reasoning"))
        assertFalse(body.has("thinking"))
        assertFalse(body.has("do_sample"))
    }

    @Test
    fun openRouterUsesReasoningObjectAndPreservesMax() {
        val capture = CaptureConnection()
        OpenAiCompatibleCorrector(
            endpoint = "https://openrouter.ai/api/v1/chat/completions",
            model = "deepseek/deepseek-v4-flash-0731",
            apiKey = "secret",
            reasoningEffort = ReasoningEffort.MAX,
            connectionFactory = { capture },
        ).correctDetailed(request)

        val body = capture.body()
        assertEquals("max", body.getJSONObject("reasoning").getString("effort"))
        assertFalse(body.has("reasoning_effort"))
        assertFalse(body.has("thinking"))
    }

    @Test
    fun zai47UsesBinaryThinkingAndProviderSpecificDeterminism() {
        val capture = CaptureConnection()
        OpenAiCompatibleCorrector(
            endpoint = "https://api.z.ai/api/paas/v4/chat/completions",
            model = "GLM-4.7",
            apiKey = "secret",
            reasoningEffort = ReasoningEffort.NONE,
            connectionFactory = { capture },
        ).correctDetailed(request)

        val body = capture.body()
        assertEquals("glm-4.7", body.getString("model"))
        assertEquals("disabled", body.getJSONObject("thinking").getString("type"))
        assertEquals(false, body.getBoolean("do_sample"))
        assertTrue(body.getInt("max_tokens") >= 512)
        assertFalse(body.has("reasoning_effort"))
        assertFalse(body.has("reasoning"))
    }

    @Test
    fun zai47ProviderDefaultLeavesThinkingOmittedButReservesThinkingHeadroom() {
        val capture = CaptureConnection()
        OpenAiCompatibleCorrector(
            endpoint = "https://api.z.ai/api/paas/v4/chat/completions",
            model = "GLM-4.7",
            apiKey = "secret",
            reasoningEffort = ReasoningEffort.DEFAULT,
            connectionFactory = { capture },
        ).correctDetailed(request)

        val body = capture.body()
        assertFalse(body.has("thinking"))
        assertFalse(body.has("reasoning_effort"))
        assertTrue(body.getInt("max_tokens") >= 4096)
    }

    @Test
    fun zai53UsesRequiredThinkingPlusReasoningEffortAndNeverSendsDisabled() {
        val capture = CaptureConnection()
        OpenAiCompatibleCorrector(
            endpoint = "https://api.z.ai/api/paas/v4/chat/completions",
            model = "GLM-5.3",
            apiKey = "secret",
            reasoningEffort = ReasoningEffort.MAX,
            connectionFactory = { capture },
        ).correctDetailed(request)

        val body = capture.body()
        assertEquals("glm-5.3", body.getString("model"))
        assertEquals("enabled", body.getJSONObject("thinking").getString("type"))
        assertEquals("max", body.getString("reasoning_effort"))
        assertEquals(false, body.getBoolean("do_sample"))
        assertTrue(body.getInt("max_tokens") >= 4096)
        assertFalse(body.has("reasoning"))
    }

    @Test
    fun unknownZaiModelDoesNotSprayReasoningControls() {
        val capture = CaptureConnection()
        OpenAiCompatibleCorrector(
            endpoint = "https://api.z.ai/api/paas/v4/chat/completions",
            model = "GLM-Future-Unknown",
            apiKey = "secret",
            reasoningEffort = ReasoningEffort.HIGH,
            connectionFactory = { capture },
        ).correctDetailed(request)

        val body = capture.body()
        assertFalse(body.has("thinking"))
        assertFalse(body.has("reasoning_effort"))
        assertFalse(body.has("reasoning"))
    }

    @Test
    fun unknownCompatibleEndpointGetsPortableCoreOnly() {
        val capture = CaptureConnection()
        OpenAiCompatibleCorrector(
            endpoint = "https://llm.example.test/v1/chat/completions",
            model = "custom-model",
            apiKey = "secret",
            reasoningEffort = ReasoningEffort.HIGH,
            connectionFactory = { capture },
        ).correctDetailed(request)

        val body = capture.body()
        assertEquals("custom-model", body.getString("model"))
        assertTrue(body.has("messages"))
        assertFalse(body.has("reasoning_effort"))
        assertFalse(body.has("reasoning"))
        assertFalse(body.has("thinking"))
        assertFalse(body.has("do_sample"))
    }

    private class CaptureConnection : HttpURLConnection(URL("https://capture.invalid")) {
        private val sent = ByteArrayOutputStream()
        override fun getOutputStream() = sent
        override fun getResponseCode(): Int = 200
        override fun getInputStream() = ByteArrayInputStream(
            """{"choices":[{"message":{"content":"今日はテストです。"}}]}""".toByteArray(),
        )
        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false
        override fun connect() = Unit
        fun body(): JSONObject = JSONObject(sent.toString(Charsets.UTF_8.name()))
    }
}
