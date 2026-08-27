package com.goodlight.floatingvoicebubble

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.goodlight.floatingvoicebubble.speech.RecognitionOutcome
import com.goodlight.floatingvoicebubble.trace.FinalizationTrace
import com.goodlight.floatingvoicebubble.trace.SessionTraceStore
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionTraceLineBreakAttributionTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun traceSeparatesModelInsertedBreakFromAppInsertedBreak() {
        val store = SessionTraceStore(context)
        val raw = "最初の話題。次の話題。"

        val modelSession = "linebreak-model-${System.nanoTime()}"
        val modelTrace = FinalizationTrace(
            outcome = outcome(modelSession, raw),
            finalText = "最初の話題。\n次の話題。",
            correctorId = "test-model",
            correctionAccepted = true,
            correctionDistance = 0.0,
            correctionInputText = raw,
            modelOutputText = "最初の話題。\n次の話題。",
            correctionAttempted = true,
            correctionModelResponded = true,
            correctionModelChanged = true,
            deterministicFormattingChanged = false,
        )
        store.save(modelTrace, enabled = true)
        val modelJson = JSONObject(store.recentSessionMetadata(30).first { it.name == "$modelSession.json" }.readText())
        assertTrue(modelJson.getBoolean("modelLineBreakChanged"))
        assertFalse(modelJson.getBoolean("appLineBreakChanged"))

        val appSession = "linebreak-app-${System.nanoTime()}"
        val appTrace = FinalizationTrace(
            outcome = outcome(appSession, raw),
            finalText = "最初の話題。\n次の話題。",
            correctorId = "test-model",
            correctionAccepted = true,
            correctionDistance = 0.0,
            correctionInputText = raw,
            modelOutputText = raw,
            correctionAttempted = true,
            correctionModelResponded = true,
            correctionModelChanged = false,
            deterministicFormattingChanged = true,
        )
        store.save(appTrace, enabled = true)
        val appJson = JSONObject(store.recentSessionMetadata(30).first { it.name == "$appSession.json" }.readText())
        assertFalse(appJson.getBoolean("modelLineBreakChanged"))
        assertTrue(appJson.getBoolean("appLineBreakChanged"))

        store.recentSessionMetadata(30)
            .filter { it.name == "$modelSession.json" || it.name == "$appSession.json" }
            .forEach { it.delete() }
    }

    private fun outcome(sessionId: String, raw: String) = RecognitionOutcome(
        sessionId = sessionId,
        rawTranscript = raw,
        alternatives = emptyList(),
        audioFile = null,
        startedAtMs = 1L,
        recognitionFinishedAtMs = 2L,
        recognizerKind = "test",
    )
}
