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

class NativeProviderWireBodyTest {
    private val request = CorrectionRequest(
        rawTranscript = "えー今日はテストです",
        alternatives = listOf("えー今日はテストです"),
        surroundingContext = "",
        dictionaryTerms = emptyList(),
    )

    @Test
    fun anthropicAdaptiveThinkingUsesDocumentedFields() {
        val capture = CaptureConnection(
            """{"content":[{"type":"text","text":"今日はテストです。"}]}""",
        )
        AnthropicCorrector(
            endpoint = "https://api.anthropic.com/v1/messages",
            model = "claude-opus-5",
            apiKey = "secret",
            reasoningEffort = ReasoningEffort.XHIGH,
            connectionFactory = { capture },
        ).correctDetailed(request)

        val body = capture.body()
        assertEquals("adaptive", body.getJSONObject("thinking").getString("type"))
        assertEquals("xhigh", body.getJSONObject("output_config").getString("effort"))
        assertTrue(body.getBoolean("stream"))
        assertTrue(body.has("system"))
        assertTrue(body.has("messages"))
    }

    @Test
    fun anthropicDefaultOmitsOptionalThinkingControls() {
        val capture = CaptureConnection(
            """{"content":[{"type":"text","text":"今日はテストです。"}]}""",
        )
        AnthropicCorrector(
            endpoint = "https://api.anthropic.com/v1/messages",
            model = "claude-opus-5",
            apiKey = "secret",
            reasoningEffort = ReasoningEffort.DEFAULT,
            connectionFactory = { capture },
        ).correctDetailed(request)

        val body = capture.body()
        assertTrue(body.getBoolean("stream"))
        assertFalse(body.has("thinking"))
        assertFalse(body.has("output_config"))
    }

    @Test
    fun anthropicStreamingTextDeltasAreJoinedWhileThinkingIsIgnored() {
        val stream = listOf(
            "data: {\"type\":\"message_start\",\"message\":{}}",
            "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"内部思考\"}}",
            "data: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"text_delta\",\"text\":\"今日は\"}}",
            "data: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"text_delta\",\"text\":\"テストです。\"}}",
            "data: {\"type\":\"message_stop\"}",
        ).joinToString("\n\n")
        val capture = CaptureConnection(stream)
        val result = AnthropicCorrector(
            endpoint = "https://api.anthropic.com/v1/messages",
            model = "claude-opus-5",
            apiKey = "secret",
            reasoningEffort = ReasoningEffort.HIGH,
            connectionFactory = { capture },
        ).correctDetailed(request)

        assertEquals("今日はテストです。", result.text)
        assertTrue(capture.body().getBoolean("stream"))
    }

    @Test
    fun gemini3UsesThinkingLevelNotBudget() {
        val capture = CaptureConnection(geminiResponse())
        GeminiApiCorrector(
            endpoint = "https://generativelanguage.googleapis.com/v1beta",
            model = "gemini-3.7-flash",
            apiKey = "secret",
            reasoningEffort = ReasoningEffort.MEDIUM,
            connectionFactory = { capture },
        ).correctDetailed(request)

        val thinking = capture.body()
            .getJSONObject("generationConfig")
            .getJSONObject("thinkingConfig")
        assertEquals("medium", thinking.getString("thinkingLevel"))
        assertFalse(thinking.has("thinkingBudget"))
    }

    @Test
    fun gemini25FlashOffUsesZeroBudgetNotThinkingLevel() {
        val capture = CaptureConnection(geminiResponse())
        GeminiApiCorrector(
            endpoint = "https://generativelanguage.googleapis.com/v1beta",
            model = "gemini-2.5-flash",
            apiKey = "secret",
            reasoningEffort = ReasoningEffort.NONE,
            connectionFactory = { capture },
        ).correctDetailed(request)

        val thinking = capture.body()
            .getJSONObject("generationConfig")
            .getJSONObject("thinkingConfig")
        assertEquals(0, thinking.getInt("thinkingBudget"))
        assertFalse(thinking.has("thinkingLevel"))
    }

    @Test
    fun geminiDefaultOmitsThinkingConfig() {
        val capture = CaptureConnection(geminiResponse())
        GeminiApiCorrector(
            endpoint = "https://generativelanguage.googleapis.com/v1beta",
            model = "gemini-3.7-flash",
            apiKey = "secret",
            reasoningEffort = ReasoningEffort.DEFAULT,
            connectionFactory = { capture },
        ).correctDetailed(request)

        assertFalse(capture.body().getJSONObject("generationConfig").has("thinkingConfig"))
    }

    @Test
    fun geminiStreamingTargetUsesOfficialSseEndpoint() {
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.7-flash:streamGenerateContent?alt=sse",
            GeminiApiCorrector.streamingTargetUrl(
                "https://generativelanguage.googleapis.com/v1beta",
                "gemini-3.7-flash",
            ),
        )
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.7-flash:streamGenerateContent?key=ignored&alt=sse",
            GeminiApiCorrector.streamingTargetUrl(
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.7-flash:generateContent?key=ignored",
                "gemini-3.7-flash",
            ),
        )
    }

    @Test
    fun geminiStreamingChunksJoinTextAndIgnoreThoughtParts() {
        val stream = listOf(
            "data: {\"candidates\":[{\"content\":{\"parts\":[{\"thought\":true,\"text\":\"内部思考\"}]}}]}",
            "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"今日は\"}]}}]}",
            "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"テストです。\"}]}}]}",
        ).joinToString("\n\n")
        val capture = CaptureConnection(stream)
        val result = GeminiApiCorrector(
            endpoint = "https://generativelanguage.googleapis.com/v1beta",
            model = "gemini-3.7-flash",
            apiKey = "secret",
            reasoningEffort = ReasoningEffort.HIGH,
            connectionFactory = { capture },
        ).correctDetailed(request)

        assertEquals("今日はテストです。", result.text)
    }

    private fun geminiResponse() =
        """{"candidates":[{"content":{"parts":[{"text":"今日はテストです。"}]}}]}"""

    private class CaptureConnection(private val response: String) : HttpURLConnection(URL("https://capture.invalid")) {
        private val sent = ByteArrayOutputStream()
        override fun getOutputStream() = sent
        override fun getResponseCode(): Int = 200
        override fun getInputStream() = ByteArrayInputStream(response.toByteArray())
        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false
        override fun connect() = Unit
        fun body(): JSONObject = JSONObject(sent.toString(Charsets.UTF_8.name()))
    }
}
