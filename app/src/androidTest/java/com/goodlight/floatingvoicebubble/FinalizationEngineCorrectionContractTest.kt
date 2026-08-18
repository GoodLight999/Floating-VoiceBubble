package com.goodlight.floatingvoicebubble

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.goodlight.floatingvoicebubble.correction.CorrectionRequest
import com.goodlight.floatingvoicebubble.correction.FinalizationEngine
import com.goodlight.floatingvoicebubble.correction.TextCorrector
import com.goodlight.floatingvoicebubble.dictionary.PersonalDictionary
import com.goodlight.floatingvoicebubble.model.FinalAsrModelStore
import com.goodlight.floatingvoicebubble.speech.RecognitionOutcome
import com.goodlight.floatingvoicebubble.trace.SessionTraceStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Executors

@RunWith(AndroidJUnit4::class)
class FinalizationEngineCorrectionContractTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val executor = Executors.newCachedThreadPool()
    private val dictionary = PersonalDictionary(context)

    @After
    fun tearDown() {
        dictionary.close()
        executor.shutdownNow()
    }

    @Test
    fun realPipelinePassesAlternativesAndContextAndRecordsSemanticModelChange() {
        val corrector = ScriptedCorrector(
            listOf("音声入力の聞き取りAIがだいぶ聞き取りミスをした"),
        )
        val engine = engine(corrector)
        val outcome = outcome(
            raw = "音声入力の取り合いがだいぶ聞き取りミスをした",
            alternatives = listOf("音声入力の聞き取りAIがだいぶ聞き取りミスをした"),
        )
        val surrounding = "直前から音声認識AIと聞き取りAIの誤認識について話している"

        val result = engine.finalize(
            outcome = outcome,
            surrounding = surrounding,
            settings = baseSettings(RecognitionRepairMode.NORMAL),
            bypassCorrection = false,
        )

        assertEquals(1, corrector.requests.size)
        val request = corrector.requests.single()
        assertEquals(surrounding, request.surroundingContext)
        assertTrue(request.alternatives.any { it.contains("聞き取りAI") })
        assertTrue(result.correctionModelResponded)
        assertTrue(result.correctionModelChanged)
        assertFalse(result.deterministicFormattingChanged)
        assertTrue(result.finalText.contains("聞き取りAI"))
    }

    @Test
    fun rawEchoPlusPeriodIsReportedAsAppFormattingNotLmCorrection() {
        val raw = "今日は音声入力を試している"
        val corrector = ScriptedCorrector(listOf(raw))
        val engine = engine(corrector)

        val result = engine.finalize(
            outcome = outcome(raw),
            surrounding = "",
            settings = baseSettings(RecognitionRepairMode.NORMAL).copy(correctionAddPeriods = true),
            bypassCorrection = false,
        )

        assertTrue(result.correctionModelResponded)
        assertFalse(result.correctionModelChanged)
        assertTrue(result.deterministicFormattingChanged)
        assertTrue(result.correctionChanged)
        assertEquals("$raw。", result.finalText)
    }

    @Test
    fun modelFailureCannotMasqueradeAsSuccessfulLmCorrection() {
        val corrector = ScriptedCorrector(listOf(IllegalStateException("provider exploded")))
        val engine = engine(corrector)
        val raw = "今日は音声入力を試している"

        val result = engine.finalize(
            outcome = outcome(raw),
            surrounding = "",
            settings = baseSettings(RecognitionRepairMode.NORMAL),
            bypassCorrection = false,
        )

        assertTrue(result.correctionAttempted)
        assertFalse(result.correctionModelResponded)
        assertFalse(result.correctionModelChanged)
        assertFalse(result.deterministicFormattingChanged)
        assertNotNull(result.correctionError)
        assertEquals(raw, result.finalText)
    }

    @Test
    fun strongModeRetriesRawEchoAndCanApplySecondSemanticRepair() {
        val raw = "音声入力の取り合いがだいぶ聞き取りミスをした"
        val corrected = "音声入力の聞き取りAIがだいぶ聞き取りミスをした"
        val corrector = ScriptedCorrector(listOf(raw, corrected))
        val engine = engine(corrector)

        val result = engine.finalize(
            outcome = outcome(raw, listOf(corrected)),
            surrounding = "音声認識AIについて話している",
            settings = baseSettings(RecognitionRepairMode.STRONG),
            bypassCorrection = false,
        )

        assertEquals(2, corrector.requests.size)
        assertFalse(corrector.requests.first().forceCorrection)
        assertTrue(corrector.requests.last().forceCorrection)
        assertTrue(result.correctionModelResponded)
        assertTrue(result.correctionModelChanged)
        assertTrue(result.finalText.contains("聞き取りAI"))
    }

    private fun engine(corrector: TextCorrector) = FinalizationEngine(
        context = context,
        settingsStore = SettingsStore(context),
        dictionary = dictionary,
        traceStore = SessionTraceStore(context),
        finalAsrModelStore = FinalAsrModelStore(context),
        inferenceWorker = executor,
        correctorOverride = { corrector },
    )

    private fun baseSettings(repair: RecognitionRepairMode) = AppSettings(
        correctionMode = CorrectionMode.BYOK,
        finalAsrMode = FinalAsrMode.LIVE_RESULT,
        correctionAddCommas = false,
        correctionAddPeriods = false,
        correctionRemoveFillers = false,
        correctionLineBreakMode = LineBreakMode.NONE,
        recognitionRepairMode = repair,
        keepSessionTraces = false,
    )

    private fun outcome(raw: String, alternatives: List<String> = emptyList()) = RecognitionOutcome(
        sessionId = "contract-${System.nanoTime()}",
        rawTranscript = raw,
        alternatives = alternatives,
        audioFile = null,
        startedAtMs = 1L,
        recognitionFinishedAtMs = 2L,
        recognizerKind = "test",
    )

    private class ScriptedCorrector(private val script: List<Any>) : TextCorrector {
        override val id: String = "scripted-test"
        val requests = mutableListOf<CorrectionRequest>()
        private var index = 0

        override fun correct(request: CorrectionRequest): String {
            requests += request
            val item = script.getOrElse(index++) { script.last() }
            if (item is Throwable) throw item
            return item as String
        }
    }
}
