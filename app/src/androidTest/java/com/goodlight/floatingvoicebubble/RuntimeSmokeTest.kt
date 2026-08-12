package com.goodlight.floatingvoicebubble

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.goodlight.floatingvoicebubble.dictionary.DictionaryTerm
import com.goodlight.floatingvoicebubble.dictionary.PersonalDictionary
import com.goodlight.floatingvoicebubble.speech.RecognitionOutcome
import com.goodlight.floatingvoicebubble.trace.FinalizationTrace
import com.goodlight.floatingvoicebubble.trace.SessionTraceStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RuntimeSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun launcherActivityStarts() {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: error("Launcher intent missing")
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = instrumentation.startActivitySync(launchIntent)
        try {
            assertTrue(activity is MainActivity)
            assertFalse(activity.isFinishing)
        } finally {
            activity.finish()
        }
    }

    @Test
    fun keystoreBackedByokSecretRoundTrips() {
        val store = SettingsStore(context)
        val secret = "diagnostic-${System.nanoTime()}"
        store.setApiKey(secret)
        try {
            assertEquals(secret, SettingsStore(context).apiKey())
        } finally {
            store.setApiKey("")
        }
    }

    @Test
    fun personalDictionaryPersistsAndRetrievesRelevantTerm() {
        val token = "診断固有名詞${System.nanoTime()}"
        PersonalDictionary(context).use { dictionary ->
            val before = dictionary.count()
            dictionary.upsert(DictionaryTerm(term = token, reading = "しんだんこゆうめいし", aliases = listOf("診断別名"), weight = 900))
            assertTrue(dictionary.count() >= before + 1L)
            assertTrue(dictionary.relevantTerms("今日は${token}について話す").any { it.term == token })
            assertTrue(dictionary.relevantTerms("今日は診断別名について話す").any { it.term == token })
        }
    }

    @Test
    fun traceStoreWritesRedactedSessionMetadataAndAudio() {
        val store = SessionTraceStore(context)
        val id = "instrumented-${System.nanoTime()}"
        val wav = File(store.audioDir, "$id.wav")
        wav.writeBytes(ByteArray(128) { index -> (index and 0xff).toByte() })
        val started = System.currentTimeMillis() - 250L
        store.save(
            FinalizationTrace(
                outcome = RecognitionOutcome(
                    sessionId = id,
                    rawTranscript = "テスト",
                    alternatives = listOf("テスト", "てすと"),
                    audioFile = wav,
                    startedAtMs = started,
                    recognitionFinishedAtMs = started + 120L,
                    recognizerKind = "instrumentation-fake",
                ),
                finalText = "テスト。",
                correctorId = "instrumentation-fake",
                correctionAccepted = true,
                correctionDistance = 0.1,
            ),
            enabled = true,
        )
        val json = File(store.audioDir, "$id.json")
        try {
            assertTrue(wav.isFile)
            assertTrue(json.isFile)
            val text = json.readText(Charsets.UTF_8)
            assertTrue(text.contains("\"sessionId\": \"$id\"") || text.contains("\"sessionId\":\"$id\""))
            assertTrue(text.contains("instrumentation-fake"))
        } finally {
            json.delete()
            wav.delete()
        }
    }
}
