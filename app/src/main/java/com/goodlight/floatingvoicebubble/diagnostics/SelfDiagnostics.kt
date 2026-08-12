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
import com.goodlight.floatingvoicebubble.SettingsStore
import com.goodlight.floatingvoicebubble.accessibility.VoiceBubbleAccessibilityService
import com.goodlight.floatingvoicebubble.correction.CorrectionBackend
import com.goodlight.floatingvoicebubble.correction.CorrectionBackendResolver
import com.goodlight.floatingvoicebubble.correction.CorrectionGuard
import com.goodlight.floatingvoicebubble.correction.CorrectionRequest
import com.goodlight.floatingvoicebubble.correction.GemmaCorrector
import com.goodlight.floatingvoicebubble.correction.OpenAiCompatibleCorrector
import com.goodlight.floatingvoicebubble.dictionary.PersonalDictionary
import com.goodlight.floatingvoicebubble.trace.SessionTraceStore
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
        .put("schema", 1)
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
            "actual no-backup trace directory read/write/delete OK"
        }

        val modelFile = settings.gemmaModelPath.takeIf(String::isNotBlank)?.let(::File)
        results += when {
            modelFile == null -> skip("gemma-model", "not configured")
            !modelFile.isFile -> fail("gemma-model", "configured path is missing")
            modelFile.length() < 1_000_000L -> fail("gemma-model", "model file is unexpectedly small")
            else -> pass(
                "gemma-model",
                "present; size=${modelFile.length()} bytes; sha256=${sha256Prefix(modelFile)}",
            )
        }

        results += probe("offline-cloud-block") {
            val forcedOffline = settings.copy(
                offlineMode = true,
                correctionMode = CorrectionMode.BYOK,
                byokModel = "diagnostic-cloud-model",
            )
            val backend = CorrectionBackendResolver.resolve(forcedOffline, gemmaAvailable = true)
            check(backend == CorrectionBackend.GEMMA) { "forced offline mode selected $backend" }
            "policy verified: explicit BYOK resolves to GEMMA when offline"
        }

        results += probeCorrectionGuard()

        if (includeExternalProbes) {
            if (modelFile?.isFile == true) {
                results += probe("gemma-inference") {
                    val raw = "今日はがんだむ見に行く"
                    val output = GemmaCorrector(appContext, modelFile.absolutePath, settings.gemmaBackend)
                        .correct(fixedCorrectionRequest(raw))
                    check(output.isNotBlank()) { "Gemma returned empty output" }
                    val decision = CorrectionGuard.choose(raw, output)
                    "response received; guard=${if (decision.accepted) "accepted" else "rejected"}"
                }
            } else {
                results += skip("gemma-inference", "model not configured")
            }

            val shouldProbeCloud = !settings.offlineMode &&
                settings.correctionMode != CorrectionMode.NONE &&
                settings.byokModel.isNotBlank()
            if (shouldProbeCloud) {
                results += probe("byok-live-request") {
                    val raw = "今日はがんだむ見に行く"
                    val output = OpenAiCompatibleCorrector(
                        settings.byokEndpoint,
                        settings.byokModel,
                        settingsStore.apiKey(),
                    ).correct(fixedCorrectionRequest(raw))
                    check(output.isNotBlank()) { "BYOK returned empty output" }
                    val decision = CorrectionGuard.choose(raw, output)
                    "provider request succeeded; guard=${if (decision.accepted) "accepted" else "rejected"}"
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
}
