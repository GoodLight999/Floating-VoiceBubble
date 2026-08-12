package com.goodlight.floatingvoicebubble.accessibility

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.InputMethod
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.EditorInfo
import com.goodlight.floatingvoicebubble.CorrectionMode
import com.goodlight.floatingvoicebubble.SettingsStore
import com.goodlight.floatingvoicebubble.correction.CorrectionBackend
import com.goodlight.floatingvoicebubble.correction.CorrectionBackendResolver
import com.goodlight.floatingvoicebubble.correction.CorrectionGuard
import com.goodlight.floatingvoicebubble.correction.CorrectionRequest
import com.goodlight.floatingvoicebubble.correction.GemmaCorrector
import com.goodlight.floatingvoicebubble.correction.OpenAiCompatibleCorrector
import com.goodlight.floatingvoicebubble.correction.TextCorrector
import com.goodlight.floatingvoicebubble.dictionary.PersonalDictionary
import com.goodlight.floatingvoicebubble.overlay.FloatingBubbleController
import com.goodlight.floatingvoicebubble.speech.RecognitionOutcome
import com.goodlight.floatingvoicebubble.speech.SpeechRecognitionSession
import com.goodlight.floatingvoicebubble.trace.FinalizationTrace
import com.goodlight.floatingvoicebubble.trace.SessionTraceStore
import java.io.File
import java.util.concurrent.Executors

class VoiceBubbleAccessibilityService : AccessibilityService() {
    private lateinit var settingsStore: SettingsStore
    private lateinit var dictionary: PersonalDictionary
    private lateinit var traceStore: SessionTraceStore
    private lateinit var overlay: FloatingBubbleController
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "VoiceBubble-Finalizer").apply { priority = Thread.NORM_PRIORITY - 1 }
    }

    private var voiceInputMethod: TrackingInputMethod? = null
    private var activeSession: SpeechRecognitionSession? = null
    private var activeTarget: EditorTarget? = null
    private var latestRaw = ""

    override fun onCreateInputMethod(): InputMethod = TrackingInputMethod(this).also { voiceInputMethod = it }

    override fun onServiceConnected() {
        super.onServiceConnected()
        settingsStore = SettingsStore(this)
        dictionary = PersonalDictionary(this)
        traceStore = SessionTraceStore(this)
        overlay = FloatingBubbleController(this, ::toggleRecording)
        overlay.attach()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = stopSession(showIdle = true)

    override fun onDestroy() {
        stopSession(showIdle = false)
        if (::overlay.isInitialized) overlay.detach()
        if (::dictionary.isInitialized) dictionary.close()
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun toggleRecording() {
        if (activeSession != null) {
            overlay.showFinalizing(latestRaw, "発話を終了しました")
            activeSession?.finishInput()
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

        val settings = settingsStore.load()
        if (settings.offlineMode && !File(settings.gemmaModelPath).isFile) {
            transientError("オフラインモードにはGemmaの .litertlm モデルが必要です。アプリ設定から読み込んでください。")
            return
        }
        if (settings.correctionMode == CorrectionMode.GEMMA && !File(settings.gemmaModelPath).isFile) {
            transientError("Gemma補正を使うには .litertlm モデルを読み込んでください。")
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
        }.getOrElse {
            transientError(it.message ?: "音声認識を初期化できませんでした。")
            return
        }
        activeSession = session
        runCatching { session.start() }.onFailure {
            stopSession(showIdle = false)
            transientError(it.message ?: "録音を開始できませんでした。")
        }
    }

    private fun onRecognitionComplete(outcome: RecognitionOutcome) {
        latestRaw = outcome.rawTranscript
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
        val relevant = dictionary.relevantTerms(outcome.rawTranscript)
        val request = CorrectionRequest(
            rawTranscript = outcome.rawTranscript,
            alternatives = outcome.alternatives,
            surroundingContext = surrounding,
            dictionaryTerms = relevant,
        )

        worker.execute {
            val corrector = selectCorrector(settings)
            var correctionError: String? = null
            val modelOutput = if (corrector == null) {
                outcome.rawTranscript
            } else {
                runCatching { corrector.correct(request) }
                    .onFailure { correctionError = it.message ?: it.javaClass.simpleName }
                    .getOrDefault(outcome.rawTranscript)
            }
            val decision = CorrectionGuard.choose(outcome.rawTranscript, modelOutput)
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
                ),
                enabled = settings.keepSessionTraces,
            )

            mainExecutor.execute {
                if (!isSameTarget(expectedTarget)) {
                    activeTarget = null
                    copyToClipboard(finalText)
                    overlay.showFinalizing(finalText, "入力先が変わったためクリップボードへ保存しました")
                    returnToIdleAfter(CLIPBOARD_NOTICE_MS)
                    return@execute
                }

                val connection = voiceInputMethod?.currentInputConnection
                if (connection == null) {
                    activeTarget = null
                    copyToClipboard(finalText)
                    overlay.showFinalizing(finalText, "入力欄が消えたためクリップボードへ保存しました")
                    returnToIdleAfter(CLIPBOARD_NOTICE_MS)
                    return@execute
                }

                val committed = runCatching {
                    connection.commitText(finalText, 1, null)
                }.isSuccess
                activeTarget = null

                if (!committed) {
                    copyToClipboard(finalText)
                    overlay.showFinalizing(finalText, "入力できなかったためクリップボードへ保存しました")
                    returnToIdleAfter(CLIPBOARD_NOTICE_MS)
                    return@execute
                }

                if (correctionError != null) {
                    overlay.showFinalizing(finalText, "補正を使えず、認識結果を入力しました")
                } else if (!decision.accepted) {
                    overlay.showFinalizing(finalText, "語調保護のため過大な補正を破棄しました")
                } else {
                    overlay.showFinalizing(finalText, "入力しました")
                }
                returnToIdleAfter(550L)
            }
        }
    }

    private fun selectCorrector(settings: com.goodlight.floatingvoicebubble.AppSettings): TextCorrector? {
        val gemmaAvailable = File(settings.gemmaModelPath).isFile
        return when (CorrectionBackendResolver.resolve(settings, gemmaAvailable)) {
            CorrectionBackend.NONE -> null
            CorrectionBackend.BYOK -> OpenAiCompatibleCorrector(
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

    private fun returnToIdleAfter(delayMs: Long) {
        rootViewHandler.postDelayed({ if (activeSession == null) overlay.showIdle() }, delayMs)
    }

    private fun stopSession(showIdle: Boolean) {
        activeSession?.close()
        activeSession = null
        activeTarget = null
        latestRaw = ""
        if (showIdle && ::overlay.isInitialized) overlay.showIdle()
    }

    private fun transientError(message: String) {
        if (!::overlay.isInitialized) return
        overlay.showError(message)
        returnToIdleAfter(2_800L)
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
        private const val CLIPBOARD_NOTICE_MS = 1_800L
    }
}
