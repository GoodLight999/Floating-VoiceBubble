package com.goodlight.floatingvoicebubble

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CorrectionStatusStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun structuredFailureRoundTripsWithoutTranscriptOrSecrets() {
        val store = CorrectionStatusStore(context)
        store.clearFailure()
        val expected = LastCorrectionFailure(
            occurredAtMs = 123456789L,
            provider = "openai_compatible",
            model = "glm-4.7",
            reasoning = "思考ON",
            latencyMs = 12_345L,
            reason = "HTTP 503 upstream unavailable",
            fallback = "音声認識結果",
            attempts = 2,
            httpStatus = 503,
            failureStage = "http",
            errorClass = "HttpResponseError",
            responsePresent = true,
            modelChanged = false,
            integrityResult = "accepted",
            endpoint = "https://api.z.ai/api/coding/paas/v4/chat/completions",
            reasoningWire = "thinking.type=enabled",
            attemptTimingSummary =
                "attempt=1 connect=18ms write=2ms headers=1110ms body=4ms total=1136ms | " +
                    "attempt=2 connect=17ms write=1ms headers=901ms body=3ms total=925ms",
        )
        try {
            store.saveFailure(expected)
            val actual = store.loadFailure() ?: error("last correction failure was not persisted")
            assertEquals(expected, actual)
            assertTrue(actual.responsePresent)
            assertFalse(actual.modelChanged)
            assertTrue(actual.reasoningWire.contains("thinking.type=enabled"))
            assertTrue(actual.attemptTimingSummary.contains("attempt=1"))
            assertTrue(actual.attemptTimingSummary.contains("headers=1110ms"))
            assertFalse(actual.attemptTimingSummary.contains("instrumentation-only-key"))
            assertFalse(actual.attemptTimingSummary.contains("音声入力の取り合い"))
        } finally {
            store.clearFailure()
        }
    }
}
