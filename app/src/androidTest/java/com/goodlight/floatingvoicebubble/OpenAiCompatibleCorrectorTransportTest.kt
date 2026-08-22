package com.goodlight.floatingvoicebubble

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.goodlight.floatingvoicebubble.correction.CorrectionCallException
import com.goodlight.floatingvoicebubble.correction.CorrectionPreferences
import com.goodlight.floatingvoicebubble.correction.CorrectionRequest
import com.goodlight.floatingvoicebubble.correction.OpenAiCompatibleCorrector
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.ArrayDeque

@RunWith(AndroidJUnit4::class)
class OpenAiCompatibleCorrectorTransportTest {
    @Test
    fun immediateSuccessUsesExactZaiThinkingBodyAndReturnsMetadata() {
        val connection = FakeConnection(URL(ENDPOINT), 200, success("今日は晴れ。"))
        val corrector = corrector(ReasoningEffort.HIGH) { connection }

        val result = corrector.correctDetailed(request())

        assertEquals("今日は晴れ。", result.text)
        assertEquals(1, result.metadata.attempts)
        assertEquals(200, result.metadata.httpStatus)
        assertTrue(result.metadata.responsePresent)
        val body = JSONObject(connection.requestBody())
        assertEquals("glm-4.7", body.getString("model"))
        assertEquals("enabled", body.getJSONObject("thinking").getString("type"))
        assertFalse(body.getBoolean("do_sample"))
    }

    @Test
    fun slowSuccessfulResponseIsAcceptedWithoutRetry() {
        val connection = FakeConnection(URL(ENDPOINT), 200, success("遅いが成功"), responseDelayMs = 40)
        val result = corrector(ReasoningEffort.DEFAULT) { connection }.correctDetailed(request())

        assertEquals("遅いが成功", result.text)
        assertEquals(1, result.metadata.attempts)
        assertEquals(200, result.metadata.httpStatus)
    }

    @Test
    fun readTimeoutIsClassifiedWithoutPretendingThereWasAResponse() {
        val connection = FakeConnection(
            URL(ENDPOINT),
            200,
            success("unused"),
            responseFailure = SocketTimeoutException("Read timed out"),
        )

        val failure = expectFailure { corrector(ReasoningEffort.DEFAULT) { connection }.correctDetailed(request()) }
        assertEquals("network-timeout", failure.stage)
        assertEquals(1, failure.attempts)
        assertFalse(failure.responsePresent)
        assertEquals("SocketTimeoutException", failure.errorClass)
    }

    @Test
    fun unsupportedNonReasoningConvenienceRetriesPortableBodyOnce() {
        val first = FakeConnection(URL(ENDPOINT), 400, "{\"error\":\"unknown field do_sample\"}")
        val second = FakeConnection(URL(ENDPOINT), 200, success("再試行で成功"))
        val queue = ArrayDeque(listOf(first, second))
        val corrector = corrector(ReasoningEffort.DEFAULT) { queue.removeFirst() }

        val result = corrector.correctDetailed(request())

        assertEquals("再試行で成功", result.text)
        assertEquals(2, result.metadata.attempts)
        assertEquals(200, result.metadata.httpStatus)
        assertTrue(JSONObject(first.requestBody()).has("do_sample"))
        assertFalse(JSONObject(second.requestBody()).has("do_sample"))
    }

    @Test
    fun explicitUnsupportedThinkingNeverSilentlyStripsUserChoice() {
        val first = FakeConnection(URL(ENDPOINT), 400, "{\"error\":\"unsupported parameter thinking\"}")
        var opens = 0
        val failure = expectFailure {
            corrector(ReasoningEffort.HIGH) {
                opens += 1
                first
            }.correctDetailed(request())
        }

        assertEquals(1, opens)
        assertEquals("http-unsupported-reasoning", failure.stage)
        assertEquals(400, failure.httpStatus)
        assertTrue(failure.responsePresent)
    }

    @Test
    fun emptySuccessfulHttpBodyIsReportedAsEmptyResponse() {
        val connection = FakeConnection(URL(ENDPOINT), 200, "")
        val failure = expectFailure { corrector(ReasoningEffort.DEFAULT) { connection }.correctDetailed(request()) }

        assertEquals("empty-response", failure.stage)
        assertEquals(200, failure.httpStatus)
        assertFalse(failure.responsePresent)
    }

    @Test
    fun lateBodyAfterResponseDeadlineIsStillATimeoutNotASuccess() {
        val connection = FakeConnection(
            URL(ENDPOINT),
            200,
            success("期限後の応答"),
            inputDelayMs = 40,
            inputFailure = SocketTimeoutException("late response"),
        )
        val failure = expectFailure { corrector(ReasoningEffort.DEFAULT) { connection }.correctDetailed(request()) }

        assertEquals("network-timeout", failure.stage)
        assertFalse(failure.responsePresent)
    }

    private fun corrector(
        effort: ReasoningEffort,
        factory: (URL) -> HttpURLConnection,
    ) = OpenAiCompatibleCorrector(
        endpoint = ENDPOINT,
        model = "GLM-4.7",
        apiKey = "test-key-never-persisted",
        reasoningEffort = effort,
        connectionFactory = factory,
    )

    private fun request() = CorrectionRequest(
        rawTranscript = "今日は晴れ",
        alternatives = listOf("今日は晴れ"),
        surroundingContext = "",
        dictionaryTerms = emptyList(),
        preferences = CorrectionPreferences(
            addCommas = false,
            addPeriods = true,
            removeFillers = false,
        ),
    )

    private fun expectFailure(block: () -> Unit): CorrectionCallException {
        try {
            block()
            fail("CorrectionCallException expected")
        } catch (failure: CorrectionCallException) {
            return failure
        }
        error("unreachable")
    }

    private fun success(text: String): String =
        "{\"choices\":[{\"message\":{\"content\":${JSONObject.quote(text)}}}]}"

    private class FakeConnection(
        url: URL,
        private val status: Int,
        private val body: String,
        private val responseDelayMs: Long = 0,
        private val responseFailure: Throwable? = null,
        private val inputDelayMs: Long = 0,
        private val inputFailure: Throwable? = null,
    ) : HttpURLConnection(url) {
        private val request = ByteArrayOutputStream()

        override fun getOutputStream(): OutputStream = request

        override fun getResponseCode(): Int {
            if (responseDelayMs > 0) Thread.sleep(responseDelayMs)
            responseFailure?.let { throw it }
            return status
        }

        override fun getInputStream(): InputStream {
            if (inputDelayMs > 0) Thread.sleep(inputDelayMs)
            inputFailure?.let { throw it }
            return ByteArrayInputStream(body.toByteArray(Charsets.UTF_8))
        }

        override fun getErrorStream(): InputStream = ByteArrayInputStream(body.toByteArray(Charsets.UTF_8))

        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false
        override fun connect() = Unit

        fun requestBody(): String = request.toString(Charsets.UTF_8.name())
    }

    companion object {
        private const val ENDPOINT = "https://api.z.ai/api/coding/paas/v4/chat/completions"
    }
}
