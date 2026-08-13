package com.goodlight.floatingvoicebubble.accessibility

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.InputMethod
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.EditorInfo
import com.goodlight.floatingvoicebubble.AppProfileStore
import com.goodlight.floatingvoicebubble.AppSettings
import com.goodlight.floatingvoicebubble.CorrectionMode
import com.goodlight.floatingvoicebubble.FinalAsrMode
import com.goodlight.floatingvoicebubble.RecognitionMode
import com.goodlight.floatingvoicebubble.SettingsStore
import com.goodlight.floatingvoicebubble.correction.ByokEndpointResolver
import com.goodlight.floatingvoicebubble.correction.CloudCorrectorFactory
import com.goodlight.floatingvoicebubble.correction.CorrectionBackend
import com.goodlight.floatingvoicebubble.correction.CorrectionBackendResolver
import com.goodlight.floatingvoicebubble.correction.CorrectionGuard
import com.goodlight.floatingvoicebubble.correction.CorrectionPreferences
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
import java.util.LinkedHashMap
import java.util.concurrent.Executors

class VoiceBubbleAccessibilityService : AccessibilityService() {
    private lateinit var settingsStore: SettingsStore
    private lateinit var appProfileStore: AppProfileStore
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
    private var inputSessionAvailable = false
    private var imeWindowVisible = false
    private var bubbleAvailable = false
    private var sessionGeneration = 0L
    private var nextFinalizationId = 0L
    private val pendingFinalizations = LinkedHashMap<Long, String>()

    override fun onCreateInputMethod(): InputMethod = TrackingInputMethod(this) { available ->
        rootViewHandler.post { onInputAvailabilityChanged(available) }
    }.also { voiceInputMethod = it }

    override fun onServiceConnected() {
        super.onServiceConnected()
        settingsStore = SettingsStore(this)
        appProfileStore = AppProfileStore(this)
        dictionary = PersonalDictionary(this)
        traceStore = SessionTraceStore(this)
        asrModelStore = AsrModelStore(this)
        finalAsrModelStore = FinalAsrModelStore(this)
        overlay = FloatingBubbleController(
            service = this,
            onToggle = ::toggleRecording,
            onCancel = ::cancelCurrentOperation,
            onDismiss = ::dismissCurrentInput,
        )
        overlay.attach()
        inputSessionAvailable = voiceInputMethod?.currentInputStarted == true
        updateImeWindowVisibility(resetDismissal = true)

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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> rootViewHandler.post { updateImeWindowVisibility() }
        }
    }

    override fun onInterrupt() {
        cancelCurrentOperation()
    }

    override fun onDestroy() {
        activeSession?.close()
        activeSession = null
        pendingFinalizations.clear()
        if (::overlay.isInitialized) overlay.detach()
        if (::dictionary.isInitialized) dictionary.close()
        worker.shutdownNow()
        warmupWorker.shutdownNow()
        super.onDestroy()
    }

    private fun onInputAvailabilityChanged(available: Boolean) {
        inputSessionAvailable = available
        if (!available) {
            refreshBubbleAvailability()
            return
        }
        // OEM IMEs do not all publish the input window in the same frame as onStartInput.
        // Probe now and shortly afterwards; window-change events keep it exact after that.
        updateImeWindowVisibility(resetDismissal = true)
        rootViewHandler.postDelayed({ updateImeWindowVisibility(resetDismissal = true) }, IME_WINDOW_RECHECK_MS)
    }

    private fun updateImeWindowVisibility(resetDismissal: Boolean = false) {
        imeWindowVisible = runCatching {
            windows.any { window -> window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        }.getOrDefault(false)
        refreshBubbleAvailability(resetDismissal)
    }

    private fun refreshBubbleAvailability(resetDismissal: Boolean = false) {
        val available = inputSessionAvailable && imeWindowVisible
        val becameVisible = !bubbleAvailable && available
        val becameHidden = bubbleAvailable && !available
        bubbleAvailable = available
        if (::overlay.isInitialized) {
            overlay.setInputAvailable(available, resetDismissal = resetDismissal || becameVisible)
        }
        if (becameHidden) {
            // Never leave the microphone running invisibly after the user closes the IME.
            activeSession?.finishInput()
        } else if (available && activeSession == null && pendingFinalizations.isEmpty() && ::overlay.isInitialized) {
            overlay.showIdle()
        }
    }

    private fun toggleRecording() {
        val session = activeSession
        if (session != null) {
            overlay.showFinalizing(latestRaw, "発話を終了しました")
            session.finishInput()
        } else {
            startSession()
        }
    }

    private fun startSession() {
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

        val targetPackageName = editor.packageName?.toString().orEmpty()
        appProfileStore.recordInputApp(targetPackageName)
        val settings = appProfileStore.effectiveSettings(settingsStore.load(), targetPackageName)
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
            transientError("Gemma補正を使うにはE2B/E4Bモデルを自動導入または読み込んでください。")
            return
        }
        val correctionBackend = CorrectionBackendResolver.resolve(settings, gemmaAvailable)
        if (correctionBackend == CorrectionBackend.BYOK) {
            if (settings.byokModel.isBlank()) {
                transientError("BYOKモデルが未設定です。設定画面でモデル一覧を取得して選択してください。")
                return
            }
            runCatching { ByokEndpointResolver.resolve(settings.byokEndpoint) }.onFailure { failure ->
                transientError(failure.message ?: "BYOK API URLが不正です。")
                return
            }
        }
        if (
            settings.finalAsrMode == FinalAsrMode.REAZON_SPEECH &&
            finalAsrModelStore.resolve(settings.finalAsrModelId) == null
        ) {
            transientError("ReazonSpeech最終ASRを使うにはモデルを導入してください。")
            return
        }

        activeTarget = EditorTarget(
            generation = inputMethod.generation,
            packageName = targetPackageName,
            fieldId = editor.fieldId,
            fieldName = editor.fieldName,
        )
        latestRaw = ""
        overlay.showListening("", "録音を開始しています")
        val token = ++sessionGeneration

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
                    if (token == sessionGeneration && activeSession != null) {
                        latestRaw = partial
                        overlay.showListening(partial)
                    }
                },
                onState = { state ->
                    if (token == sessionGeneration && activeSession != null) overlay.showListening(latestRaw, state)
                },
                onComplete = { outcome -> onRecognitionComplete(token, outcome) },
                onFailure = { message -> onRecognitionFailure(token, message) },
            )
        }.getOrElse { failure ->
            transientError(failure.message ?: "音声認識を初期化できませんでした。")
            return
        }

        activeSession = session
        runCatching { session.start() }.onFailure { failure ->
            if (token == sessionGeneration) {
                session.close()
                activeSession = null
                activeTarget = null
                transientError(failure.message ?: "録音を開始できませんでした。")
            }
        }
    }

    private fun onRecognitionFailure(token: Long, message: String) {
        if (token != sessionGeneration) return
        activeSession?.close()
        activeSession = null
        activeTarget = null
        latestRaw = ""
        transientError(message)
    }

    private fun onRecognitionComplete(token: Long, outcome: RecognitionOutcome) {
        if (token != sessionGeneration) return
        latestRaw = outcome.rawTranscript
        activeSession?.close()
        activeSession = null

        val expectedTarget = activeTarget
        activeTarget = null
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
        val baseSettings = settingsStore.load()
        val settings = expectedTarget?.packageName
            ?.let { appProfileStore.effectiveSettings(baseSettings, it) }
            ?: baseSettings
        val jobId = ++nextFinalizationId
        pendingFinalizations[jobId] = outcome.rawTranscript
        overlay.showFinalizing(outcome.rawTranscript)

        worker.execute {
            try {
                finalizeAndDeliver(jobId, outcome, expectedTarget, surrounding, settings)
            } catch (failure: Throwable) {
                mainExecutor.execute {
                    recoverFinalization(jobId, expectedTarget, outcome.rawTranscript, failure)
                }
            }
        }
    }

    private fun finalizeAndDeliver(
        jobId: Long,
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
                    finalAsrError = if (model == null) "ReazonSpeech model is missing" else "recorded WAV is missing"
                    outcome.rawTranscript
                } else {
                    runCatching { SherpaFinalAsrEngine.decode(model, audio) }
                        .onSuccess { decoded ->
                            finalAsrId = decoded.engineId
                            finalAsrLatencyMs = decoded.elapsedMs
                            finalAsrRtf = decoded.realTimeFactor
                        }
                        .onFailure { failure -> finalAsrError = failure.message ?: failure.javaClass.simpleName }
                        .getOrNull()?.text ?: outcome.rawTranscript
                }
            }
        }

        val alternatives = buildList {
            add(correctionInput)
            addAll(outcome.alternatives)
        }.map(String::trim).filter(String::isNotEmpty).distinct()

        val relevant = dictionary.relevantTerms(correctionInput)
        val preferences = CorrectionPreferences(
            addCommas = settings.correctionAddCommas,
            addPeriods = settings.correctionAddPeriods,
            removeFillers = settings.correctionRemoveFillers,
            polite = settings.correctionPolite,
            businessPolite = settings.correctionBusinessPolite,
        )
        val request = CorrectionRequest(
            rawTranscript = correctionInput,
            alternatives = alternatives,
            surroundingContext = surrounding,
            dictionaryTerms = relevant,
            preferences = preferences,
        )

        var correctionError: String? = null
        val corrector = runCatching { selectCorrector(settings) }
            .onFailure { failure -> correctionError = failure.message ?: failure.javaClass.simpleName }
            .getOrNull()
        val modelOutput = if (corrector == null) {
            correctionInput
        } else {
            runCatching { corrector.correct(request) }
                .onFailure { failure -> correctionError = failure.message ?: failure.javaClass.simpleName }
                .getOrDefault(correctionInput)
        }
        val decision = CorrectionGuard.choose(correctionInput, modelOutput, preferences)
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
                jobId = jobId,
                expectedTarget = expectedTarget,
                finalText = finalText,
                finalAsrError = finalAsrError,
                correctionError = correctionError,
                correctionAccepted = decision.accepted,
                correctionDecisionReason = decision.reason,
            )
        }
    }

    private fun deliverFinalText(
        jobId: Long,
        expectedTarget: EditorTarget?,
        finalText: String,
        finalAsrError: String?,
        correctionError: String?,
        correctionAccepted: Boolean,
        correctionDecisionReason: String?,
    ) {
        if (pendingFinalizations.remove(jobId) == null) return

        if (!isSameTarget(expectedTarget)) {
            copyToClipboard(finalText)
            showFinalizationNotice(finalText, "入力先が変わったためクリップボードへ保存しました", CLIPBOARD_NOTICE_MS)
            return
        }

        val currentConnection = voiceInputMethod?.currentInputConnection
        if (currentConnection == null) {
            copyToClipboard(finalText)
            showFinalizationNotice(finalText, "入力欄が消えたためクリップボードへ保存しました", CLIPBOARD_NOTICE_MS)
            return
        }

        val dispatchSucceeded = runCatching { currentConnection.commitText(finalText, 1, null) }.isSuccess
        if (!dispatchSucceeded) {
            copyToClipboard(finalText)
            showFinalizationNotice(finalText, "直接入力できなかったためクリップボードへ保存しました", CLIPBOARD_NOTICE_MS)
            return
        }

        val state = when {
            finalAsrError != null -> "最終ASRを使えず、live認識結果で入力しました"
            correctionError != null -> "補正失敗: ${compactError(correctionError)} — 認識結果を入力しました"
            !correctionAccepted -> when (correctionDecisionReason) {
                "comma-not-allowed" -> "読点追加OFFに反した補正を拒否し、認識結果を入力しました"
                "period-not-allowed" -> "句点追加OFFに反した補正を拒否し、認識結果を入力しました"
                "filler-removal-not-allowed" -> "フィラー除去OFFに反した補正を拒否し、認識結果を入力しました"
                else -> "補正が大きすぎたため、認識結果を保護して入力しました"
            }
            else -> "入力しました"
        }
        val notice = if (correctionError != null || !correctionAccepted) CLIPBOARD_NOTICE_MS else DIRECT_INSERT_NOTICE_MS
        showFinalizationNotice(finalText, state, notice)
    }

    private fun recoverFinalization(
        jobId: Long,
        expectedTarget: EditorTarget?,
        fallbackText: String,
        failure: Throwable,
    ) {
        if (pendingFinalizations.remove(jobId) == null) return
        val dispatchSucceeded = if (isSameTarget(expectedTarget)) {
            voiceInputMethod?.currentInputConnection?.let { connection ->
                runCatching { connection.commitText(fallbackText, 1, null) }.isSuccess
            } ?: false
        } else {
            false
        }

        if (dispatchSucceeded) {
            showFinalizationNotice(fallbackText, "確定処理エラーのためlive認識結果を入力しました", CLIPBOARD_NOTICE_MS)
            return
        }

        copyToClipboard(fallbackText)
        val detail = failure.message?.takeIf(String::isNotBlank)?.let(::compactError)
        showFinalizationNotice(
            fallbackText,
            detail?.let { "確定処理エラー: $it — クリップボードへ保存しました" }
                ?: "確定処理エラーのためクリップボードへ保存しました",
            CLIPBOARD_NOTICE_MS,
        )
    }

    private fun showFinalizationNotice(text: String, state: String, delayMs: Long) {
        // Never cover a new live transcript with an older session's correction result.
        if (activeSession == null && bubbleAvailable) {
            overlay.showFinalizing(text, state)
            returnToIdleAfter(delayMs)
        }
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

    private fun cancelCurrentOperation() {
        val session = activeSession
        if (session != null) {
            ++sessionGeneration
            session.close()
            activeSession = null
            activeTarget = null
            latestRaw = ""
            if (pendingFinalizations.isEmpty()) overlay.showIdle()
            else overlay.showFinalizing(pendingFinalizations.values.last(), "前の発話を整えています")
            return
        }

        if (pendingFinalizations.isNotEmpty()) pendingFinalizations.clear()
        latestRaw = ""
        if (::overlay.isInitialized) overlay.showIdle()
    }

    private fun dismissCurrentInput() {
        ++sessionGeneration
        activeSession?.close()
        activeSession = null
        activeTarget = null
        latestRaw = ""
        pendingFinalizations.clear()
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("Floating VoiceBubble", text))
    }

    private fun returnToIdleAfter(delayMs: Long) {
        rootViewHandler.postDelayed({
            when {
                activeSession != null || !bubbleAvailable -> Unit
                pendingFinalizations.isNotEmpty() -> overlay.showFinalizing(
                    pendingFinalizations.values.last(),
                    "前の発話を整えています",
                )
                else -> overlay.showIdle()
            }
        }, delayMs)
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

    private fun compactError(value: String): String = value.replace(Regex("\\s+"), " ").trim().take(MAX_ERROR_DETAIL_CHARS)

    private val rootViewHandler by lazy { android.os.Handler(mainLooper) }

    private data class EditorTarget(
        val generation: Long,
        val packageName: String,
        val fieldId: Int,
        val fieldName: String?,
    )

    private class TrackingInputMethod(
        service: AccessibilityService,
        private val onInputAvailabilityChanged: (Boolean) -> Unit,
    ) : InputMethod(service) {
        var generation: Long = 0L
            private set

        override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
            if (!restarting) generation += 1L
            super.onStartInput(attribute, restarting)
            onInputAvailabilityChanged(true)
        }

        override fun onFinishInput() {
            super.onFinishInput()
            onInputAvailabilityChanged(false)
        }
    }

    companion object {
        private const val DIRECT_INSERT_NOTICE_MS = 650L
        private const val CLIPBOARD_NOTICE_MS = 2_200L
        private const val ERROR_NOTICE_MS = 3_000L
        private const val IME_WINDOW_RECHECK_MS = 180L
        private const val MAX_ERROR_DETAIL_CHARS = 92
    }
}
