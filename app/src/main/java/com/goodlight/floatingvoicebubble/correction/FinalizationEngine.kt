package com.goodlight.floatingvoicebubble.correction

import android.content.Context
import com.goodlight.floatingvoicebubble.AppSettings
import com.goodlight.floatingvoicebubble.CorrectionMode
import com.goodlight.floatingvoicebubble.FinalAsrMode
import com.goodlight.floatingvoicebubble.SettingsStore
import com.goodlight.floatingvoicebubble.dictionary.PersonalDictionary
import com.goodlight.floatingvoicebubble.model.FinalAsrModelStore
import com.goodlight.floatingvoicebubble.model.GemmaModelSource
import com.goodlight.floatingvoicebubble.model.GemmaModelStorage
import com.goodlight.floatingvoicebubble.speech.RecognitionOutcome
import com.goodlight.floatingvoicebubble.speech.SherpaFinalAsrEngine
import com.goodlight.floatingvoicebubble.trace.FinalizationTrace
import com.goodlight.floatingvoicebubble.trace.SessionTraceStore
import java.io.File
import java.net.URI
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
    val correctionModelResponded: Boolean = false,
    val correctionModelChanged: Boolean = false,
    val deterministicFormattingChanged: Boolean = false,
    val correctionLatencyMs: Long? = null,
    val correctionProvider: String? = null,
    val correctionModel: String? = null,
    val correctionReasoning: String? = null,
    val correctionReasoningWire: String? = null,
    val correctionAttempts: Int = 0,
    val correctionAttemptTimings: List<CorrectionAttemptTiming> = emptyList(),
    val correctionHttpStatus: Int? = null,
    val correctionFailureStage: String? = null,
    val correctionErrorClass: String? = null,
    val correctionResponsePresent: Boolean = false,
    val correctionEndpoint: String? = null,
    val correctionIntegrityResult: String? = null,
    val fallbackSource: String? = null,
)

class FinalizationEngine(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val dictionary: PersonalDictionary,
    private val traceStore: SessionTraceStore,
    private val finalAsrModelStore: FinalAsrModelStore,
    private val inferenceWorker: ExecutorService,
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
                correctionLatencyMs = null,
                correctionAttempts = 0,
                correctionAttemptTimings = emptyList(),
                correctionHttpStatus = null,
                correctionFailureStage = null,
                correctionErrorClass = null,
                correctionResponsePresent = false,
                correctionIntegrityResult = "bypassed",
                route = CorrectionRoute(null, null, null, null, null),
                fallbackSource = null,
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
                correctionIntegrityResult = "bypassed",
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
        val route = correctionRoute(settings)

        var correctionError: String? = null
        var correctionAttempts = 0
        var correctionAttemptTimings = emptyList<CorrectionAttemptTiming>()
        var correctionHttpStatus: Int? = null
        var correctionFailureStage: String? = null
        var correctionErrorClass: String? = null
        var correctionResponsePresent = false
        val corrector = runCatching { selectCorrector(settings) }
            .onFailure { failure ->
                correctionError = failure.message ?: failure.javaClass.simpleName
                correctionFailureStage = "select-backend"
                correctionErrorClass = failure.javaClass.simpleName
            }
            .getOrNull()
        val attempted = corrector != null
        var modelOutput: String? = null
        var correctionLatencyMs: Long? = null
        if (corrector != null) {
            val started = System.nanoTime()
            val cloudCorrection = route.provider != null && route.provider != "none" && route.provider != "on-device"
            val detailed = runCatching {
                if (cloudCorrection) {
                    // Cloud adapters own an idle-between-bytes timeout. Do not wrap them in a short
                    // total wall-clock Future timeout: active SSE/reasoning traffic is valid progress.
                    corrector.correctDetailed(request)
                } else {
                    bounded(
                        CorrectionTimeoutPolicy.localCorrectionTimeoutMs(settings.reasoningEffort),
                        "端末内補正モデル",
                    ) { corrector.correctDetailed(request) }
                }
            }.onFailure { failure ->
                correctionError = failure.message ?: failure.javaClass.simpleName
                val structured = failure as? CorrectionCallException
                correctionAttempts = structured?.attempts ?: 1
                correctionAttemptTimings = structured?.attemptTimings.orEmpty()
                correctionHttpStatus = structured?.httpStatus
                correctionFailureStage = structured?.stage ?: if (failure is TimeoutException) "model-timeout" else "model-call"
                correctionErrorClass = structured?.errorClass ?: failure.javaClass.simpleName
                correctionResponsePresent = structured?.responsePresent ?: false
            }.getOrNull()
            if (detailed != null) {
                modelOutput = detailed.text
                correctionAttempts = detailed.metadata.attempts
                correctionAttemptTimings = detailed.metadata.attemptTimings
                correctionHttpStatus = detailed.metadata.httpStatus
                correctionResponsePresent = detailed.metadata.responsePresent
            }
            correctionLatencyMs = (System.nanoTime() - started) / 1_000_000L
        } else if (settings.correctionMode != CorrectionMode.NONE && correctionError == null) {
            correctionError = "補正バックエンドが利用できません"
            correctionFailureStage = "select-backend"
            correctionErrorClass = "BackendUnavailable"
        }

        val rawModelOutput = modelOutput
        val modelResponded = rawModelOutput != null
        val modelSucceeded = correctionError == null && modelResponded
        val semanticCandidate = if (modelSucceeded) {
            CorrectionGuard.sanitize(rawModelOutput.orEmpty())
        } else {
            correctionInput
        }
        val modelChanged = modelSucceeded && semanticCandidate != correctionInput.trim()
        val allowDeterministicFormatting = modelSucceeded && settings.correctionMode != CorrectionMode.NONE
        val candidate = if (allowDeterministicFormatting) {
            CorrectionPostProcessor.apply(correctionInput, semanticCandidate, preferences)
        } else {
            // A failed/timeout model must never leave behind punctuation, filler deletion or an
            // arbitrary line break. The fallback contract is exact RAW recognition text.
            correctionInput
        }
        val deterministicFormattingChanged = modelSucceeded && candidate != semanticCandidate
        val decision = CorrectionGuard.choose(correctionInput, candidate, preferences)
        val finalText = decision.text
        val changed = finalText != correctionInput
        if (!decision.accepted && correctionFailureStage == null) {
            correctionFailureStage = "integrity-check"
            correctionErrorClass = "OutputIntegrityRejected"
        }
        val integrityResult = if (decision.accepted) "accepted" else "rejected:${decision.reason ?: "unknown"}"
        val fallbackSource = when {
            correctionError != null -> "音声認識結果"
            !decision.accepted -> "音声認識結果"
            else -> null
        }

        relevant.filter { finalText.contains(it.term) }.map { it.term }.takeIf { it.isNotEmpty() }?.let(dictionary::markUsed)
        saveTrace(
            outcome = outcome,
            correctionInput = correctionInput,
            finalText = finalText,
            finalAsrId = finalAsrId,
            finalAsrLatencyMs = finalAsrLatencyMs,
            finalAsrRtf = finalAsrRtf,
            finalAsrError = finalAsrError,
            correctorId = corrector?.id ?: "none",
            modelOutput = rawModelOutput,
            accepted = decision.accepted,
            distance = decision.normalizedDistance,
            correctionError = correctionError,
            decisionReason = decision.reason,
            attempted = attempted,
            changed = changed,
            bypassed = false,
            modelResponded = modelResponded,
            modelChanged = modelChanged,
            deterministicFormattingChanged = deterministicFormattingChanged,
            correctionLatencyMs = correctionLatencyMs,
            correctionAttempts = correctionAttempts,
            correctionAttemptTimings = correctionAttemptTimings,
            correctionHttpStatus = correctionHttpStatus,
            correctionFailureStage = correctionFailureStage,
            correctionErrorClass = correctionErrorClass,
            correctionResponsePresent = correctionResponsePresent,
            correctionIntegrityResult = integrityResult,
            route = route,
            fallbackSource = fallbackSource,
            settings = settings,
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
            correctionProvider = route.provider,
            correctionModel = route.model,
            correctionReasoning = route.reasoning,
            correctionReasoningWire = route.reasoningWire,
            correctionAttempts = correctionAttempts,
            correctionAttemptTimings = correctionAttemptTimings,
            correctionHttpStatus = correctionHttpStatus,
            correctionFailureStage = correctionFailureStage,
            correctionErrorClass = correctionErrorClass,
            correctionResponsePresent = correctionResponsePresent,
            correctionEndpoint = route.endpoint,
            correctionIntegrityResult = integrityResult,
            fallbackSource = fallbackSource,
        )
    }

    private fun correctionRoute(settings: AppSettings): CorrectionRoute {
        val gemmaAvailable = GemmaModelSource.isAvailable(context, settings.gemmaModelPath)
        return when (CorrectionBackendResolver.resolve(settings, gemmaAvailable)) {
            CorrectionBackend.NONE -> CorrectionRoute("none", null, null, null, null)
            CorrectionBackend.GEMMA -> {
                val file = settings.gemmaModelPath.takeUnless(GemmaModelSource::isExternal)?.let(::File)
                val source = when {
                    GemmaModelSource.isExternal(settings.gemmaModelPath) -> "legacy-content-uri-unrunnable"
                    file != null && GemmaModelStorage.isInSharedDirectory(context, file) -> "shared-real-file-no-copy"
                    else -> "real-file-path"
                }
                CorrectionRoute(
                    provider = "on-device",
                    model = if (settings.gemmaVariant.name == "UNKNOWN") "Gemma" else "Gemma ${settings.gemmaVariant.name}",
                    reasoning = null,
                    reasoningWire = null,
                    endpoint = source,
                )
            }
            CorrectionBackend.BYOK -> CorrectionRoute(
                provider = CloudCorrectorFactory.protocolFor(settings.byokEndpoint).name.lowercase(),
                model = settings.byokModel,
                reasoning = ReasoningCapabilities.label(settings.byokEndpoint, settings.byokModel, settings.reasoningEffort),
                reasoningWire = ReasoningWireDescriptor.describe(settings.byokEndpoint, settings.byokModel, settings.reasoningEffort),
                endpoint = redactedEndpoint(settings.byokEndpoint),
            )
        }
    }

    private fun selectCorrector(settings: AppSettings): TextCorrector? {
        correctorOverride?.let { return it(settings) }
        val gemmaAvailable = GemmaModelSource.isAvailable(context, settings.gemmaModelPath)
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
        correctionLatencyMs: Long?,
        correctionAttempts: Int,
        correctionAttemptTimings: List<CorrectionAttemptTiming>,
        correctionHttpStatus: Int?,
        correctionFailureStage: String?,
        correctionErrorClass: String?,
        correctionResponsePresent: Boolean,
        correctionIntegrityResult: String?,
        route: CorrectionRoute,
        fallbackSource: String?,
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
                correctionProvider = route.provider,
                correctionModel = route.model,
                correctionReasoning = route.reasoning,
                correctionReasoningWire = route.reasoningWire,
                correctionLatencyMs = correctionLatencyMs,
                correctionAttempts = correctionAttempts,
                correctionAttemptTimings = correctionAttemptTimings,
                correctionHttpStatus = correctionHttpStatus,
                correctionFailureStage = correctionFailureStage,
                correctionErrorClass = correctionErrorClass,
                correctionResponsePresent = correctionResponsePresent,
                correctionEndpoint = route.endpoint,
                correctionIntegrityResult = correctionIntegrityResult,
                fallbackSource = fallbackSource,
                finalAsrId = finalAsrId,
                finalAsrLatencyMs = finalAsrLatencyMs,
                finalAsrRtf = finalAsrRtf,
                finalAsrError = finalAsrError,
            ),
            enabled = settings.keepSessionTraces,
        )
    }

    private fun redactedEndpoint(endpoint: String): String = runCatching {
        val uri = URI(endpoint.trim())
        buildString {
            append(uri.scheme ?: "https")
            append("://")
            append(uri.host.orEmpty())
            if (uri.port >= 0) append(":${uri.port}")
            append(uri.path.orEmpty())
        }
    }.getOrElse { endpoint.substringBefore('?').substringBefore('#').take(220) }

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

    private data class CorrectionRoute(
        val provider: String?,
        val model: String?,
        val reasoning: String?,
        val reasoningWire: String?,
        val endpoint: String?,
    )

    companion object {
        private const val FINAL_ASR_TIMEOUT_MS = 30_000L
    }
}
