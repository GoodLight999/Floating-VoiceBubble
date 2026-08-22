package com.goodlight.floatingvoicebubble.correction

import android.content.Context
import com.goodlight.floatingvoicebubble.AppSettings
import com.goodlight.floatingvoicebubble.CorrectionMode
import com.goodlight.floatingvoicebubble.FinalAsrMode
import com.goodlight.floatingvoicebubble.SettingsStore
import com.goodlight.floatingvoicebubble.dictionary.PersonalDictionary
import com.goodlight.floatingvoicebubble.model.FinalAsrModelStore
import com.goodlight.floatingvoicebubble.speech.RecognitionOutcome
import com.goodlight.floatingvoicebubble.speech.SherpaFinalAsrEngine
import com.goodlight.floatingvoicebubble.trace.FinalizationTrace
import com.goodlight.floatingvoicebubble.trace.SessionTraceStore
import java.io.File
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

data class FinalizationResult(
    val finalText: String,
    val finalAsrError: String?,
    val correctionError: String?,
    val correctionAccepted: Boolean,
    val correctionDecisionReason: String?,
    val correctionAttempted: Boolean,
    val correctionChanged: Boolean,
    val correctionBypassed: Boolean,
    val modelOutput: String?,
    /** The selected LM/Gemma actually returned a non-empty final text response. */
    val correctionModelResponded: Boolean = false,
    /** The model response itself differed from the transcript before deterministic formatting. */
    val correctionModelChanged: Boolean = false,
    /** App-side punctuation/filler/paragraph rules changed the model response or RAW fallback. */
    val deterministicFormattingChanged: Boolean = false,
    /** Wall-clock time spent in the single correction-model attempt. */
    val correctionLatencyMs: Long? = null,
)

class FinalizationEngine(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val dictionary: PersonalDictionary,
    private val traceStore: SessionTraceStore,
    private val finalAsrModelStore: FinalAsrModelStore,
    private val inferenceWorker: ExecutorService,
    /** Test seam for exercising the real finalization pipeline without external credentials. */
    private val correctorOverride: ((AppSettings) -> TextCorrector?)? = null,
) {
    fun finalize(
        outcome: RecognitionOutcome,
        surrounding: String,
        settings: AppSettings,
        bypassCorrection: Boolean,
    ): FinalizationResult {
        if (bypassCorrection) {
            val text = outcome.rawTranscript
            saveTrace(
                outcome = outcome,
                correctionInput = text,
                finalText = text,
                finalAsrId = "bypassed",
                finalAsrLatencyMs = null,
                finalAsrRtf = null,
                finalAsrError = null,
                correctorId = "bypassed",
                modelOutput = null,
                accepted = true,
                distance = 0.0,
                correctionError = null,
                decisionReason = null,
                attempted = false,
                changed = false,
                bypassed = true,
                modelResponded = false,
                modelChanged = false,
                deterministicFormattingChanged = false,
                settings = settings,
            )
            return FinalizationResult(
                finalText = text,
                finalAsrError = null,
                correctionError = null,
                correctionAccepted = true,
                correctionDecisionReason = null,
                correctionAttempted = false,
                correctionChanged = false,
                correctionBypassed = true,
                modelOutput = null,
            )
        }

        var finalAsrError: String? = null
        var finalAsrId = "live-result"
        var finalAsrLatencyMs: Long? = null
        var finalAsrRtf: Double? = null
        val correctionInput = when (settings.finalAsrMode) {
            FinalAsrMode.LIVE_RESULT -> outcome.rawTranscript
            FinalAsrMode.REAZON_SPEECH -> {
                val model = finalAsrModelStore.resolve(settings.finalAsrModelId)
                val audio = outcome.audioFile
                if (model == null || audio == null) {
                    finalAsrError = if (model == null) "ReazonSpeech model is missing" else "recorded WAV is missing"
                    outcome.rawTranscript
                } else {
                    runCatching { bounded(FINAL_ASR_TIMEOUT_MS, "最終音声認識") { SherpaFinalAsrEngine.decode(model, audio) } }
                        .onSuccess { decoded ->
                            finalAsrId = decoded.engineId
                            finalAsrLatencyMs = decoded.elapsedMs
                            finalAsrRtf = decoded.realTimeFactor
                        }
                        .onFailure { finalAsrError = it.message ?: it.javaClass.simpleName }
                        .getOrNull()?.text ?: outcome.rawTranscript
                }
            }
        }

        val preferences = CorrectionPreferences(
            addCommas = settings.correctionAddCommas,
            addPeriods = settings.correctionAddPeriods,
            removeFillers = settings.correctionRemoveFillers,
            polite = settings.correctionPolite,
            businessPolite = settings.correctionBusinessPolite,
            lineBreakMode = settings.correctionLineBreakMode,
            recognitionRepairMode = settings.recognitionRepairMode,
        )
        val alternatives = buildList { add(correctionInput); addAll(outcome.alternatives) }
            .map(String::trim).filter(String::isNotEmpty).distinct()
        val relevant = dictionary.relevantTerms(correctionInput)
        val request = CorrectionRequest(correctionInput, alternatives, surrounding, relevant, preferences)

        var correctionError: String? = null
        val corrector = runCatching { selectCorrector(settings) }
            .onFailure { correctionError = it.message ?: it.javaClass.simpleName }
            .getOrNull()
        val attempted = corrector != null
        var modelOutput: String? = null
        var correctionLatencyMs: Long? = null
        if (corrector != null) {
            val started = System.nanoTime()
            modelOutput = runCatching {
                bounded(
                    CorrectionTimeoutPolicy.correctionTimeoutMs(settings.reasoningEffort),
                    "補正モデル",
                ) { corrector.correct(request) }
            }.onFailure { correctionError = it.message ?: it.javaClass.simpleName }.getOrNull()
            correctionLatencyMs = (System.nanoTime() - started) / 1_000_000L
        } else if (settings.correctionMode != CorrectionMode.NONE && correctionError == null) {
            correctionError = "補正バックエンドが利用できません"
        }

        val rawModelOutput = modelOutput
        val modelResponded = rawModelOutput != null
        val semanticCandidate = rawModelOutput?.let(CorrectionGuard::sanitize) ?: correctionInput
        val modelChanged = modelResponded && semanticCandidate != correctionInput.trim()
        val allowDeterministicFormatting = settings.correctionMode != CorrectionMode.NONE
        val candidate = if (allowDeterministicFormatting) {
            CorrectionPostProcessor.apply(correctionInput, semanticCandidate, preferences)
        } else {
            correctionInput
        }
        val deterministicFormattingChanged = candidate != semanticCandidate
        val decision = CorrectionGuard.choose(correctionInput, candidate, preferences)
        val finalText = decision.text
        val changed = finalText != correctionInput

        relevant.filter { finalText.contains(it.term) }.map { it.term }.takeIf { it.isNotEmpty() }?.let(dictionary::markUsed)
        saveTrace(
            outcome, correctionInput, finalText, finalAsrId, finalAsrLatencyMs, finalAsrRtf, finalAsrError,
            corrector?.id ?: "none", rawModelOutput, decision.accepted, decision.normalizedDistance,
            correctionError, decision.reason, attempted, changed, false,
            modelResponded, modelChanged, deterministicFormattingChanged, settings,
        )
        return FinalizationResult(
            finalText = finalText,
            finalAsrError = finalAsrError,
            correctionError = correctionError,
            correctionAccepted = decision.accepted,
            correctionDecisionReason = decision.reason,
            correctionAttempted = attempted,
            correctionChanged = changed,
            correctionBypassed = false,
            modelOutput = rawModelOutput,
            correctionModelResponded = modelResponded,
            correctionModelChanged = modelChanged,
            deterministicFormattingChanged = deterministicFormattingChanged,
            correctionLatencyMs = correctionLatencyMs,
        )
    }

    private fun selectCorrector(settings: AppSettings): TextCorrector? {
        correctorOverride?.let { return it(settings) }
        val gemmaAvailable = File(settings.gemmaModelPath).isFile
        return when (CorrectionBackendResolver.resolve(settings, gemmaAvailable)) {
            CorrectionBackend.NONE -> null
            CorrectionBackend.BYOK -> CloudCorrectorFactory.create(
                settings.byokEndpoint, settings.byokModel, settingsStore.apiKey(), settings.reasoningEffort,
            )
            CorrectionBackend.GEMMA -> GemmaCorrector(context, settings.gemmaModelPath, settings.gemmaBackend)
        }
    }

    private fun saveTrace(
        outcome: RecognitionOutcome,
        correctionInput: String,
        finalText: String,
        finalAsrId: String,
        finalAsrLatencyMs: Long?,
        finalAsrRtf: Double?,
        finalAsrError: String?,
        correctorId: String,
        modelOutput: String?,
        accepted: Boolean,
        distance: Double,
        correctionError: String?,
        decisionReason: String?,
        attempted: Boolean,
        changed: Boolean,
        bypassed: Boolean,
        modelResponded: Boolean,
        modelChanged: Boolean,
        deterministicFormattingChanged: Boolean,
        settings: AppSettings,
    ) {
        traceStore.save(
            FinalizationTrace(
                outcome = outcome,
                finalText = finalText,
                correctorId = correctorId,
                correctionAccepted = accepted,
                correctionDistance = distance,
                correctionError = correctionError ?: decisionReason,
                correctionInputText = correctionInput,
                modelOutputText = modelOutput,
                correctionAttempted = attempted,
                correctionChanged = changed,
                correctionBypassed = bypassed,
                correctionDecisionReason = decisionReason,
                correctionModelResponded = modelResponded,
                correctionModelChanged = modelChanged,
                deterministicFormattingChanged = deterministicFormattingChanged,
                finalAsrId = finalAsrId,
                finalAsrLatencyMs = finalAsrLatencyMs,
                finalAsrRtf = finalAsrRtf,
                finalAsrError = finalAsrError,
            ),
            enabled = settings.keepSessionTraces,
        )
    }

    private fun <T> bounded(timeoutMs: Long, label: String, block: () -> T): T {
        val future = inferenceWorker.submit<T> { block() }
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (timeout: TimeoutException) {
            future.cancel(true)
            throw TimeoutException("$label が${timeoutMs / 1000}秒以内に応答しませんでした")
        } catch (execution: ExecutionException) {
            throw (execution.cause ?: execution)
        } catch (interrupted: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            throw interrupted
        }
    }

    companion object {
        private const val FINAL_ASR_TIMEOUT_MS = 30_000L
    }
}
