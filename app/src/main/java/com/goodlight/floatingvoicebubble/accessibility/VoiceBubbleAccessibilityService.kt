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
import com.goodlight.floatingvoicebubble.CorrectionStatusStore
import com.goodlight.floatingvoicebubble.FinalAsrMode
import com.goodlight.floatingvoicebubble.LastCorrectionFailure
import com.goodlight.floatingvoicebubble.RecognitionMode
import com.goodlight.floatingvoicebubble.SettingsStore
import com.goodlight.floatingvoicebubble.correction.ByokEndpointResolver
import com.goodlight.floatingvoicebubble.correction.CloudCorrectorFactory
import com.goodlight.floatingvoicebubble.correction.CorrectionBackend
import com.goodlight.floatingvoicebubble.correction.CorrectionBackendResolver
import com.goodlight.floatingvoicebubble.correction.CorrectionTimeoutPolicy
import com.goodlight.floatingvoicebubble.correction.FinalizationEngine
import com.goodlight.floatingvoicebubble.correction.FinalizationResult
import com.goodlight.floatingvoicebubble.correction.ReasoningCapabilities
import com.goodlight.floatingvoicebubble.dictionary.PersonalDictionary
import com.goodlight.floatingvoicebubble.model.AsrModelStore
import com.goodlight.floatingvoicebubble.model.FinalAsrModelStore
import com.goodlight.floatingvoicebubble.model.GemmaModelSource
import com.goodlight.floatingvoicebubble.overlay.FloatingBubbleController
import com.goodlight.floatingvoicebubble.speech.RecognitionOutcome
import com.goodlight.floatingvoicebubble.speech.SherpaFinalAsrEngine
import com.goodlight.floatingvoicebubble.speech.SherpaStreamingEngine
import com.goodlight.floatingvoicebubble.speech.SpeechRecognitionSession
import com.goodlight.floatingvoicebubble.trace.SessionTraceStore
import java.util.LinkedHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeoutException

class VoiceBubbleAccessibilityService : AccessibilityService() {
    private lateinit var settings: SettingsStore
    private lateinit var profiles: AppProfileStore
    private lateinit var dictionary: PersonalDictionary
    private lateinit var traces: SessionTraceStore
    private lateinit var correctionStatus: CorrectionStatusStore
    private lateinit var asrModels: AsrModelStore
    private lateinit var finalModels: FinalAsrModelStore
    private lateinit var finalizer: FinalizationEngine
    private lateinit var overlay: FloatingBubbleController

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
    private val finalizationTasks = LinkedHashMap<Long, Future<*>>()
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
        correctionStatus = CorrectionStatusStore(this)
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
            else -> Unit
        }
    }

    override fun onInterrupt() = cancel()

    override fun onDestroy() {
        session?.close()
        cancelAllPendingFinalizations()
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
            errorUi("通信しない設定には端末内音声認識モデルが必要です。")
            return
        }
        if (effective.recognitionMode == RecognitionMode.SHERPA_STREAMING && model == null) {
            errorUi("端末内ストリーミング音声認識モデルを導入してください。")
            return
        }
        val gemma = GemmaModelSource.isAvailable(this, effective.gemmaModelPath)
        if (effective.correctionMode == CorrectionMode.GEMMA && !gemma) {
            errorUi("端末内Gemma補正モデルを導入するか、既存の .litertlm を選択してください。")
            return
        }
        if (CorrectionBackendResolver.resolve(effective, gemma) == CorrectionBackend.BYOK) {
            if (effective.byokModel.isBlank()) {
                errorUi("文章補正に使うモデルを選択してください。")
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
        overlay.showFinalizingStack(pending.values.toList(), correctionProgressLabel(effective))

        val task = finalizationExecutor(effective).submit {
            val watchdogMs = finalizationWatchdogMs(effective)
            h.postDelayed(
                {
                    resolveFinalization(
                        id,
                        FinalizationResolution(
                            target = capturedTarget,
                            raw = outcome.rawTranscript,
                            failure = TimeoutException("確定処理全体が${watchdogMs / 1000}秒を超えました"),
                            settings = effective,
                        ),
                        cancelTask = true,
                    )
                },
                watchdogMs,
            )
            try {
                val result = finalizer.finalize(outcome, context, effective, false)
                mainExecutor.execute {
                    resolveFinalization(
                        id,
                        FinalizationResolution(
                            capturedTarget,
                            outcome.rawTranscript,
                            result = result,
                            settings = effective,
                        ),
                    )
                }
            } catch (failure: Throwable) {
                mainExecutor.execute {
                    resolveFinalization(
                        id,
                        FinalizationResolution(
                            capturedTarget,
                            outcome.rawTranscript,
                            failure = failure,
                            settings = effective,
                        ),
                    )
                }
            }
        }
        finalizationTasks[id] = task
    }

    private fun finalizationExecutor(effective: AppSettings): ExecutorService {
        val gemmaAvailable = GemmaModelSource.isAvailable(this, effective.gemmaModelPath)
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
        finalizationTasks.remove(entry.key)?.cancel(true)
        val nowReady = finalizationOrder.discard(entry.key)
        put(capturedTarget, entry.value, "補正なしで入力しました")
        deliverReady(nowReady)
        if (pending.isNotEmpty()) overlay.showFinalizingStack(pending.values.toList()) else idleAfter(650)
    }

    private fun resolveFinalization(
        id: Long,
        resolution: FinalizationResolution,
        cancelTask: Boolean = false,
    ) {
        val task = finalizationTasks.remove(id)
        if (cancelTask) task?.cancel(true)
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
                    resolution.settings,
                )
            }
        }
    }

    private fun deliverResolved(capturedTarget: Target?, result: FinalizationResult) {
        val correctionFailed = result.correctionError != null || !result.correctionAccepted
        if (correctionFailed) {
            correctionStatus.saveFailure(
                LastCorrectionFailure(
                    occurredAtMs = System.currentTimeMillis(),
                    provider = result.correctionProvider.orEmpty(),
                    model = result.correctionModel.orEmpty(),
                    reasoning = result.correctionReasoning.orEmpty(),
                    latencyMs = result.correctionLatencyMs,
                    reason = result.correctionError ?: integrityReason(result.correctionDecisionReason),
                    fallback = result.fallbackSource ?: "音声認識結果",
                    attempts = result.correctionAttempts,
                    httpStatus = result.correctionHttpStatus,
                    failureStage = result.correctionFailureStage.orEmpty(),
                    errorClass = result.correctionErrorClass.orEmpty(),
                    responsePresent = result.correctionResponsePresent,
                    modelChanged = result.correctionModelChanged,
                    integrityResult = result.correctionIntegrityResult.orEmpty(),
                    endpoint = result.correctionEndpoint.orEmpty(),
                ),
            )
        } else if (result.correctionModelResponded) {
            correctionStatus.clearFailure()
        }

        val commit = commit(capturedTarget, result.finalText)
        if (commit == CommitResult.FAILED) {
            clip(result.finalText)
            notice(result.finalText, "直接入力できずクリップボードへ保存しました", 4_000)
            return
        }
        rememberCommitted(capturedTarget, result.finalText, commit)

        val latency = result.correctionLatencyMs?.let { "（${formatLatency(it)}）" }.orEmpty()
        val state = when {
            result.correctionBypassed -> "補正なしで入力しました"
            result.correctionError != null && result.deterministicFormattingChanged ->
                "文章補正に失敗$latency: ${short(result.correctionError)} — 音声認識結果に指定整形だけ適用"
            result.correctionError != null ->
                "文章補正に失敗$latency: ${short(result.correctionError)} — 音声認識結果を入力"
            !result.correctionAccepted ->
                "補正結果を採用できませんでした$latency: ${integrityReason(result.correctionDecisionReason)} — 音声認識結果を入力"
            result.correctionModelChanged -> "文章を補正して入力しました$latency"
            result.correctionModelResponded && result.deterministicFormattingChanged ->
                "語句はそのまま、指定した整形を適用しました$latency"
            result.correctionModelResponded -> "補正モデルは変更不要と判断しました$latency"
            result.deterministicFormattingChanged -> "指定した整形を適用しました"
            result.finalAsrError != null -> "確定時の再認識を使えず、リアルタイム認識結果を入力しました"
            else -> "入力しました"
        }
        val suffix = if (commit == CommitResult.UNVERIFIED) "（入力欄での反映確認不可）" else ""
        val delay = when {
            correctionFailed -> 7_500L
            result.correctionModelResponded && !result.correctionModelChanged -> 3_200L
            commit == CommitResult.UNVERIFIED -> 3_200L
            result.correctionModelChanged -> 2_000L
            else -> 1_200L
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
                notice(text, "$state（入力欄での反映確認不可）", 2_800)
            }
            CommitResult.VERIFIED -> {
                rememberCommitted(capturedTarget, text, result)
                notice(text, state, 900)
            }
        }
    }

    private fun recoverResolved(
        capturedTarget: Target?,
        text: String,
        failure: Throwable,
        effective: AppSettings?,
    ) {
        val detail = failure.message?.takeIf(String::isNotBlank)?.let(::short)
            ?: "確定処理を完了できませんでした"
        effective?.let { current ->
            val backend = CorrectionBackendResolver.resolve(
                current,
                GemmaModelSource.isAvailable(this, current.gemmaModelPath),
            )
            correctionStatus.saveFailure(
                LastCorrectionFailure(
                    occurredAtMs = System.currentTimeMillis(),
                    provider = providerLabel(current),
                    model = current.byokModel,
                    reasoning = if (current.byokModel.isBlank()) "" else ReasoningCapabilities.label(
                        current.byokEndpoint,
                        current.byokModel,
                        current.reasoningEffort,
                    ),
                    latencyMs = null,
                    reason = detail,
                    fallback = "音声認識結果",
                    attempts = if (backend == CorrectionBackend.NONE) 0 else 1,
                    failureStage = if (failure is TimeoutException) "finalization-watchdog" else "finalization",
                    errorClass = failure.javaClass.simpleName,
                    responsePresent = false,
                    modelChanged = false,
                    integrityResult = "not-run",
                    endpoint = failureEndpoint(current, backend),
                ),
            )
        }
        val result = commit(capturedTarget, text)
        if (result != CommitResult.FAILED) {
            rememberCommitted(capturedTarget, text, result)
            val suffix = if (result == CommitResult.UNVERIFIED) "（入力欄での反映確認不可）" else ""
            notice(text, "文章補正を完了できませんでした: $detail — 音声認識結果を入力$suffix", 7_500)
        } else {
            clip(text)
            notice(text, "確定処理エラー: $detail — クリップボードへ保存", 7_500)
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

    private fun correctionProgressLabel(effective: AppSettings): String {
        val backend = CorrectionBackendResolver.resolve(
            effective,
            GemmaModelSource.isAvailable(this, effective.gemmaModelPath),
        )
        return when (backend) {
            CorrectionBackend.NONE -> "文章を確定しています"
            CorrectionBackend.GEMMA -> "端末内Gemmaで文章を補正しています"
            CorrectionBackend.BYOK -> {
                val reasoning = ReasoningCapabilities.label(
                    effective.byokEndpoint,
                    effective.byokModel,
                    effective.reasoningEffort,
                )
                "${effective.byokModel} で文章を補正しています（$reasoning）"
            }
        }
    }

    private fun finalizationWatchdogMs(effective: AppSettings): Long {
        val finalRecognitionBudget = if (effective.finalAsrMode == FinalAsrMode.REAZON_SPEECH) 32_000L else 2_000L
        val backend = CorrectionBackendResolver.resolve(
            effective,
            GemmaModelSource.isAvailable(this, effective.gemmaModelPath),
        )
        val correctionBudget = if (backend == CorrectionBackend.NONE) 2_000L else {
            CorrectionTimeoutPolicy.correctionTimeoutMs(effective.reasoningEffort) + 3_000L
        }
        return finalRecognitionBudget + correctionBudget + 4_000L
    }

    private fun providerLabel(effective: AppSettings): String {
        val backend = CorrectionBackendResolver.resolve(
            effective,
            GemmaModelSource.isAvailable(this, effective.gemmaModelPath),
        )
        return when (backend) {
            CorrectionBackend.NONE -> "none"
            CorrectionBackend.GEMMA -> "on-device"
            CorrectionBackend.BYOK -> CloudCorrectorFactory.protocolFor(effective.byokEndpoint).name.lowercase()
        }
    }

    private fun failureEndpoint(effective: AppSettings, backend: CorrectionBackend): String = when (backend) {
        CorrectionBackend.NONE -> ""
        CorrectionBackend.GEMMA -> if (GemmaModelSource.isExternal(effective.gemmaModelPath)) {
            "external-document"
        } else {
            "on-device"
        }
        CorrectionBackend.BYOK -> runCatching {
            ByokEndpointResolver.resolve(effective.byokEndpoint).generationUrl.substringBefore('?').substringBefore('#')
        }.getOrElse { effective.byokEndpoint.substringBefore('?').substringBefore('#').take(220) }
    }

    private fun integrityReason(reason: String?): String = when (reason) {
        "empty-output" -> "補正モデルが空の文章を返しました"
        "output-expanded-too-much" -> "補正結果が元の発言より異常に長くなりました"
        "output-lost-too-much" -> "補正結果から元の発言が大量に欠落しました"
        "word-changes-disabled" -> "『語句は直さない』設定なのに語句が変更されました"
        null -> "補正結果の形式が不正でした"
        else -> "補正結果の形式を確認できませんでした ($reason)"
    }

    private fun formatLatency(ms: Long): String = if (ms < 1_000L) "${ms}ms" else "%.1f秒".format(ms / 1_000.0)

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
        cancelAllPendingFinalizations()
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
        cancelAllPendingFinalizations()
    }

    private fun cancelAllPendingFinalizations() {
        finalizationTasks.values.forEach { it.cancel(true) }
        finalizationTasks.clear()
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

    private fun short(value: String) = value.replace(Regex("\\s+"), " ").trim().take(120)

    private enum class CommitResult { VERIFIED, UNVERIFIED, FAILED }

    private data class FinalizationResolution(
        val target: Target?,
        val raw: String,
        val result: FinalizationResult? = null,
        val failure: Throwable? = null,
        val settings: AppSettings? = null,
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
}
