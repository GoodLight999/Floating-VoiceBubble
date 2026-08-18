package com.goodlight.floatingvoicebubble.accessibility

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.InputMethod
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.text.InputType
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
import com.goodlight.floatingvoicebubble.correction.CorrectionBackend
import com.goodlight.floatingvoicebubble.correction.CorrectionBackendResolver
import com.goodlight.floatingvoicebubble.correction.FinalizationEngine
import com.goodlight.floatingvoicebubble.correction.FinalizationResult
import com.goodlight.floatingvoicebubble.dictionary.PersonalDictionary
import com.goodlight.floatingvoicebubble.model.AsrModelStore
import com.goodlight.floatingvoicebubble.model.FinalAsrModelStore
import com.goodlight.floatingvoicebubble.overlay.FloatingBubbleController
import com.goodlight.floatingvoicebubble.speech.RecognitionOutcome
import com.goodlight.floatingvoicebubble.speech.SherpaFinalAsrEngine
import com.goodlight.floatingvoicebubble.speech.SherpaStreamingEngine
import com.goodlight.floatingvoicebubble.speech.SpeechRecognitionSession
import com.goodlight.floatingvoicebubble.trace.SessionTraceStore
import java.io.File
import java.util.LinkedHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeoutException

class VoiceBubbleAccessibilityService : AccessibilityService() {
    private lateinit var settings: SettingsStore
    private lateinit var profiles: AppProfileStore
    private lateinit var dictionary: PersonalDictionary
    private lateinit var traces: SessionTraceStore
    private lateinit var asrModels: AsrModelStore
    private lateinit var finalModels: FinalAsrModelStore
    private lateinit var finalizer: FinalizationEngine
    private lateinit var overlay: FloatingBubbleController

    // Network-backed finalization can run concurrently. Native/local model work stays serialized to
    // avoid multiplying model memory and relying on undocumented concurrent native-runtime safety.
    // OrderedResolutionQueue below guarantees that commits still happen in utterance order.
    private val cloudFinalization = Executors.newFixedThreadPool(3) { r -> Thread(r, "VoiceBubble-CloudFinalizer") }
    private val localFinalization = Executors.newSingleThreadExecutor { r -> Thread(r, "VoiceBubble-LocalFinalizer") }
    private val inference = Executors.newCachedThreadPool { r -> Thread(r, "VoiceBubble-Inference") }
    private val warmup = Executors.newSingleThreadExecutor { r -> Thread(r, "VoiceBubble-Warmup") }
    private var input: TrackingInputMethod? = null
    private var session: SpeechRecognitionSession? = null
    private var target: Target? = null
    private var latest = ""
    private var inputStarted = false
    private var imeVisible = false
    private var bubbleVisible = false
    private var generation = 0L
    private var rawGeneration: Long? = null
    private var nextJob = 0L
    private val pending = LinkedHashMap<Long, String>()
    private val targets = LinkedHashMap<Long, Target?>()
    private val finalizationOrder = OrderedResolutionQueue<Long, FinalizationResolution>()

    /**
     * Recent VoiceBubble commits are kept only in this service process. They are not persisted and
     * are never populated from arbitrary Accessibility window text. This gives chat-style inputs
     * useful conversational context after the editor is cleared without turning the service into a
     * screen scraper. Password fields are excluded entirely.
     */
    private val recentVoiceContext = RecentVoiceContextBuffer()
    private val h by lazy { android.os.Handler(mainLooper) }

    override fun onCreateInputMethod(): InputMethod =
        TrackingInputMethod(this) { value -> h.post { inputChanged(value) } }.also { input = it }

    override fun onServiceConnected() {
        super.onServiceConnected()
        settings = SettingsStore(this)
        profiles = AppProfileStore(this)
        dictionary = PersonalDictionary(this)
        traces = SessionTraceStore(this)
        asrModels = AsrModelStore(this)
        finalModels = FinalAsrModelStore(this)
        finalizer = FinalizationEngine(this, settings, dictionary, traces, finalModels, inference)
        overlay = FloatingBubbleController(this, ::toggle, ::commitRaw, ::cancel, ::dismiss)
        overlay.attach()
        inputStarted = input?.currentInputStarted == true
        updateIme(true)
        val current = settings.load()
        asrModels.resolve(current.streamingAsrModelId)?.let { model ->
            warmup.execute { runCatching { SherpaStreamingEngine.preload(model) } }
        }
        if (current.finalAsrMode == FinalAsrMode.REAZON_SPEECH) {
            finalModels.resolve(current.finalAsrModelId)?.let { model ->
                warmup.execute { runCatching { SherpaFinalAsrEngine.preload(model) } }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            -> h.post { updateIme() }
        }
    }

    override fun onInterrupt() = cancel()

    override fun onDestroy() {
        session?.close()
        pending.clear()
        targets.clear()
        finalizationOrder.clear()
        recentVoiceContext.clear()
        if (::overlay.isInitialized) overlay.detach()
        if (::dictionary.isInitialized) dictionary.close()
        cloudFinalization.shutdownNow()
        localFinalization.shutdownNow()
        inference.shutdownNow()
        warmup.shutdownNow()
        super.onDestroy()
    }

    private fun inputChanged(value: Boolean) {
        inputStarted = value
        if (!value) {
            refresh()
            return
        }
        updateIme(true)
        h.postDelayed({ updateIme(true) }, 180)
    }

    private fun updateIme(reset: Boolean = false) {
        imeVisible = runCatching { windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } }
            .getOrDefault(false)
        refresh(reset)
    }

    private fun refresh(reset: Boolean = false) {
        val visible = inputStarted && imeVisible
        val appeared = !bubbleVisible && visible
        val hidden = bubbleVisible && !visible
        bubbleVisible = visible
        if (::overlay.isInitialized) overlay.setInputAvailable(visible, reset || appeared)
        when {
            hidden -> session?.finishInput()
            visible && session == null && pending.isEmpty() -> overlay.showIdle()
            visible && session == null -> overlay.showFinalizingStack(pending.values.toList())
        }
    }

    private fun toggle() {
        session?.let {
            overlay.showListening(latest, "発話を終了しています", pending.values.toList())
            it.finishInput()
            return
        }
        start()
    }

    private fun start() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            errorUi("マイク権限を許可してください。")
            return
        }
        val method = input
        val connection = method?.currentInputConnection
        val editor = method?.currentInputEditorInfo
        if (method == null || connection == null || editor == null || !method.currentInputStarted) {
            errorUi("文字入力欄へカーソルを置いてください。")
            return
        }
        val pkg = editor.packageName.orEmpty()
        profiles.recordInputApp(pkg)
        val effective = profiles.effectiveSettings(settings.load(), pkg)
        val model = asrModels.resolve(effective.streamingAsrModelId)
        if (effective.offlineMode && model == null) {
            errorUi("完全オフラインにはストリーミングASRモデルが必要です。")
            return
        }
        if (effective.recognitionMode == RecognitionMode.SHERPA_STREAMING && model == null) {
            errorUi("Nemotronモデルを導入してください。")
            return
        }
        val gemma = File(effective.gemmaModelPath).isFile
        if (effective.correctionMode == CorrectionMode.GEMMA && !gemma) {
            errorUi("Gemma補正モデルを導入してください。")
            return
        }
        if (CorrectionBackendResolver.resolve(effective, gemma) == CorrectionBackend.BYOK) {
            if (effective.byokModel.isBlank()) {
                errorUi("補正モデルを選択してください。")
                return
            }
            runCatching { ByokEndpointResolver.resolve(effective.byokEndpoint) }
                .onFailure {
                    errorUi(it.message ?: "API URLが不正です。")
                    return
                }
        }
        if (
            effective.finalAsrMode == FinalAsrMode.REAZON_SPEECH &&
            finalModels.resolve(effective.finalAsrModelId) == null
        ) {
            errorUi("ReazonSpeechモデルを導入してください。")
            return
        }

        target = Target(
            generation = method.generation,
            packageName = pkg,
            fieldId = editor.fieldId,
            fieldName = editor.fieldName,
            inputType = editor.inputType,
        )
        latest = ""
        overlay.showListening("", "録音を開始しています", pending.values.toList())
        val token = ++generation
        val created = runCatching {
            SpeechRecognitionSession(
                context = this,
                mode = effective.recognitionMode,
                offlineRequired = effective.offlineMode,
                autoEndpoint = effective.autoStop,
                biasTerms = dictionary.topBiasTerms(),
                traceAudioDir = traces.audioDir,
                streamingModel = model,
                onPartial = { text ->
                    if (token == generation && session != null) {
                        latest = text
                        overlay.showListening(text, pending = pending.values.toList())
                    }
                },
                onState = { state ->
                    if (token == generation && session != null) {
                        overlay.showListening(latest, state, pending.values.toList())
                    }
                },
                onComplete = { outcome -> complete(token, outcome) },
                onFailure = { message -> failed(token, message) },
            )
        }.getOrElse {
            errorUi(it.message ?: "音声認識を初期化できませんでした。")
            return
        }
        session = created
        runCatching { created.start() }.onFailure {
            if (token == generation) {
                created.close()
                session = null
                target = null
                errorUi(it.message ?: "録音を開始できませんでした。")
            }
        }
    }

    private fun failed(token: Long, message: String) {
        if (token != generation) return
        if (rawGeneration == token) rawGeneration = null
        session?.close()
        session = null
        target = null
        latest = ""
        errorUi(message)
    }

    private fun complete(token: Long, outcome: RecognitionOutcome) {
        if (token != generation) return
        latest = outcome.rawTranscript
        session?.close()
        session = null
        val bypass = rawGeneration == token
        if (bypass) rawGeneration = null
        val capturedTarget = target
        target = null
        val base = settings.load()
        val effective = capturedTarget?.packageName?.let { profiles.effectiveSettings(base, it) } ?: base
        if (bypass) {
            put(capturedTarget, outcome.rawTranscript, "補正なしで入力しました")
            cloudFinalization.execute { runCatching { finalizer.finalize(outcome, "", effective, true) } }
            return
        }

        val context = correctionContext(capturedTarget)
        val id = ++nextJob
        pending[id] = outcome.rawTranscript
        targets[id] = capturedTarget
        finalizationOrder.register(id)
        overlay.showFinalizingStack(pending.values.toList(), "LM補正しています")

        // The watchdog starts when this job actually begins executing, not while it is waiting in
        // the local-model queue. That prevents queue wait time from being misclassified as an LM
        // timeout. Cloud jobs can run concurrently; local/native jobs remain serialized.
        finalizationExecutor(effective).execute {
            h.postDelayed(
                {
                    resolveFinalization(
                        id,
                        FinalizationResolution(
                            target = capturedTarget,
                            raw = outcome.rawTranscript,
                            failure = TimeoutException("確定処理が45秒を超えました"),
                        ),
                    )
                },
                FINALIZATION_WATCHDOG_MS,
            )
            try {
                val result = finalizer.finalize(outcome, context, effective, false)
                mainExecutor.execute {
                    resolveFinalization(
                        id,
                        FinalizationResolution(capturedTarget, outcome.rawTranscript, result = result),
                    )
                }
            } catch (failure: Throwable) {
                mainExecutor.execute {
                    resolveFinalization(
                        id,
                        FinalizationResolution(capturedTarget, outcome.rawTranscript, failure = failure),
                    )
                }
            }
        }
    }

    private fun finalizationExecutor(effective: AppSettings): ExecutorService {
        val gemmaAvailable = File(effective.gemmaModelPath).isFile
        val backend = CorrectionBackendResolver.resolve(effective, gemmaAvailable)
        return if (effective.finalAsrMode == FinalAsrMode.REAZON_SPEECH || backend == CorrectionBackend.GEMMA) {
            localFinalization
        } else {
            cloudFinalization
        }
    }

    private fun commitRaw() {
        session?.let {
            rawGeneration = generation
            overlay.showListening(latest, "補正せず確定しています", pending.values.toList())
            it.finishInput()
            return
        }
        val entry = pending.entries.firstOrNull() ?: return
        val capturedTarget = targets.remove(entry.key)
        if (pending.remove(entry.key) == null) return
        val nowReady = finalizationOrder.discard(entry.key)
        put(capturedTarget, entry.value, "補正なしで入力しました")
        deliverReady(nowReady)
        if (pending.isNotEmpty()) overlay.showFinalizingStack(pending.values.toList()) else idleAfter(650)
    }

    private fun resolveFinalization(id: Long, resolution: FinalizationResolution) {
        deliverReady(finalizationOrder.resolve(id, resolution))
    }

    private fun deliverReady(ready: List<Pair<Long, FinalizationResolution>>) {
        ready.forEach { (id, resolution) ->
            if (pending.remove(id) == null) return@forEach
            targets.remove(id)
            val result = resolution.result
            if (result != null) {
                deliverResolved(resolution.target, result)
            } else {
                recoverResolved(
                    resolution.target,
                    resolution.raw,
                    resolution.failure ?: IllegalStateException("Finalization failed without a cause"),
                )
            }
        }
    }

    private fun deliverResolved(capturedTarget: Target?, result: FinalizationResult) {
        val commit = commit(capturedTarget, result.finalText)
        if (commit == CommitResult.FAILED) {
            clip(result.finalText)
            notice(result.finalText, "直接入力できずクリップボードへ保存しました", 3_500)
            return
        }
        rememberCommitted(capturedTarget, result.finalText, commit)

        val state = when {
            result.correctionBypassed -> "補正なしで入力しました"
            result.correctionError != null && !result.correctionModelChanged && result.deterministicFormattingChanged ->
                "LM補正失敗・アプリ整形のみ: ${short(result.correctionError)}"
            result.correctionError != null ->
                "LM補正失敗: ${short(result.correctionError)} — 認識結果を使用"
            !result.correctionAccepted && result.correctionModelResponded ->
                "LM出力を安全ガードが拒否: ${result.correctionDecisionReason ?: "unknown"}"
            !result.correctionAccepted ->
                "補正結果を安全ガードが拒否: ${result.correctionDecisionReason ?: "unknown"}"
            result.correctionModelChanged -> "LM補正して入力しました"
            result.correctionModelResponded && result.deterministicFormattingChanged ->
                "LMは語句変更なし・アプリ整形のみ"
            result.correctionModelResponded -> "LM補正は変更なしでした"
            result.deterministicFormattingChanged -> "補正モデルなし・アプリ整形のみ"
            result.finalAsrError != null -> "最終認識を使えずリアルタイム認識で入力しました"
            else -> "入力しました"
        }
        val suffix = if (commit == CommitResult.UNVERIFIED) "（反映確認不可）" else ""
        val delay = when {
            result.correctionError != null -> 4_800L
            !result.correctionAccepted -> 4_000L
            result.correctionModelResponded && !result.correctionModelChanged -> 2_800L
            commit == CommitResult.UNVERIFIED -> 2_800L
            result.correctionModelChanged -> 1_600L
            else -> 900L
        }
        notice(result.finalText, state + suffix, delay)
    }

    private fun put(capturedTarget: Target?, text: String, state: String) {
        when (val result = commit(capturedTarget, text)) {
            CommitResult.FAILED -> {
                clip(text)
                notice(text, "$state（クリップボードへ保存）", 3_500)
            }
            CommitResult.UNVERIFIED -> {
                rememberCommitted(capturedTarget, text, result)
                notice(text, "$state（反映確認不可）", 2_800)
            }
            CommitResult.VERIFIED -> {
                rememberCommitted(capturedTarget, text, result)
                notice(text, state, 900)
            }
        }
    }

    private fun recoverResolved(capturedTarget: Target?, text: String, failure: Throwable) {
        val result = commit(capturedTarget, text)
        val detail = failure.message?.takeIf(String::isNotBlank)?.let(::short)
        if (result != CommitResult.FAILED) {
            rememberCommitted(capturedTarget, text, result)
            val suffix = if (result == CommitResult.UNVERIFIED) "（反映確認不可）" else ""
            notice(
                text,
                (detail?.let { "LM補正処理エラー: $it — RAWを入力" } ?: "LM補正処理を完了できずRAWを入力") + suffix,
                4_800,
            )
        } else {
            clip(text)
            notice(
                text,
                detail?.let { "確定処理エラー: $it — クリップボードへ保存" }
                    ?: "確定処理エラーのためクリップボードへ保存",
                4_800,
            )
        }
    }

    private fun commit(capturedTarget: Target?, text: String): CommitResult {
        if (!same(capturedTarget)) return CommitResult.FAILED
        val connection = input?.currentInputConnection ?: return CommitResult.FAILED
        return runCatching {
            connection.commitText(text, 1, null)
            if (text.isEmpty()) return@runCatching CommitResult.VERIFIED
            val tail = text.takeLast(96)
            val after = connection.getSurroundingText(maxOf(128, tail.length + 32), 0, 0)?.text?.toString()
            if (after?.endsWith(tail) == true) CommitResult.VERIFIED else CommitResult.UNVERIFIED
        }.getOrDefault(CommitResult.FAILED)
    }

    private fun correctionContext(capturedTarget: Target?): String {
        if (capturedTarget == null || isSensitive(capturedTarget)) return ""
        val currentEditor = if (same(capturedTarget)) {
            runCatching {
                input?.currentInputConnection?.getSurroundingText(700, 300, 0)?.text?.toString().orEmpty()
            }.getOrDefault("")
        } else {
            ""
        }
        return recentVoiceContext.build(capturedTarget.contextKey(), currentEditor)
    }

    private fun rememberCommitted(capturedTarget: Target?, text: String, result: CommitResult) {
        if (capturedTarget == null || result == CommitResult.FAILED || text.isBlank() || isSensitive(capturedTarget)) return
        recentVoiceContext.add(capturedTarget.contextKey(), text)
    }

    private fun isSensitive(capturedTarget: Target): Boolean {
        val inputType = capturedTarget.inputType
        val klass = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return when (klass) {
            InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    private fun notice(text: String, state: String, delay: Long) {
        if (!bubbleVisible) return
        when {
            session != null -> overlay.showListening(latest, pending = pending.values.toList())
            pending.isNotEmpty() -> overlay.showFinalizingStack(pending.values.toList(), state)
            else -> {
                overlay.showFinalizing(text, state)
                idleAfter(delay)
            }
        }
    }

    private fun cancel() {
        session?.let {
            ++generation
            rawGeneration = null
            it.close()
            session = null
            target = null
            latest = ""
            if (pending.isEmpty()) overlay.showIdle()
            else overlay.showFinalizingStack(pending.values.toList(), "前の発話を処理しています")
            return
        }
        pending.clear()
        targets.clear()
        finalizationOrder.clear()
        latest = ""
        if (::overlay.isInitialized) overlay.showIdle()
    }

    private fun dismiss() {
        ++generation
        rawGeneration = null
        session?.close()
        session = null
        target = null
        latest = ""
        pending.clear()
        targets.clear()
        finalizationOrder.clear()
    }

    private fun clip(text: String) = getSystemService(ClipboardManager::class.java)
        .setPrimaryClip(ClipData.newPlainText("Floating VoiceBubble", text))

    private fun idleAfter(ms: Long) = h.postDelayed({
        when {
            session != null || !bubbleVisible -> Unit
            pending.isNotEmpty() -> overlay.showFinalizingStack(pending.values.toList(), "前の発話を処理しています")
            else -> overlay.showIdle()
        }
    }, ms)

    private fun errorUi(message: String) {
        if (::overlay.isInitialized) {
            overlay.showError(message)
            idleAfter(3_500)
        }
    }

    private fun same(capturedTarget: Target?): Boolean {
        capturedTarget ?: return false
        val method = input ?: return false
        if (!method.currentInputStarted || method.generation != capturedTarget.generation) return false
        val editor = method.currentInputEditorInfo ?: return false
        return capturedTarget.packageName == editor.packageName.orEmpty() &&
            capturedTarget.fieldId == editor.fieldId &&
            capturedTarget.fieldName == editor.fieldName
    }

    private fun short(value: String) = value.replace(Regex("\\s+"), " ").trim().take(92)

    private enum class CommitResult { VERIFIED, UNVERIFIED, FAILED }

    private data class FinalizationResolution(
        val target: Target?,
        val raw: String,
        val result: FinalizationResult? = null,
        val failure: Throwable? = null,
    )

    private data class Target(
        val generation: Long,
        val packageName: String,
        val fieldId: Int,
        val fieldName: String?,
        val inputType: Int,
    ) {
        fun contextKey() = VoiceContextKey(packageName, fieldId, fieldName)
    }

    private class TrackingInputMethod(
        service: AccessibilityService,
        val changed: (Boolean) -> Unit,
    ) : InputMethod(service) {
        var generation = 0L
            private set

        override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
            if (!restarting) generation++
            super.onStartInput(attribute, restarting)
            changed(true)
        }

        override fun onFinishInput() {
            super.onFinishInput()
            changed(false)
        }
    }

    companion object {
        private const val FINALIZATION_WATCHDOG_MS = 45_000L
    }
}
