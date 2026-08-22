package com.goodlight.floatingvoicebubble.trace

import android.accessibilityservice.AccessibilityService
import android.content.Context
import com.goodlight.floatingvoicebubble.speech.RecognitionOutcome
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class FinalizationTrace(
    val outcome: RecognitionOutcome,
    val finalText: String,
    val correctorId: String,
    val correctionAccepted: Boolean,
    val correctionDistance: Double,
    val correctionError: String? = null,
    val correctionInputText: String = outcome.rawTranscript,
    val modelOutputText: String? = null,
    val correctionAttempted: Boolean = false,
    val correctionChanged: Boolean = finalText != correctionInputText,
    val correctionBypassed: Boolean = false,
    val correctionDecisionReason: String? = null,
    val correctionModelResponded: Boolean = modelOutputText != null,
    val correctionModelChanged: Boolean = false,
    val deterministicFormattingChanged: Boolean = false,
    val correctionProvider: String? = null,
    val correctionModel: String? = null,
    val correctionReasoning: String? = null,
    val correctionLatencyMs: Long? = null,
    val correctionAttempts: Int = if (correctionAttempted) 1 else 0,
    val correctionHttpStatus: Int? = null,
    val correctionFailureStage: String? = null,
    val correctionErrorClass: String? = null,
    val correctionResponsePresent: Boolean = correctionModelResponded,
    val correctionEndpoint: String? = null,
    val correctionIntegrityResult: String? = null,
    val fallbackSource: String? = null,
    val finalAsrId: String = "live-result",
    val finalAsrLatencyMs: Long? = null,
    val finalAsrRtf: Double? = null,
    val finalAsrError: String? = null,
    val finishedAtMs: Long = System.currentTimeMillis(),
)

class SessionTraceStore(context: Context) {
    private val appContext = context.applicationContext
    val audioDir: File = File(appContext.noBackupFilesDir, "session-traces").apply { mkdirs() }

    init {
        migrateLegacyDirectory()
        if (context is AccessibilityService) cleanupOrphans()
    }

    fun save(trace: FinalizationTrace, enabled: Boolean) {
        trace.outcome.audioFile?.let(::awaitAudioFinalization)
        if (!enabled) {
            trace.outcome.audioFile?.delete()
            return
        }
        val json = JSONObject()
            .put("schema", 6)
            .put("sessionId", trace.outcome.sessionId)
            .put("liveRecognizer", trace.outcome.recognizerKind)
            .put("corrector", trace.correctorId)
            .put("startedAtMs", trace.outcome.startedAtMs)
            .put("recognitionFinishedAtMs", trace.outcome.recognitionFinishedAtMs)
            .put("finishedAtMs", trace.finishedAtMs)
            .put("recognitionLatencyMs", trace.outcome.recognitionFinishedAtMs - trace.outcome.startedAtMs)
            .put("totalLatencyMs", trace.finishedAtMs - trace.outcome.startedAtMs)
            .put("liveRawTranscript", trace.outcome.rawTranscript)
            .put("liveAlternatives", JSONArray(trace.outcome.alternatives))
            .put("finalAsr", trace.finalAsrId)
            .put("finalAsrText", trace.correctionInputText)
            .put("finalAsrLatencyMs", trace.finalAsrLatencyMs ?: JSONObject.NULL)
            .put("finalAsrRtf", trace.finalAsrRtf ?: JSONObject.NULL)
            .put("finalAsrError", trace.finalAsrError ?: JSONObject.NULL)
            .put("rawTranscript", trace.correctionInputText)
            .put("alternatives", JSONArray(listOf(trace.correctionInputText) + trace.outcome.alternatives))
            .put("modelOutput", trace.modelOutputText ?: JSONObject.NULL)
            .put("finalText", trace.finalText)
            .put("correctionAttempted", trace.correctionAttempted)
            .put("correctionAttempts", trace.correctionAttempts)
            .put("correctionProvider", trace.correctionProvider ?: JSONObject.NULL)
            .put("correctionModel", trace.correctionModel ?: JSONObject.NULL)
            .put("correctionReasoning", trace.correctionReasoning ?: JSONObject.NULL)
            .put("correctionLatencyMs", trace.correctionLatencyMs ?: JSONObject.NULL)
            .put("correctionHttpStatus", trace.correctionHttpStatus ?: JSONObject.NULL)
            .put("correctionFailureStage", trace.correctionFailureStage ?: JSONObject.NULL)
            .put("correctionErrorClass", trace.correctionErrorClass ?: JSONObject.NULL)
            .put("correctionResponsePresent", trace.correctionResponsePresent)
            .put("correctionEndpoint", trace.correctionEndpoint ?: JSONObject.NULL)
            .put("correctionIntegrityResult", trace.correctionIntegrityResult ?: JSONObject.NULL)
            .put("fallbackSource", trace.fallbackSource ?: JSONObject.NULL)
            .put("correctionModelResponded", trace.correctionModelResponded)
            .put("correctionModelChanged", trace.correctionModelChanged)
            .put("deterministicFormattingChanged", trace.deterministicFormattingChanged)
            .put("correctionChanged", trace.correctionChanged)
            .put("correctionBypassed", trace.correctionBypassed)
            .put("correctionAccepted", trace.correctionAccepted)
            .put("correctionDistance", trace.correctionDistance)
            .put("correctionDecisionReason", trace.correctionDecisionReason ?: JSONObject.NULL)
            .put("correctionError", trace.correctionError ?: JSONObject.NULL)
            .put("audioFile", trace.outcome.audioFile?.name ?: JSONObject.NULL)
        val target = File(audioDir, "${trace.outcome.sessionId}.json")
        val temp = File(audioDir, ".${trace.outcome.sessionId}.json.part")
        temp.writeText(json.toString(2), Charsets.UTF_8)
        if (target.exists()) target.delete()
        check(temp.renameTo(target)) { "session trace metadata could not be committed" }
        prune()
    }

    fun recentSessionMetadata(limit: Int = 30): List<File> = audioDir
        .listFiles { file -> file.extension == "json" }
        ?.sortedByDescending(File::lastModified)
        ?.take(limit.coerceAtLeast(0))
        .orEmpty()

    internal fun cleanupOrphans() {
        val files = audioDir.listFiles().orEmpty()
        val committedIds = files.asSequence()
            .filter { it.isFile && it.extension == "json" && !it.name.endsWith(".benchmark.json") }
            .map { it.nameWithoutExtension }
            .toHashSet()
        files.forEach { file ->
            val delete = when {
                file.extension == "pcm" -> true
                file.name.endsWith(".part") -> true
                file.extension == "wav" && file.nameWithoutExtension !in committedIds -> true
                else -> false
            }
            if (delete) runCatching { file.delete() }
        }
    }

    private fun migrateLegacyDirectory() {
        val legacy = File(appContext.filesDir, "session-traces")
        if (!legacy.isDirectory || legacy.canonicalPath == audioDir.canonicalPath) return
        legacy.listFiles().orEmpty().forEach { source ->
            val destination = File(audioDir, source.name)
            if (destination.exists()) return@forEach
            if (!source.renameTo(destination)) {
                runCatching {
                    source.inputStream().use { input -> destination.outputStream().use { output -> input.copyTo(output) } }
                    source.delete()
                }
            }
        }
        legacy.delete()
    }

    private fun awaitAudioFinalization(file: File) {
        val deadline = System.nanoTime() + 1_500_000_000L
        var previousSize = -1L
        var stableSamples = 0
        while (System.nanoTime() < deadline) {
            val size = if (file.isFile) file.length() else -1L
            if (size > 44L && size == previousSize) {
                stableSamples++
                if (stableSamples >= 2) return
            } else stableSamples = 0
            previousSize = size
            Thread.sleep(30)
        }
    }

    private fun prune(maxSessions: Int = 30) {
        recentSessionMetadata(Int.MAX_VALUE).drop(maxSessions).forEach { json ->
            val id = json.nameWithoutExtension
            json.delete()
            File(audioDir, "$id.wav").delete()
            File(audioDir, "$id.benchmark.json").delete()
        }
    }
}
