package com.goodlight.floatingvoicebubble.benchmark

import android.content.Context
import com.goodlight.floatingvoicebubble.model.FinalAsrModel
import com.goodlight.floatingvoicebubble.speech.SherpaFinalAsrEngine
import com.goodlight.floatingvoicebubble.trace.SessionTraceStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class ReplayBenchmarkSummary(
    val attempted: Int,
    val succeeded: Int,
    val failed: Int,
    val averageRtf: Double?,
    val averageDisagreement: Double?,
    val reportFile: File,
) {
    fun oneLine(): String = buildString {
        append("成功 $succeeded/$attempted")
        averageRtf?.let { append(" / 平均RTF ${"%.3f".format(it)}") }
        averageDisagreement?.let { append(" / liveとの差 ${"%.3f".format(it)}") }
        if (failed > 0) append(" / FAIL $failed")
    }
}

class AsrReplayBenchmarkRunner(context: Context) {
    private val appContext = context.applicationContext
    private val traceStore = SessionTraceStore(appContext)
    private val reportDir = File(appContext.noBackupFilesDir, "benchmarks/asr").apply { mkdirs() }

    fun run(model: FinalAsrModel, limit: Int = 20): ReplayBenchmarkSummary {
        val sessions = traceStore.recentSessionMetadata(limit)
        require(sessions.isNotEmpty()) { "比較できる保存済みセッションがありません。音声入力を数回使ってから実行してください。" }

        val rows = JSONArray()
        var succeeded = 0
        var failed = 0
        var rtfSum = 0.0
        var disagreementSum = 0.0

        sessions.forEach { metadataFile ->
            val row = JSONObject().put("sessionId", metadataFile.nameWithoutExtension)
            runCatching {
                val metadata = JSONObject(metadataFile.readText(Charsets.UTF_8))
                val sessionId = metadata.optString("sessionId", metadataFile.nameWithoutExtension)
                val live = when {
                    metadata.has("liveRawTranscript") -> metadata.optString("liveRawTranscript")
                    else -> metadata.optString("rawTranscript")
                }.trim()
                val audioName = metadata.optString("audioFile").takeIf { it.isNotBlank() && it != "null" }
                    ?: "$sessionId.wav"
                val wav = File(traceStore.audioDir, audioName)
                require(wav.isFile && wav.length() > 44L) { "WAV missing" }
                val decoded = SherpaFinalAsrEngine.decode(model, wav)
                val disagreement = normalizedEditDistance(live, decoded.text)
                succeeded += 1
                rtfSum += decoded.realTimeFactor
                disagreementSum += disagreement
                row.put("status", "PASS")
                    .put("liveText", live)
                    .put("candidateText", decoded.text)
                    .put("candidate", decoded.engineId)
                    .put("elapsedMs", decoded.elapsedMs)
                    .put("audioDurationMs", decoded.audioDurationMs)
                    .put("rtf", decoded.realTimeFactor)
                    .put("liveCandidateNormalizedEditDistance", disagreement)
            }.onFailure { failure ->
                failed += 1
                row.put("status", "FAIL")
                    .put("error", failure.message ?: failure.javaClass.simpleName)
            }
            rows.put(row)
        }

        val timestamp = System.currentTimeMillis()
        val report = JSONObject()
            .put("schema", 1)
            .put("createdAtMs", timestamp)
            .put("candidate", model.id)
            .put("note", "No ground truth is inferred. liveCandidateNormalizedEditDistance measures disagreement only, not accuracy.")
            .put("attempted", sessions.size)
            .put("succeeded", succeeded)
            .put("failed", failed)
            .put("averageRtf", if (succeeded > 0) rtfSum / succeeded else JSONObject.NULL)
            .put("averageDisagreement", if (succeeded > 0) disagreementSum / succeeded else JSONObject.NULL)
            .put("sessions", rows)
        val target = File(reportDir, "replay-$timestamp.json")
        val temp = File(reportDir, ".replay-$timestamp.json.part")
        temp.writeText(report.toString(2), Charsets.UTF_8)
        check(temp.renameTo(target)) { "ベンチマークレポートを保存できませんでした。" }

        return ReplayBenchmarkSummary(
            attempted = sessions.size,
            succeeded = succeeded,
            failed = failed,
            averageRtf = if (succeeded > 0) rtfSum / succeeded else null,
            averageDisagreement = if (succeeded > 0) disagreementSum / succeeded else null,
            reportFile = target,
        )
    }

    companion object {
        internal fun normalizedEditDistance(a: String, b: String): Double {
            val left = normalize(a)
            val right = normalize(b)
            if (left == right) return 0.0
            if (left.isEmpty() || right.isEmpty()) return 1.0
            var previous = IntArray(right.length + 1) { it }
            var current = IntArray(right.length + 1)
            for (i in left.indices) {
                current[0] = i + 1
                for (j in right.indices) {
                    val substitution = previous[j] + if (left[i] == right[j]) 0 else 1
                    current[j + 1] = minOf(
                        previous[j + 1] + 1,
                        current[j] + 1,
                        substitution,
                    )
                }
                val swap = previous
                previous = current
                current = swap
            }
            return previous[right.length].toDouble() / maxOf(left.length, right.length)
        }

        private fun normalize(text: String): String = text
            .lowercase()
            .replace(Regex("[\\s、。,.!?！？・]"), "")
    }
}
