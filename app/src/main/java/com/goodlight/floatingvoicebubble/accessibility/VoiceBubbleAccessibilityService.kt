package com.goodlight.floatingvoicebubble.accessibility

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.InputMethod
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.EditorInfo
import com.goodlight.floatingvoicebubble.AppSettings
import com.goodlight.floatingvoicebubble.CorrectionMode
import com.goodlight.floatingvoicebubble.FinalAsrMode
import com.goodlight.floatingvoicebubble.RecognitionMode
import com.goodlight.floatingvoicebubble.SettingsStore
import com.goodlight.floatingvoicebubble.correction.CloudCorrectorFactory
import com.goodlight.floatingvoicebubble.correction.CorrectionBackend
import com.goodlight.floatingvoicebubble.correction.CorrectionBackendResolver
import com.goodlight.floatingvoicebubble.correction.CorrectionGuard
import com.goodlight.floatingvoicebubble.correction.CorrectionRequest
import com.goodlight.floatingvoicebubble.correction.GemmaCorrector
import com.goodlight.floatingvoicebubble.correction.TextCorrector
import com.goodlight.floatingvoicebubble.dictionary.PersonalDictionary
import com.goodlight.floatingvoicebubble.model.AsrModelStore
import com.goodlight.floatingvoicebubble.model.FinalAsrModelStore
import com.goodlight.floatingvoicebubble.overlay.FloatingBubbleController
import com.goodlight.floatingvoicebubble.speech.RecognitionOutcome
import com.goodlight.floatingvoicebubble.speech.SherpaFinalAsrEngine
import com.goodlight.floatingvoicebubble.speech.SherpaStreamingEngine
import com.goodlight.floatingvoicebubble.speech.SpeechRecognitionSession
import com.goodlight.floatingvoicebubble.trace.FinalizationTrace
import com.goodlight.floatingvoicebubble.trace.SessionTraceStore
import java.io.File
import java.util.concurrent.Executors

class VoiceBubbleAccessibilityService : AccessibilityService() {
    private lateinit var settingsStore: SettingsStore
    private lateinit var dictionary: PersonalDictionary
    private lateinit var traceStore: SessionTraceStore
    private lateinit var asrModelStore: AsrModelStore
    private lateinit var finalAsrModelStore: FinalAsrModelStore
    private lateinit var overlay: FloatingBubbleController

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "VoiceBubble-Finalizer").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val warmupWorker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "VoiceBubble-ASR-Warmup").apply { priority = Thread.NORM_PRIORITY - 1 }
    }

    private var voiceInputMethod: TrackingInputMethod? = null
    private var activeSession: SpeechRecognitionSession? = null
    private var activeTarget: EditorTarget? = null
    private var latestRaw = ""
    private var finalizationInProgress = false

    override fun onCreateInputMethod(): InputMethod = TrackingInputMethod(this).also { voiceInputMethod = it }

    override fun onServiceConnected() {
        super.onServiceConnected()
        settingsStore = SettingsStore(this)
        dictionary = PersonalDictionary(this)
        traceStore = SessionTraceStore(this)
        asrModelStore = AsrModelStore(this)
        finalAsrModelStore = FinalAsrModelStore(this)
        overlay = FloatingBubbleController(this, ::toggleRecording)
        overlay.attach()

        val settings = settingsStore.load()
        asrModelStore.resolve(settings.streamingAsrModelId)?.let { model ->
            warmupWorker.execute { runCatching { SherpaStreamingEngine.preload(model) } }
        }
        if (settings.finalAsrMode == FinalAsrMode.REAZON_SPEECH) {
            finalAsrModelStore.resolve(settings.finalAsrModelId)?.let { model ->
                warmupWorker.execute { runCatching { SherpaFinalAsrEngine.preload(model) } }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() {
        stopSession(showIdle = true)
    }

    override fun onDestroy() {
        stopSession(showIdle = false)
        if (::overlay.isInitialized) overlay.detach()
        if (::dictionary.isInitialized) dictionary.close()
        worker.shutdownNow()
        warmupWorker.shutdownNow()
        super.onDestroy()
    }

    private fun toggleRecording() {
        if (finalizationInProgress) {
            overlay.showFinalizing(latestRaw, "確定処理中です")
            return
        }
        val session = activeSession
        if (session != null) {
            overlay.showFinalizing(latestRaw, "発話を終了しました")
            session.finishInput()
        } else {
            startSession()
        }
    }

    private fun startSession() {
        if (finalizationInProgress) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            transientError("アプリを開き、マイク権限を許可してください。")
            return
        }

        val inputMethod = voiceInputMethod
        val connection = inputMethod?.currentInputConnection
        val editor = inputMethod?.currentInputEditorInfo
        if (inputMethod == null || connection == null || editor == null || !inputMethod.currentInputStarted) {
            transientError("文字入力欄へカーソルを置いてから音声入力を開始してください。")
            return
        }

        val settings = settingsStore.load()
        val streamingModel = asrModelStore.resolve(settings.streamingAsrModelId)
        if (settings.offlineMode && streamingModel == null) {
            transientError("完全オフラインには真のストリーミングASRモデルが必要です。設定からNemotronモデルを導入してください。")
            return
        }
        if (settings.recognitionMode == RecognitionMode.SHERPA_STREAMING && streamingModel == null) {
            transientError("自前ストリーミング認識を使うにはNemotronモデルが必要です。")
            return
        }

        val gemmaAvailable = File(settings.gemmaModelPath).isFile
        if (settings.correctionMode == CorrectionMode.GEMMA && !gemmaAvailable) {
            transientError("Gemma補正を使うには .litertlm モデルを読み込んでください。")
            return
        }
        if (
            settings.finalAsrMode == FinalAsrMode.REAZON_SPEECH &&
            finalAsrModelStore.resolve(settings.finalAsrModelId) == null
        ) {
            transientError("ReazonSpeech最終ASRを使うにはモデルを読み込んでください。")
            return
        }

        activeTarget = EditorTarget(
            generation = inputMethod.generation,
            packageName = editor.packageName?.toString().orEmpty(),
            fieldId = editor.fieldId,
            fieldName = editor.fieldName,
        )
        latestRaw = ""
        overlay.showListening("", "録音を開始しています")

        val session = runCatching {
            SpeechRecognitionSession(
                context = this,
                mode = settings.recognitionMode,
                offlineRequired = settings.offlineMode,
                autoEndpoint = settings.autoStop,
                biasTerms = dictionary.topBiasTerms(),
                traceAudioDir = traceStore.audioDir,
                streamingModel = streamingModel,
                onPartial = { partial ->
                    latestRaw = partial
                    overlay.showListening(partial)
                },
                onState = { state ->
                    if (activeSession != null) overlay.showListening(latestRaw, state)
                },
                onComplete = ::onRecognitionComplete,
                onFailure = { message ->
                    stopSession(showIdle = false)
                    transientError(message)
                },
            )
        }.getOrElse { failure ->
            transientError(failure.message ?: "音声認識を初期化できませんでした。")
            return
        }

        activeSession = session
        runCatching { session.start() }.onFailure { failure ->
            stopSession(showIdle = false)
            transientError(failure.message ?: "録音を開始できませんでした。")
        }
    }

    private fun onRecognitionComplete(outcome: RecognitionOutcome) {
        latestRaw = outcome.rawTranscript
        finalizationInProgress = true
        overlay.showFinalizing(outcome.rawTranscript)
        activeSession?.close()
        activeSession = null

        val expectedTarget = activeTarget
        val surrounding = if (isSameTarget(expectedTarget)) {
            runCatching {
                voiceInputMethod?.currentInputConnection
                    ?.getSurroundingText(700, 300, 0)
                    ?.text
                    ?.toString()
                    .orEmpty()
            }.getOrDefault("")
        } else {
            ""
        }
        val settings = settingsStore.load()

        worker.execute {
            try {
                finalizeAndDeliver(outcome, expectedTarget, surrounding, settings)
            } catch (failure: Throwable) {
                mainExecutor.execute {
                    recoverFinalization(expectedTarget, outcome.rawTranscript, failure)
                }
            }
        }
    }

    private fun finalizeAndDeliver(
        outcome: RecognitionOutcome,
        expectedTarget: EditorTarget?,
        surrounding: String,
        settings: AppSettings,
    ) {
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
                    finalAsrError = if (model == null) {
                        "ReazonSpeech model is missing"
                    } else {
                        "recorded WAV is missing"
                    }
                    outcome.rawTranscript
                } else {
                    runCatching { SherpaFinalAsrEngine.decode(model, audio) }
                        .onSuccess { decoded ->
                            finalAsrId = decoded.engineId
                            finalAsrLatencyMs = decoded.elapsedMs
                            finalAsrRtf = decoded.realTimeFactor
                        }
                        .onFailure { failure ->
                            finalAsrError = failure.message ?: failure.javaClass.simpleName
                        }
                        .getOrNull()
                        ?.text
                        ?: outcome.rawTranscript
                }
            }
        }

        val alternatives = buildList {
            add(correctionInput)
            addAll(outcome.alternatives)
        }.map(String::trim).filter(String::isNotEmpty).distinct()

        val relevant = dictionary.relevantTerms(correctionInput)
        val request = CorrectionRequest(
            rawTranscript = correctionInput,
            alternatives = alternatives,
            surroundingContext = surrounding,
            dictionaryTerms = relevant,
        )

        val corrector = selectCorrector(settings)
        var correctionError: String? = null
        val modelOutput = if (corrector == null) {
            correctionInput
        } else {
            runCatching { corrector.correct(request) }
                .onFailure { failure -> correctionError = failure.message ?: failure.javaClass.simpleName }
                .getOrDefault(correctionInput)
        }
        val decision = CorrectionGuard.choose(correctionInput, modelOutput)
        val finalText = decision.text

        relevant.filter { item -> finalText.contains(item.term) }
            .map { it.term }
            .takeIf { it.isNotEmpty() }
            ?.let(dictionary::markUsed)

        traceStore.save(
            FinalizationTrace(
                outcome = outcome,
                finalText = finalText,
                correctorId = corrector?.id ?: "none",
                correctionAccepted = decision.accepted,
                correctionDistance = decision.normalizedDistance,
                correctionError = correctionError ?: decision.reason,
                correctionInputText = correctionInput,
                finalAsrId = finalAsrId,
                finalAsrLatencyMs = finalAsrLatencyMs,
                finalAsrRtf = finalAsrRtf,
                finalAsrError = finalAsrError,
            ),
            enabled = settings.keepSessionTraces,
        )

        mainExecutor.execute {
            deliverFinalText(
                expectedTarget = expectedTarget,
                finalText = finalText,
                finalAsrError = finalAsrError,
                correctionError = correctionError,
                correctionAccepted = decision.accepted,
            )
        }
    }

    private fun deliverFinalText(
        expectedTarget: EditorTarget?,
        finalText: String,
        finalAsrError: String?,
        correctionError: String?,
        correctionAccepted: Boolean,
    ) {
        if (!isSameTarget(expectedTarget)) {
            activeTarget = null
            copyToClipboard(finalText)
            overlay.showFinalizing(finalText, "入力先が変わったためクリップボードへ保存しました")
            completeFinalizationAfter(CLIPBOARD_NOTICE_MS)
            return
        }

        val currentConnection = voiceInputMethod?.currentInputConnection
        if (currentConnection == null) {
            activeTarget = null
            copyToClipboard(finalText)
            overlay.showFinalizing(finalText, "入力欄が消えたためクリップボードへ保存しました")
            completeFinalizationAfter(CLIPBOARD_NOTICE_MS)
            return
        }

        val dispatchSucceeded = runCatching {
            // AccessibilityInputConnection.commitText() is Unit/void on API 33+.
            // A disconnected editor can only be detected here through an exception;
            // target generation is revalidated immediately before this call.
            currentConnection.commitText(finalText, 1, null)
        }.isSuccess
        activeTarget = null

        if (!dispatchSucceeded) {
            copyToClipboard(finalText)
            overlay.showFinalizing(finalText, "直接入力できなかったためクリップボードへ保存しました")
            completeFinalizationAfter(CLIPBOARD_NOTICE_MS)
            return
        }

        when {
            finalAsrError != null -> overlay.showFinalizing(finalText, "最終ASRを使えず、live認識結果で入力しました")
            correctionError != null -> overlay.showFinalizing(finalText, "補正を使えず、認識結果を入力しました")
            !correctionAccepted -> overlay.showFinalizing(finalText, "語調保護のため過大な補正を破棄しました")
            else -> overlay.showFinalizing(finalText, "入力しました")
        }
        completeFinalizationAfter(DIRECT_INSERT_NOTICE_MS)
    }

    private fun recoverFinalization(expectedTarget: EditorTarget?, fallbackText: String, failure: Throwable) {
        val dispatchSucceeded = if (isSameTarget(expectedTarget)) {
            voiceInputMethod?.currentInputConnection?.let { connection ->
                runCatching { connection.commitText(fallbackText, 1, null) }.isSuccess
            } ?: false
        } else {
            false
        }
        activeTarget = null

        if (dispatchSucceeded) {
            overlay.showFinalizing(fallbackText, "確定処理でエラーが発生したためlive認識結果を入力しました")
            completeFinalizationAfter(CLIPBOARD_NOTICE_MS)
            return
        }

        copyToClipboard(fallbackText)
        val detail = failure.message?.takeIf(String::isNotBlank)?.take(MAX_ERROR_DETAIL_CHARS)
        overlay.showFinalizing(
            fallbackText,
            if (detail == null) {
                "確定処理でエラーが発生したためクリップボードへ保存しました"
            } else {
                "確定処理エラーのためクリップボードへ保存しました: $detail"
            },
        )
        completeFinalizationAfter(CLIPBOARD_NOTICE_MS)
    }

    private fun selectCorrector(settings: AppSettings): TextCorrector? {
        val gemmaAvailable = File(settings.gemmaModelPath).isFile
        return when (CorrectionBackendResolver.resolve(settings, gemmaAvailable)) {
            CorrectionBackend.NONE -> null
            CorrectionBackend.BYOK -> CloudCorrectorFactory.create(
                settings.byokEndpoint,
                settings.byokModel,
                settingsStore.apiKey(),
            )
            CorrectionBackend.GEMMA -> GemmaCorrector(
                this,
                settings.gemmaModelPath,
                settings.gemmaBackend,
            )
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("Floating VoiceBubble", text))
    }

    private fun completeFinalizationAfter(delayMs: Long) {
        finalizationInProgress = false
        latestRaw = ""
        returnToIdleAfter(delayMs)
    }

    private fun returnToIdleAfter(delayMs: Long) {
        rootViewHandler.postDelayed({
            if (activeSession == null && !finalizationInProgress) overlay.showIdle()
        }, delayMs)
    }

    private fun stopSession(showIdle: Boolean) {
        activeSession?.close()
        activeSession = null
        if (!finalizationInProgress) {
            activeTarget = null
            latestRaw = ""
        }
        if (showIdle && ::overlay.isInitialized && !finalizationInProgress) {
            overlay.showIdle()
        }
    }

    private fun transientError(message: String) {
        if (!::overlay.isInitialized) return
        overlay.showError(message)
        returnToIdleAfter(ERROR_NOTICE_MS)
    }

    private fun isSameTarget(expected: EditorTarget?): Boolean {
        expected ?: return false
        val inputMethod = voiceInputMethod ?: return false
        if (!inputMethod.currentInputStarted || inputMethod.generation != expected.generation) return false
        val editor = inputMethod.currentInputEditorInfo ?: return false
        return expected.packageName == editor.packageName?.toString().orEmpty() &&
            expected.fieldId == editor.fieldId &&
            expected.fieldName == editor.fieldName
    }

    private val rootViewHandler by lazy { android.os.Handler(mainLooper) }

    private data class EditorTarget(
        val generation: Long,
        val packageName: String,
        val fieldId: Int,
        val fieldName: String?,
    )

    private class TrackingInputMethod(service: AccessibilityService) : InputMethod(service) {
        var generation: Long = 0L
            private set

        override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
            if (!restarting) generation += 1L
            super.onStartInput(attribute, restarting)
        }
    }

    companion object {
        private const val DIRECT_INSERT_NOTICE_MS = 550L
        private const val CLIPBOARD_NOTICE_MS = 1_800L
        private const val ERROR_NOTICE_MS = 2_800L
        private const val MAX_ERROR_DETAIL_CHARS = 80
    }
}
