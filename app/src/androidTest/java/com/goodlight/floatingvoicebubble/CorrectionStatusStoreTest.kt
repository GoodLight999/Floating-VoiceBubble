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
        )
        try {
            store.saveFailure(expected)
            val actual = store.loadFailure() ?: error("last correction failure was not persisted")
            assertEquals(expected, actual)
            assertTrue(actual.responsePresent)
            assertFalse(actual.modelChanged)
        } finally {
            store.clearFailure()
        }
    }
}
