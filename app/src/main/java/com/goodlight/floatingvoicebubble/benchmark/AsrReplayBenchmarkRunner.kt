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
    val labeled: Int,
    val averageRtf: Double?,
    val averageDisagreement: Double?,
    val averageStrictCer: Double?,
    val averageContentCer: Double?,
    val averageWer: Double?,
    val reportFile: File,
) {
    fun oneLine(): String = buildString {
        append("成功 $succeeded/$attempted")
        averageRtf?.let { append(" / 平均RTF ${"%.3f".format(it)}") }
        if (labeled > 0) {
            append(" / 正解付き $labeled")
            averageContentCer?.let { append(" / CER ${"%.3f".format(it)}") }
            averageStrictCer?.let { append(" / strict ${"%.3f".format(it)}") }
            averageWer?.let { append(" / WER ${"%.3f".format(it)}") }
        } else {
            averageDisagreement?.let { append(" / liveとの差 ${"%.3f".format(it)}") }
        }
        if (failed > 0) append(" / FAIL $failed")
    }
}

class AsrReplayBenchmarkRunner(context: Context) {
    private val appContext = context.applicationContext
    private val traceStore = SessionTraceStore(appContext)
    private val referenceStore = BenchmarkReferenceStore(appContext)
    private val reportDir = File(appContext.noBackupFilesDir, "benchmarks/asr").apply { mkdirs() }

    fun run(model: FinalAsrModel, limit: Int = 20): ReplayBenchmarkSummary {
        val sessions = traceStore.recentSessionMetadata(limit)
        require(sessions.isNotEmpty()) {
            "比較できる保存済みセッションがありません。音声入力を数回使ってから実行してください。"
        }

        val rows = JSONArray()
        var succeeded = 0
        var failed = 0
        var labeled = 0
        var werCount = 0
        var rtfSum = 0.0
        var disagreementSum = 0.0
        var strictCerSum = 0.0
        var contentCerSum = 0.0
        var werSum = 0.0

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

                referenceStore.get(sessionId)?.let { reference ->
                    val score = AsrAccuracyScorer.score(reference, decoded.text)
                    labeled += 1
                    strictCerSum += score.strictCer
                    contentCerSum += score.contentCer
                    score.wer?.let { wer ->
                        werCount += 1
                        werSum += wer
                    }
                    row.put("groundTruth", reference)
                        .put("strictCer", score.strictCer)
                        .put("contentCer", score.contentCer)
                        .put("wer", score.wer ?: JSONObject.NULL)
                        .put("referenceCodePoints", score.referenceCodePoints)
                        .put("hypothesisCodePoints", score.hypothesisCodePoints)
                } ?: row.put("groundTruth", JSONObject.NULL)
            }.onFailure { failure ->
                failed += 1
                row.put("status", "FAIL")
                    .put("error", failure.message ?: failure.javaClass.simpleName)
            }
            rows.put(row)
        }

        val timestamp = System.currentTimeMillis()
        val report = JSONObject()
            .put("schema", 2)
            .put("createdAtMs", timestamp)
            .put("candidate", model.id)
            .put(
                "note",
                "Accuracy metrics are emitted only for explicit ground-truth labels. " +
                    "liveCandidateNormalizedEditDistance measures disagreement only.",
            )
            .put("attempted", sessions.size)
            .put("succeeded", succeeded)
            .put("failed", failed)
            .put("labeled", labeled)
            .put("averageRtf", if (succeeded > 0) rtfSum / succeeded else JSONObject.NULL)
            .put("averageDisagreement", if (succeeded > 0) disagreementSum / succeeded else JSONObject.NULL)
            .put("averageStrictCer", if (labeled > 0) strictCerSum / labeled else JSONObject.NULL)
            .put("averageContentCer", if (labeled > 0) contentCerSum / labeled else JSONObject.NULL)
            .put("averageWer", if (werCount > 0) werSum / werCount else JSONObject.NULL)
            .put("sessions", rows)
        val target = File(reportDir, "replay-$timestamp.json")
        val temp = File(reportDir, ".replay-$timestamp.json.part")
        temp.writeText(report.toString(2), Charsets.UTF_8)
        check(temp.renameTo(target)) { "ベンチマークレポートを保存できませんでした。" }

        return ReplayBenchmarkSummary(
            attempted = sessions.size,
            succeeded = succeeded,
            failed = failed,
            labeled = labeled,
            averageRtf = if (succeeded > 0) rtfSum / succeeded else null,
            averageDisagreement = if (succeeded > 0) disagreementSum / succeeded else null,
            averageStrictCer = if (labeled > 0) strictCerSum / labeled else null,
            averageContentCer = if (labeled > 0) contentCerSum / labeled else null,
            averageWer = if (werCount > 0) werSum / werCount else null,
            reportFile = target,
        )
    }

    companion object {
        internal fun normalizedEditDistance(a: String, b: String): Double {
            val left = AsrAccuracyScorer.normalize(a, stripPunctuation = true).codePoints().toArray()
            val right = AsrAccuracyScorer.normalize(b, stripPunctuation = true).codePoints().toArray()
            if (left.contentEquals(right)) return 0.0
            if (left.isEmpty() || right.isEmpty()) return 1.0
            var previous = IntArray(right.size + 1) { it }
            var current = IntArray(right.size + 1)
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
            return previous[right.size].toDouble() / maxOf(left.size, right.size)
        }
    }
}
