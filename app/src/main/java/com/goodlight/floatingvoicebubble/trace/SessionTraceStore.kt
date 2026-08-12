package com.goodlight.floatingvoicebubble.trace

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
    val finishedAtMs: Long = System.currentTimeMillis(),
)

class SessionTraceStore(context: Context) {
    val audioDir: File = File(context.filesDir, "session-traces").apply { mkdirs() }

    fun save(trace: FinalizationTrace, enabled: Boolean) {
        trace.outcome.audioFile?.let(::awaitAudioFinalization)
        if (!enabled) { trace.outcome.audioFile?.delete(); return }
        val json = JSONObject()
            .put("schema", 1).put("sessionId", trace.outcome.sessionId).put("recognizer", trace.outcome.recognizerKind)
            .put("corrector", trace.correctorId).put("startedAtMs", trace.outcome.startedAtMs)
            .put("recognitionFinishedAtMs", trace.outcome.recognitionFinishedAtMs).put("finishedAtMs", trace.finishedAtMs)
            .put("recognitionLatencyMs", trace.outcome.recognitionFinishedAtMs - trace.outcome.startedAtMs)
            .put("totalLatencyMs", trace.finishedAtMs - trace.outcome.startedAtMs).put("rawTranscript", trace.outcome.rawTranscript)
            .put("alternatives", JSONArray(trace.outcome.alternatives)).put("finalText", trace.finalText)
            .put("correctionAccepted", trace.correctionAccepted).put("correctionDistance", trace.correctionDistance)
            .put("correctionError", trace.correctionError ?: JSONObject.NULL).put("audioFile", trace.outcome.audioFile?.name ?: JSONObject.NULL)
        val target = File(audioDir, "${trace.outcome.sessionId}.json"); val temp = File(audioDir, ".${trace.outcome.sessionId}.json.part")
        temp.writeText(json.toString(2), Charsets.UTF_8); if (target.exists()) target.delete(); temp.renameTo(target); prune()
    }

    private fun awaitAudioFinalization(file: File) {
        val deadline = System.nanoTime() + 1_500_000_000L
        var previousSize = -1L; var stableSamples = 0
        while (System.nanoTime() < deadline) {
            val size = if (file.isFile) file.length() else -1L
            if (size > 44L && size == previousSize) { stableSamples++; if (stableSamples >= 2) return } else stableSamples = 0
            previousSize = size; Thread.sleep(30)
        }
    }

    private fun prune(maxSessions: Int = 30) {
        val jsonFiles = audioDir.listFiles { file -> file.extension == "json" }?.sortedByDescending(File::lastModified).orEmpty()
        jsonFiles.drop(maxSessions).forEach { json -> val id = json.nameWithoutExtension; json.delete(); File(audioDir, "$id.wav").delete() }
    }
}
