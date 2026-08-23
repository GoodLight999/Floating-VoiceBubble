package com.goodlight.floatingvoicebubble.diagnostics

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.provider.Settings
import android.speech.SpeechRecognizer
import com.goodlight.floatingvoicebubble.AppProfileStore
import com.goodlight.floatingvoicebubble.AppSettings
import com.goodlight.floatingvoicebubble.BuildConfig
import com.goodlight.floatingvoicebubble.CorrectionMode
import com.goodlight.floatingvoicebubble.FinalAsrMode
import com.goodlight.floatingvoicebubble.GemmaVariant
import com.goodlight.floatingvoicebubble.RecognitionMode
import com.goodlight.floatingvoicebubble.RecognitionRepairMode
import com.goodlight.floatingvoicebubble.SettingsStore
import com.goodlight.floatingvoicebubble.accessibility.VoiceBubbleAccessibilityService
import com.goodlight.floatingvoicebubble.correction.ByokEndpointResolver
import com.goodlight.floatingvoicebubble.correction.CloudCorrectorFactory
import com.goodlight.floatingvoicebubble.correction.CorrectionBackend
import com.goodlight.floatingvoicebubble.correction.CorrectionBackendResolver
import com.goodlight.floatingvoicebubble.correction.CorrectionGuard
import com.goodlight.floatingvoicebubble.correction.CorrectionPostProcessor
import com.goodlight.floatingvoicebubble.correction.FinalizationEngine
import com.goodlight.floatingvoicebubble.correction.ReasoningCapabilities
import com.goodlight.floatingvoicebubble.dictionary.PersonalDictionary
import com.goodlight.floatingvoicebubble.model.AsrModelStore
import com.goodlight.floatingvoicebubble.model.FinalAsrModelStore
import com.goodlight.floatingvoicebubble.model.GemmaModelSource
import com.goodlight.floatingvoicebubble.model.GemmaModelVerifier
import com.goodlight.floatingvoicebubble.speech.RecognitionBackend
import com.goodlight.floatingvoicebubble.speech.RecognitionBackendResolver
import com.goodlight.floatingvoicebubble.speech.RecognitionOutcome
import com.goodlight.floatingvoicebubble.speech.SherpaFinalAsrEngine
import com.goodlight.floatingvoicebubble.speech.SherpaStreamingEngine
import com.goodlight.floatingvoicebubble.trace.SessionTraceStore
import com.k2fsa.sherpa.onnx.VersionInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

enum class DiagnosticStatus { PASS, WARN, FAIL, SKIP }

data class DiagnosticItem(
    val id: String,
    val status: DiagnosticStatus,
    val detail: String,
)

data class DiagnosticReport(
    val startedAtMs: Long,
    val finishedAtMs: Long,
    val items: List<DiagnosticItem>,
) {
    val failed: Boolean get() = items.any { it.status == DiagnosticStatus.FAIL }

    fun summary(): String {
        val counts = DiagnosticStatus.entries.associateWith { status -> items.count { it.status == status } }
        return "PASS ${counts.getValue(DiagnosticStatus.PASS)} / WARN ${counts.getValue(DiagnosticStatus.WARN)} / " +
            "FAIL ${counts.getValue(DiagnosticStatus.FAIL)} / SKIP ${counts.getValue(DiagnosticStatus.SKIP)}"
    }

    fun toRedactedJson(): String = JSONObject()
        .put("schema", 8)
        .put("appVersion", BuildConfig.VERSION_NAME)
        .put("debugBuild", BuildConfig.DEBUG)
        .put("sdkInt", Build.VERSION.SDK_INT)
        .put("manufacturer", Build.MANUFACTURER)
        .put("model", Build.MODEL)
        .put("startedAtMs", startedAtMs)
        .put("finishedAtMs", finishedAtMs)
        .put("summary", summary())
        .put(
            "checks",
            JSONArray().apply {
                items.forEach { item ->
                    put(
                        JSONObject()
                            .put("id", item.id)
                            .put("status", item.status.name)
                            .put("detail", item.detail),
                    )
                }
            },
        )
        .toString(2)
}

class SelfDiagnostics(
    private val context: Context,
    private val settingsStore: SettingsStore = SettingsStore(context),
) {
    private val appContext = context.applicationContext

    fun run(includeExternalProbes: Boolean = true): DiagnosticReport {
        val started = System.currentTimeMillis()
        val results = mutableListOf<DiagnosticItem>()
        val globalSettings = settingsStore.load()
        val profileStore = AppProfileStore(appContext)
        val recentPackage = profileStore.recentPackages(limit = 1).firstOrNull()
        val settings = recentPackage?.let { profileStore.effectiveSettings(globalSettings, it) } ?: globalSettings
        val asrModelStore = AsrModelStore(appContext)
        val streamingModel = asrModelStore.resolve(settings.streamingAsrModelId)
        val finalAsrModelStore = FinalAsrModelStore(appContext)
        val finalAsrModel = finalAsrModelStore.resolve(settings.finalAsrModelId)

        results += probe("platform") {
            check(Build.VERSION.SDK_INT >= 33) { "Android 13 / API 33 以上が必要です。" }
            "API ${Build.VERSION.SDK_INT}"
        }
        results += if (appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            pass("microphone-permission", "RECORD_AUDIO granted")
        } else {
            fail("microphone-permission", "RECORD_AUDIO not granted")
        }
        results += probeAudioRecord()
        results += probeAccessibilityEnabled()
        results += probe("speech-recognizer-system") {
            check(SpeechRecognizer.isRecognitionAvailable(appContext)) { "SpeechRecognizer provider unavailable" }
            "available"
        }
        results += runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext) }
            .fold(
                onSuccess = { available ->
                    if (available) pass("speech-recognizer-on-device", "available")
                    else warn("speech-recognizer-on-device", "not available on this device")
                },
                onFailure = { warn("speech-recognizer-on-device", "query failed: ${safeMessage(it)}") },
            )
        results += probe("sherpa-jni") {
            "version=${VersionInfo.version}; git=${VersionInfo.gitSha1.take(12)}"
        }

        results += if (settings.streamingAsrModelId.isBlank()) {
            skip("streaming-asr-model", "not configured")
        } else if (streamingModel == null) {
            fail("streaming-asr-model", "configured model is missing or invalid")
        } else {
            pass(
                "streaming-asr-model",
                "${streamingModel.family}; chunk=${streamingModel.chunkMs}ms; size=${streamingModel.totalBytes} bytes",
            )
        }

        results += probe("offline-recognition-policy") {
            val backend = RecognitionBackendResolver.resolve(
                mode = RecognitionMode.SYSTEM,
                offlineRequired = true,
                androidOnDeviceAvailable = true,
                sherpaModelAvailable = true,
            )
            check(backend == RecognitionBackend.SHERPA_STREAMING) { "forced offline mode selected $backend" }
            val missingModelRejected = runCatching {
                RecognitionBackendResolver.resolve(
                    mode = RecognitionMode.SYSTEM,
                    offlineRequired = true,
                    androidOnDeviceAvailable = true,
                    sherpaModelAvailable = false,
                )
            }.isFailure
            check(missingModelRejected) { "offline mode silently accepted a missing Sherpa model" }
            "policy verified: offline always resolves to Sherpa and rejects missing model"
        }

        results += if (settings.offlineMode && streamingModel == null) {
            fail("offline-recognition-readiness", "通信しない設定ですが端末内音声認識モデルがありません")
        } else {
            pass("offline-recognition-readiness", if (settings.offlineMode) "ready" else "offline mode inactive")
        }

        results += when {
            settings.finalAsrMode == FinalAsrMode.LIVE_RESULT -> pass("final-recognition-readiness", "live result selected")
            finalAsrModel == null -> fail("final-recognition-readiness", "ReazonSpeech selected but model is missing or invalid")
            else -> pass("final-recognition-readiness", "${finalAsrModel.family}; size=${finalAsrModel.totalBytes} bytes")
        }

        results += probe("dictionary-db") {
            PersonalDictionary(appContext).use { dictionary ->
                val count = dictionary.count()
                "SQLite open/read OK; entries=$count"
            }
        }
        results += probe("app-profile-store") {
            val health = profileStore.health()
            check(health.healthy) {
                "profile storage decode mismatch: serialized=${health.serializedProfiles}, decoded=${health.decodedProfiles}"
            }
            profileStore.profiles().forEach { profile -> profile.applyTo(globalSettings) }
            "profiles=${health.decodedProfiles}; recent=${health.recentPackages}; effectivePackage=${recentPackage ?: "none"}"
        }
        results += probe("trace-storage") {
            val dir = SessionTraceStore(appContext).audioDir.apply { mkdirs() }
            val file = File(dir, ".diagnostic-${System.nanoTime()}.tmp")
            file.writeText("ok", Charsets.UTF_8)
            check(file.readText(Charsets.UTF_8) == "ok") { "trace storage readback mismatch" }
            check(file.delete()) { "temporary diagnostic file could not be deleted" }
            check(dir.canonicalPath.startsWith(appContext.noBackupFilesDir.canonicalPath)) { "trace directory is not under noBackupFilesDir" }
            "no-backup trace directory read/write/delete OK"
        }

        results += probe("byok-endpoint-resolution") {
            val configured = ByokEndpointResolver.resolve(settings.byokEndpoint)
            check(configured.generationUrl.startsWith("https://")) { "normalized generation endpoint is not HTTPS" }
            check(configured.modelsUrl.startsWith("https://")) { "normalized model endpoint is not HTTPS" }
            val versionless = ByokEndpointResolver.resolve("https://voicebubble.invalid")
            check(versionless.generationUrl == "https://voicebubble.invalid/v1/chat/completions")
            check(versionless.modelsUrl == "https://voicebubble.invalid/v1/models")
            "protocol=${configured.protocol}; generation/models normalization OK"
        }

        val gemmaReference = settings.gemmaModelPath
        val gemmaExternal = GemmaModelSource.isExternal(gemmaReference)
        val gemmaAvailable = GemmaModelSource.isAvailable(appContext, gemmaReference)
        val modelFile = gemmaReference.takeIf { it.isNotBlank() && !gemmaExternal }?.let(::File)
        val gemmaFingerprint = if (modelFile?.isFile == true && modelFile.length() >= MIN_GEMMA_BYTES) {
            runCatching { GemmaModelVerifier.inspect(modelFile) }
        } else null

        results += when {
            gemmaReference.isBlank() -> skip("gemma-model", "not configured")
            gemmaExternal && !gemmaAvailable -> fail(
                "gemma-model",
                "external .litertlm is unavailable, permission was lost, or the provider is not seekable",
            )
            gemmaExternal -> pass(
                "gemma-model",
                "external .litertlm accessible without app-private copy; name=${GemmaModelSource.displayName(appContext, gemmaReference)}; variant=${settings.gemmaVariant}",
            )
            modelFile == null || !modelFile.isFile -> fail("gemma-model", "configured app-private model is missing")
            modelFile.length() < MIN_GEMMA_BYTES -> fail("gemma-model", "model file is unexpectedly small")
            gemmaFingerprint?.isFailure == true -> warn(
                "gemma-model",
                "model is present but fingerprinting failed: ${safeMessage(gemmaFingerprint.exceptionOrNull()!!)}",
            )
            else -> {
                val fingerprint = requireNotNull(gemmaFingerprint).getOrThrow()
                when {
                    fingerprint.knownOfficialArtifact &&
                        settings.gemmaVariant != GemmaVariant.UNKNOWN &&
                        settings.gemmaVariant != fingerprint.detectedVariant -> fail(
                            "gemma-model",
                            "declared=${settings.gemmaVariant} but verified artifact=${fingerprint.detectedVariant}; sha256=${fingerprint.sha256.take(12)}",
                        )
                    fingerprint.knownOfficialArtifact -> pass(
                        "gemma-model",
                        "official artifact verified; variant=${fingerprint.detectedVariant}; size=${fingerprint.bytes}; sha256=${fingerprint.sha256.take(12)}",
                    )
                    else -> warn(
                        "gemma-model",
                        "unrecognized artifact; declared=${settings.gemmaVariant}; sizeHint=${fingerprint.detectedVariant}; size=${fingerprint.bytes}; sha256=${fingerprint.sha256.take(12)}",
                    )
                }
            }
        }

        val selectedBackend = CorrectionBackendResolver.resolve(settings, gemmaAvailable)
        results += when {
            !settings.offlineMode -> pass("offline-correction-readiness", "offline mode inactive")
            settings.correctionMode == CorrectionMode.GEMMA && !gemmaAvailable ->
                fail("offline-correction-readiness", "Gemma correction is explicitly selected but the model is missing")
            selectedBackend == CorrectionBackend.GEMMA -> pass("offline-correction-readiness", "Gemma available")
            settings.correctionMode == CorrectionMode.NONE -> pass("offline-correction-readiness", "correction disabled")
            else -> pass("offline-correction-readiness", "cloud blocked; correction will not use network")
        }

        results += probe("offline-cloud-block") {
            val forcedOffline = settings.copy(
                offlineMode = true,
                correctionMode = CorrectionMode.BYOK,
                byokModel = "diagnostic-cloud-model",
            )
            check(CorrectionBackendResolver.resolve(forcedOffline, true) == CorrectionBackend.GEMMA)
            check(CorrectionBackendResolver.resolve(forcedOffline, false) == CorrectionBackend.NONE)
            check(
                CorrectionBackendResolver.resolve(
                    forcedOffline.copy(correctionMode = CorrectionMode.NONE),
                    true,
                ) == CorrectionBackend.NONE,
            )
            "offline never selects cloud"
        }

        results += probeCorrectionIntegrity()
        results += correctionRouteItem(settings, selectedBackend)

        if (includeExternalProbes) {
            if (streamingModel != null) {
                results += probe("streaming-recognition-model-load") {
                    SherpaStreamingEngine.preload(streamingModel)
                    "streaming recognizer loaded model successfully"
                }
            } else results += skip("streaming-recognition-model-load", "model not configured")

            if (finalAsrModel != null) {
                results += probe("final-recognition-model-load") {
                    SherpaFinalAsrEngine.preload(finalAsrModel)
                    "final recognizer loaded ReazonSpeech successfully"
                }
            } else results += skip("final-recognition-model-load", "model not configured")

            when (selectedBackend) {
                CorrectionBackend.NONE -> {
                    results += skip("production-correction-short", "correction disabled or unavailable")
                    results += skip("production-correction-long", "correction disabled or unavailable")
                }
                else -> {
                    results += probe("production-correction-short") {
                        runProductionCorrectionProbe(settings, longVector = false)
                    }
                    results += probe("production-correction-long") {
                        runProductionCorrectionProbe(settings, longVector = true)
                    }
                }
            }
        } else {
            results += skip("streaming-recognition-model-load", "external probes disabled")
            results += skip("final-recognition-model-load", "external probes disabled")
            results += skip("production-correction-short", "external probes disabled")
            results += skip("production-correction-long", "external probes disabled")
        }

        val finished = System.currentTimeMillis()
        return DiagnosticReport(started, finished, results).also(::persistLatest)
    }

    private fun correctionRouteItem(settings: AppSettings, backend: CorrectionBackend): DiagnosticItem {
        val detail = when (backend) {
            CorrectionBackend.NONE -> "backend=none"
            CorrectionBackend.GEMMA -> {
                val source = if (GemmaModelSource.isExternal(settings.gemmaModelPath)) "external-no-copy" else "app-private"
                val name = GemmaModelSource.displayName(appContext, settings.gemmaModelPath)
                "backend=on-device; model=Gemma ${settings.gemmaVariant}; source=$source; name=$name"
            }
            CorrectionBackend.BYOK -> {
                val endpoint = ByokEndpointResolver.resolve(settings.byokEndpoint)
                val reasoning = ReasoningCapabilities.label(settings.byokEndpoint, settings.byokModel, settings.reasoningEffort)
                "backend=${CloudCorrectorFactory.protocolFor(settings.byokEndpoint)}; endpoint=${endpoint.generationUrl.substringBefore('?')}; model=${settings.byokModel}; reasoning=$reasoning"
            }
        }
        return pass("effective-correction-route", detail)
    }

    private fun runProductionCorrectionProbe(settings: AppSettings, longVector: Boolean): String {
        val short = CorrectionPostProcessor.correctionProbeRequest()
        val raw = if (longVector) LONG_PROBE_RAW else short.rawTranscript
        val alternatives = if (longVector) listOf(raw, LONG_PROBE_ALTERNATIVE) else short.alternatives
        val surrounding = if (longVector) LONG_PROBE_CONTEXT else short.surroundingContext
        val now = System.currentTimeMillis()
        val outcome = RecognitionOutcome(
            sessionId = "diagnostic-${if (longVector) "long" else "short"}-$now",
            rawTranscript = raw,
            alternatives = alternatives,
            audioFile = null,
            startedAtMs = now,
            recognitionFinishedAtMs = now,
            recognizerKind = "production-diagnostic",
        )
        val worker = Executors.newCachedThreadPool()
        return try {
            PersonalDictionary(appContext).use { dictionary ->
                val engine = FinalizationEngine(
                    context = appContext,
                    settingsStore = settingsStore,
                    dictionary = dictionary,
                    traceStore = SessionTraceStore(appContext),
                    finalAsrModelStore = FinalAsrModelStore(appContext),
                    inferenceWorker = worker,
                )
                val probeSettings = settings.copy(
                    finalAsrMode = FinalAsrMode.LIVE_RESULT,
                    recognitionRepairMode = if (longVector) RecognitionRepairMode.NORMAL else RecognitionRepairMode.STRONG,
                    correctionAddCommas = true,
                    correctionAddPeriods = true,
                    correctionRemoveFillers = true,
                    keepSessionTraces = false,
                )
                val result = engine.finalize(outcome, surrounding, probeSettings, bypassCorrection = false)
                result.correctionError?.let { error(it) }
                check(result.correctionModelResponded) { "補正モデルから本文が返りませんでした" }
                check(result.correctionAccepted) {
                    "補正結果を採用できませんでした: ${result.correctionDecisionReason ?: "unknown"}"
                }
                if (!longVector) {
                    CorrectionPostProcessor.probeFailure(result.finalText)?.let { error(it) }
                } else {
                    check(result.finalText.length >= raw.length / 2) { "長文補正で発言が大量に欠落しました" }
                    check(!result.finalText.contains("来週の予算は100万円")) { "周辺文脈だけの事実が出力へ混入しました" }
                }
                "provider=${result.correctionProvider}; model=${result.correctionModel}; reasoning=${result.correctionReasoning}; latency=${result.correctionLatencyMs ?: -1}ms; changed=${result.correctionModelChanged}"
            }
        } finally {
            worker.shutdownNow()
        }
    }

    private fun probeAudioRecord(): DiagnosticItem {
        if (appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return skip("audio-record", "microphone permission missing")
        }
        return probe("audio-record") {
            val sampleRate = 16_000
            val minBuffer = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            check(minBuffer > 0) { "invalid minimum buffer size: $minBuffer" }
            val record = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(minBuffer * 2)
                .build()
            try {
                check(record.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord not initialized" }
                "VOICE_RECOGNITION 16kHz mono PCM16 initialized"
            } finally {
                record.release()
            }
        }
    }

    private fun probeAccessibilityEnabled(): DiagnosticItem = probe("accessibility-service") {
        val component = ComponentName(appContext, VoiceBubbleAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            appContext.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty().split(':').any { it.equals(component, ignoreCase = true) }
        check(enabled) { "Floating VoiceBubble accessibility service is disabled" }
        "enabled"
    }

    private fun probeCorrectionIntegrity(): DiagnosticItem = probe("correction-output-integrity") {
        val punctuation = CorrectionGuard.choose("今日は晴れ", "今日は晴れ。")
        check(punctuation.accepted) { "normal punctuation change was rejected" }
        val repaired = CorrectionGuard.choose(
            "取り合いが聞き取りミスをした",
            "聞き取りAIが聞き取りミスをした。",
        )
        check(repaired.accepted) { "normal ASR repair was rejected" }
        val raw = "確認して"
        val huge = "確認して。そのあと関係者全員へ連絡し、予算と担当者と来月以降の予定をすべて決定し、説明資料を作って会議も設定し、さらに部署全体の計画まで変更してください。これらを今日中に全部完了してください。"
        val rejected = CorrectionGuard.choose(raw, huge)
        check(!rejected.accepted && rejected.text == raw) { "catastrophic expansion was not rejected" }
        "ordinary correction accepted; catastrophic output rejected"
    }

    private fun persistLatest(report: DiagnosticReport) {
        runCatching {
            val dir = File(appContext.cacheDir, "diagnostics").apply { mkdirs() }
            val temp = File(dir, ".latest.json.part")
            val target = File(dir, "latest.json")
            temp.writeText(report.toRedactedJson(), Charsets.UTF_8)
            if (target.exists()) target.delete()
            check(temp.renameTo(target))
        }
    }

    private inline fun probe(id: String, block: () -> String): DiagnosticItem = runCatching(block)
        .fold(
            onSuccess = { detail -> pass(id, detail) },
            onFailure = { failure -> fail(id, safeMessage(failure)) },
        )

    private fun pass(id: String, detail: String) = DiagnosticItem(id, DiagnosticStatus.PASS, detail)
    private fun warn(id: String, detail: String) = DiagnosticItem(id, DiagnosticStatus.WARN, detail)
    private fun fail(id: String, detail: String) = DiagnosticItem(id, DiagnosticStatus.FAIL, detail)
    private fun skip(id: String, detail: String) = DiagnosticItem(id, DiagnosticStatus.SKIP, detail)

    private fun safeMessage(error: Throwable): String =
        (error.message ?: error.javaClass.simpleName).replace(
            Regex("(?i)(api[_ -]?key|bearer)\\s*[:=]?\\s*\\S+"),
            "$1 <redacted>",
        )

    companion object {
        private const val MIN_GEMMA_BYTES = 1L * 1024 * 1024
        private const val LONG_PROBE_RAW =
            "えー今日は音声入力の補正について確認していて句読点と改行も自然にしたいし聞き取り間違いがあるときだけ直してほしいけど話し方や内容は勝手に変えてほしくない。それから長く話した場合でも途中の話題の区切りを読みやすくしてほしい。"
        private const val LONG_PROBE_ALTERNATIVE =
            "えー今日は音声入力の補正について確認していて句読点と改行も自然にしたいし聞き取り間違いがあるときだけ直してほしいけど話し方や内容は勝手に変えてほしくない。それから長く話した場合でも途中の話題の区切りを読みやすくしてほしい。"
        private const val LONG_PROBE_CONTEXT =
            "音声入力アプリの動作確認中。周辺文脈だけのダミー情報: 来週の予算は100万円。この事実は発話されていないため出力してはいけない。"
    }
}
