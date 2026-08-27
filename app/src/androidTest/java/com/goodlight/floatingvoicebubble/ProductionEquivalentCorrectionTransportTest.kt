package com.goodlight.floatingvoicebubble

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.goodlight.floatingvoicebubble.correction.FinalizationEngine
import com.goodlight.floatingvoicebubble.correction.OpenAiCompatibleCorrector
import com.goodlight.floatingvoicebubble.dictionary.PersonalDictionary
import com.goodlight.floatingvoicebubble.model.FinalAsrModelStore
import com.goodlight.floatingvoicebubble.speech.RecognitionOutcome
import com.goodlight.floatingvoicebubble.trace.SessionTraceStore
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.Executors

/**
 * Exercises the same FinalizationEngine -> provider adapter -> post-processing/integrity path used
 * by dictation. These are deliberately separate from the short transport-only probes.
 */
@RunWith(AndroidJUnit4::class)
class ProductionEquivalentCorrectionTransportTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val worker = Executors.newCachedThreadPool()
    private val dictionary = PersonalDictionary(context)

    @After
    fun tearDown() {
        dictionary.close()
        worker.shutdownNow()
    }

    @Test
    fun longProductionPacketReachesProviderAndSmallSemanticRepairIsAccepted() {
        val raw = longRaw()
        val corrected = raw.replace("音声入力の取り合い", "音声入力の聞き取りAI")
        val connection = FakeConnection(URL(ENDPOINT), 200, success(corrected))
        val result = engine(connection).finalize(
            outcome = outcome(raw, listOf(corrected, raw.replace("取り合い", "聞き取り合い"))),
            surrounding = "同じ入力欄で直前から音声認識AIと聞き取り精度について話している。".repeat(16),
            settings = settings(),
            bypassCorrection = false,
        )

        assertTrue(result.correctionModelResponded)
        assertTrue(result.correctionModelChanged)
        assertEquals(corrected, result.finalText)
        assertEquals("accepted", result.correctionIntegrityResult)
        assertEquals(1, result.correctionAttempts)

        val body = JSONObject(connection.requestBody())
        val messages = body.getJSONArray("messages")
        val user = messages.getJSONObject(1).getString("content")
        assertTrue(user.contains("<RAW>"))
        assertTrue(user.contains("</RAW>"))
        assertTrue(user.contains(raw))
        assertTrue(user.contains("<N_BEST_ALTERNATIVES>"))
        assertTrue(user.contains("</N_BEST_ALTERNATIVES>"))
        assertTrue(user.contains("<SURROUNDING_CONTEXT_FOR_DISAMBIGUATION_ONLY>"))
        assertTrue(user.contains("</SURROUNDING_CONTEXT_FOR_DISAMBIGUATION_ONLY>"))
        assertTrue("production packet unexpectedly tiny", user.length > raw.length + 300)
    }

    @Test
    fun providerReadTimeoutFallsBackToRawWithStructuredStage() {
        val raw = longRaw()
        val connection = FakeConnection(
            URL(ENDPOINT),
            200,
            success(raw),
            responseFailure = SocketTimeoutException("Read timed out"),
        )
        val result = engine(connection).finalize(
            outcome = outcome(raw),
            surrounding = "",
            settings = settings(),
            bypassCorrection = false,
        )

        assertEquals(raw, result.finalText)
        assertFalse(result.correctionModelResponded)
        assertEquals("network-timeout", result.correctionFailureStage)
        assertEquals("SocketTimeoutException", result.correctionErrorClass)
        assertFalse(result.correctionResponsePresent)
        assertEquals("音声認識結果", result.fallbackSource)
        assertEquals(1, result.correctionAttempts)
    }

    @Test
    fun emptyProviderBodyCannotMasqueradeAsSuccessfulNoOp() {
        val raw = longRaw()
        val connection = FakeConnection(URL(ENDPOINT), 200, "")
        val result = engine(connection).finalize(
            outcome = outcome(raw),
            surrounding = "",
            settings = settings(),
            bypassCorrection = false,
        )

        assertEquals(raw, result.finalText)
        assertFalse(result.correctionModelResponded)
        assertEquals("empty-response", result.correctionFailureStage)
        assertEquals(200, result.correctionHttpStatus)
        assertFalse(result.correctionResponsePresent)
    }

    @Test
    fun responseArrivingAfterTransportDeadlineRemainsFailure() {
        val raw = longRaw()
        val connection = FakeConnection(
            URL(ENDPOINT),
            200,
            success(raw.replace("取り合い", "聞き取りAI")),
            inputFailure = SocketTimeoutException("late response"),
        )
        val result = engine(connection).finalize(
            outcome = outcome(raw),
            surrounding = "",
            settings = settings(),
            bypassCorrection = false,
        )

        assertEquals(raw, result.finalText)
        assertFalse(result.correctionModelResponded)
        assertEquals("network-timeout", result.correctionFailureStage)
        assertFalse(result.correctionResponsePresent)
    }

    private fun engine(connection: HttpURLConnection): FinalizationEngine {
        val corrector = OpenAiCompatibleCorrector(
            endpoint = ENDPOINT,
            model = "glm-4.7",
            apiKey = "instrumentation-only-key",
            reasoningEffort = ReasoningEffort.NONE,
            connectionFactory = { connection },
        )
        return FinalizationEngine(
            context = context,
            settingsStore = SettingsStore(context),
            dictionary = dictionary,
            traceStore = SessionTraceStore(context),
            finalAsrModelStore = FinalAsrModelStore(context),
            inferenceWorker = worker,
            correctorOverride = { corrector },
        )
    }

    private fun settings() = AppSettings(
        correctionMode = CorrectionMode.BYOK,
        finalAsrMode = FinalAsrMode.LIVE_RESULT,
        byokEndpoint = ENDPOINT,
        byokModel = "glm-4.7",
        reasoningEffort = ReasoningEffort.NONE,
        recognitionRepairMode = RecognitionRepairMode.STRONG,
        correctionAddCommas = false,
        correctionAddPeriods = false,
        correctionRemoveFillers = false,
        correctionLineBreakMode = LineBreakMode.NONE,
        keepSessionTraces = false,
    )

    private fun outcome(raw: String, alternatives: List<String> = emptyList()) = RecognitionOutcome(
        sessionId = "production-transport-${System.nanoTime()}",
        rawTranscript = raw,
        alternatives = alternatives,
        audioFile = null,
        startedAtMs = 1L,
        recognitionFinishedAtMs = 2L,
        recognizerKind = "production-equivalent-test",
    )

    private fun longRaw(): String = buildString {
        append("音声入力の取り合いがだいぶ聞き取りミスをした。")
        repeat(24) { index ->
            append("これは本番相当の長さを作るための第${index + 1}文で、話し方を勝手に変えず誤認識だけ直してほしい。")
        }
    }

    private fun success(text: String): String =
        "{\"choices\":[{\"message\":{\"content\":${JSONObject.quote(text)}}}]}"

    private class FakeConnection(
        url: URL,
        private val status: Int,
        private val body: String,
        private val responseFailure: Throwable? = null,
        private val inputFailure: Throwable? = null,
    ) : HttpURLConnection(url) {
        private val request = ByteArrayOutputStream()

        override fun getOutputStream(): OutputStream = request
        override fun getResponseCode(): Int {
            responseFailure?.let { throw it }
            return status
        }
        override fun getInputStream(): InputStream {
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
