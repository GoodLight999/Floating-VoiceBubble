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
import com.goodlight.floatingvoicebubble.BuildConfig
import com.goodlight.floatingvoicebubble.CorrectionMode
import com.goodlight.floatingvoicebubble.FinalAsrMode
import com.goodlight.floatingvoicebubble.GemmaVariant
import com.goodlight.floatingvoicebubble.RecognitionMode
import com.goodlight.floatingvoicebubble.SettingsStore
import com.goodlight.floatingvoicebubble.accessibility.VoiceBubbleAccessibilityService
import com.goodlight.floatingvoicebubble.correction.CloudCorrectorFactory
import com.goodlight.floatingvoicebubble.correction.CorrectionBackend
import com.goodlight.floatingvoicebubble.correction.CorrectionBackendResolver
import com.goodlight.floatingvoicebubble.correction.CorrectionGuard
import com.goodlight.floatingvoicebubble.correction.CorrectionRequest
import com.goodlight.floatingvoicebubble.correction.GemmaCorrector
import com.goodlight.floatingvoicebubble.dictionary.PersonalDictionary
import com.goodlight.floatingvoicebubble.model.AsrModelStore
import com.goodlight.floatingvoicebubble.model.FinalAsrModelStore
import com.goodlight.floatingvoicebubble.speech.RecognitionBackend
import com.goodlight.floatingvoicebubble.speech.RecognitionBackendResolver
import com.goodlight.floatingvoicebubble.speech.SherpaFinalAsrEngine
import com.goodlight.floatingvoicebubble.speech.SherpaStreamingEngine
import com.goodlight.floatingvoicebubble.trace.SessionTraceStore
import com.k2fsa.sherpa.onnx.VersionInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

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
        .put("schema", 4)
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
        val settings = settingsStore.load()
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
            fail("offline-asr-readiness", "offline mode is enabled but no valid true-streaming ASR model is selected")
        } else {
            pass("offline-asr-readiness", if (settings.offlineMode) "ready" else "offline mode inactive")
        }

        results += when {
            settings.finalAsrMode == FinalAsrMode.LIVE_RESULT -> pass("final-asr-readiness", "live result selected")
            finalAsrModel == null -> fail("final-asr-readiness", "ReazonSpeech selected but model is missing or invalid")
            else -> pass("final-asr-readiness", "${finalAsrModel.family}; size=${finalAsrModel.totalBytes} bytes")
        }

        results += probe("dictionary-db") {
            PersonalDictionary(appContext).use { dictionary ->
                val count = dictionary.count()
                "SQLite open/read OK; entries=$count"
            }
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

        val modelFile = settings.gemmaModelPath.takeIf(String::isNotBlank)?.let(::File)
        val gemmaAvailable = modelFile?.isFile == true && modelFile.length() >= MIN_GEMMA_BYTES
        results += when {
            modelFile == null -> skip("gemma-model", "not configured")
            !modelFile.isFile -> fail("gemma-model", "configured path is missing")
            modelFile.length() < MIN_GEMMA_BYTES -> fail("gemma-model", "model file is unexpectedly small")
            settings.gemmaVariant == GemmaVariant.UNKNOWN -> warn(
                "gemma-model",
                "present; variant is not declared E2B/E4B; size=${modelFile.length()} bytes; sha256=${sha256Prefix(modelFile)}",
            )
            else -> pass(
                "gemma-model",
                "variant=${settings.gemmaVariant}; size=${modelFile.length()} bytes; sha256=${sha256Prefix(modelFile)}",
            )
        }

        val selectedOfflineCorrection = CorrectionBackendResolver.resolve(settings.copy(offlineMode = true), gemmaAvailable)
        results += when {
            !settings.offlineMode -> pass("offline-correction-readiness", "offline mode inactive")
            settings.correctionMode == CorrectionMode.GEMMA && !gemmaAvailable ->
                fail("offline-correction-readiness", "Gemma correction is explicitly selected but the model is missing")
            selectedOfflineCorrection == CorrectionBackend.GEMMA ->
                pass("offline-correction-readiness", "Gemma available; offline correction ready")
            settings.correctionMode == CorrectionMode.NONE ->
                pass("offline-correction-readiness", "correction explicitly disabled; no Gemma model required")
            else ->
                pass("offline-correction-readiness", "cloud blocked; no Gemma available, so correction will be disabled")
        }

        results += probe("offline-cloud-block") {
            val forcedOffline = settings.copy(
                offlineMode = true,
                correctionMode = CorrectionMode.BYOK,
                byokModel = "diagnostic-cloud-model",
            )
            val backendWithGemma = CorrectionBackendResolver.resolve(forcedOffline, gemmaAvailable = true)
            val backendWithoutGemma = CorrectionBackendResolver.resolve(forcedOffline, gemmaAvailable = false)
            val explicitNone = CorrectionBackendResolver.resolve(
                forcedOffline.copy(correctionMode = CorrectionMode.NONE),
                gemmaAvailable = true,
            )
            check(backendWithGemma == CorrectionBackend.GEMMA) { "forced offline mode selected $backendWithGemma with Gemma" }
            check(backendWithoutGemma == CorrectionBackend.NONE) { "forced offline mode selected $backendWithoutGemma without Gemma" }
            check(explicitNone == CorrectionBackend.NONE) { "explicit NONE was overridden by $explicitNone" }
            "policy verified: offline never selects cloud; BYOK→Gemma/NONE and explicit NONE stays NONE"
        }

        results += probeCorrectionGuard()

        if (includeExternalProbes) {
            if (streamingModel != null) {
                results += probe("streaming-asr-model-load") {
                    SherpaStreamingEngine.preload(streamingModel)
                    "Sherpa OnlineRecognizer loaded model successfully"
                }
            } else {
                results += skip("streaming-asr-model-load", "model not configured")
            }

            if (finalAsrModel != null) {
                results += probe("final-asr-model-load") {
                    SherpaFinalAsrEngine.preload(finalAsrModel)
                    "Sherpa OfflineRecognizer loaded ReazonSpeech successfully"
                }
            } else {
                results += skip("final-asr-model-load", "model not configured")
            }

            if (gemmaAvailable) {
                results += probe("gemma-inference") {
                    val raw = "今日はがんだむ見に行く"
                    val output = GemmaCorrector(appContext, requireNotNull(modelFile).absolutePath, settings.gemmaBackend)
                        .correct(fixedCorrectionRequest(raw))
                    check(output.isNotBlank()) { "Gemma returned empty output" }
                    val decision = CorrectionGuard.choose(raw, output)
                    "response received; guard=${if (decision.accepted) "accepted" else "rejected"}"
                }
            } else {
                results += skip("gemma-inference", "model not configured or invalid")
            }

            val shouldProbeCloud = !settings.offlineMode &&
                settings.correctionMode != CorrectionMode.NONE &&
                settings.byokModel.isNotBlank()
            if (shouldProbeCloud) {
                results += probe("byok-live-request") {
                    val raw = "今日はがんだむ見に行く"
                    val output = CloudCorrectorFactory.create(
                        settings.byokEndpoint,
                        settings.byokModel,
                        settingsStore.apiKey(),
                    ).correct(fixedCorrectionRequest(raw))
                    check(output.isNotBlank()) { "BYOK returned empty output" }
                    val decision = CorrectionGuard.choose(raw, output)
                    "provider=${CloudCorrectorFactory.protocolFor(settings.byokEndpoint)}; guard=${if (decision.accepted) "accepted" else "rejected"}"
                }
            } else {
                val reason = when {
                    settings.offlineMode -> "offline mode"
                    settings.correctionMode == CorrectionMode.NONE -> "correction disabled"
                    else -> "BYOK model not configured"
                }
                results += skip("byok-live-request", reason)
            }
        } else {
            results += skip("streaming-asr-model-load", "external probes disabled")
            results += skip("final-asr-model-load", "external probes disabled")
            results += skip("gemma-inference", "external probes disabled")
            results += skip("byok-live-request", "external probes disabled")
        }

        val finished = System.currentTimeMillis()
        return DiagnosticReport(started, finished, results).also(::persistLatest)
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

    private fun probeCorrectionGuard(): DiagnosticItem = probe("correction-guard") {
        val accepted = CorrectionGuard.choose("今日はがんだむ見に行く", "今日はガンダム見に行く。")
        check(accepted.accepted && accepted.text.contains("ガンダム")) { "minimal correction vector rejected" }
        val raw = "これマジでやばい、あとで見る"
        val rejected = CorrectionGuard.choose(raw, "これは非常に興味深い内容ですので、後ほど詳しく確認いたします。")
        check(!rejected.accepted && rejected.text == raw) { "register-changing rewrite was not blocked" }
        "minimal-edit accept/rewrite reject vectors passed"
    }

    private fun fixedCorrectionRequest(raw: String): CorrectionRequest = CorrectionRequest(
        rawTranscript = raw,
        alternatives = listOf(raw, "今日はガンダム見に行く"),
        surroundingContext = "",
        dictionaryTerms = emptyList(),
    )

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
        (error.message ?: error.javaClass.simpleName).replace(Regex("(?i)(api[_ -]?key|bearer)\\s*[:=]?\\s*\\S+"), "$1 <redacted>")

    private fun sha256Prefix(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().take(6).joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    companion object {
        private const val MIN_GEMMA_BYTES = 1L * 1024 * 1024
    }
}
