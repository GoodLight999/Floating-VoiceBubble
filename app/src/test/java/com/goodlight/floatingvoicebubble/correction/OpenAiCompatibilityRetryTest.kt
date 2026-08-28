package com.goodlight.floatingvoicebubble.correction

import com.goodlight.floatingvoicebubble.ReasoningEffort
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.ArrayDeque

class OpenAiCompatibilityRetryTest {
    private val request = CorrectionRequest(
        rawTranscript = "えー今日はテストです",
        alternatives = emptyList(),
        surroundingContext = "",
        dictionaryTerms = emptyList(),
    )

    @Test
    fun rejectedDoSampleRetriesWithoutDroppingExplicitThinkingChoice() {
        val first = CaptureConnection(
            status = 400,
            response = """{"error":{"message":"Unsupported parameter: do_sample"}}""",
        )
        val second = CaptureConnection(
            status = 200,
            response = """{"choices":[{"message":{"content":"今日はテストです。"}}]}""",
        )
        val connections = ArrayDeque(listOf(first, second))

        val result = OpenAiCompatibleCorrector(
            endpoint = ZAI_ENDPOINT,
            model = "glm-4.7",
            apiKey = "secret",
            reasoningEffort = ReasoningEffort.NONE,
            connectionFactory = { connections.removeFirst() },
        ).correctDetailed(request)

        assertEquals("今日はテストです。", result.text)
        assertEquals(2, result.metadata.attempts)
        assertEquals(2, result.metadata.attemptTimings.size)

        val firstBody = first.body()
        val secondBody = second.body()
        assertEquals("disabled", firstBody.getJSONObject("thinking").getString("type"))
        assertTrue(firstBody.has("do_sample"))
        assertEquals("disabled", secondBody.getJSONObject("thinking").getString("type"))
        assertFalse(secondBody.has("do_sample"))
    }

    @Test
    fun rejectedReasoningFieldDoesNotSilentlyRetryAtDifferentSemantics() {
        val first = CaptureConnection(
            status = 400,
            response = """{"error":{"message":"Unsupported parameter: thinking.type"}}""",
        )
        var connectionCount = 0
        try {
            OpenAiCompatibleCorrector(
                endpoint = ZAI_ENDPOINT,
                model = "glm-4.7",
                apiKey = "secret",
                reasoningEffort = ReasoningEffort.NONE,
                connectionFactory = {
                    connectionCount += 1
                    first
                },
            ).correctDetailed(request)
            fail("explicitly rejected reasoning control must fail")
        } catch (failure: CorrectionCallException) {
            assertEquals("http-unsupported-reasoning", failure.stage)
            assertEquals(1, failure.attempts)
            assertEquals(1, connectionCount)
            assertEquals(1, failure.attemptTimings.size)
        }
    }

    private class CaptureConnection(
        private val status: Int,
        private val response: String,
    ) : HttpURLConnection(URL("https://capture.invalid")) {
        private val sent = ByteArrayOutputStream()
        override fun getOutputStream() = sent
        override fun getResponseCode(): Int = status
        override fun getInputStream() = ByteArrayInputStream(response.toByteArray())
        override fun getErrorStream() = ByteArrayInputStream(response.toByteArray())
        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false
        override fun connect() = Unit
        fun body(): JSONObject = JSONObject(sent.toString(Charsets.UTF_8.name()))
    }

    companion object {
        private const val ZAI_ENDPOINT = "https://api.z.ai/api/coding/paas/v4/chat/completions"
    }
}
