package com.goodlight.floatingvoicebubble.benchmark

import android.content.Context
import com.goodlight.floatingvoicebubble.model.AsrModelStore
import com.goodlight.floatingvoicebubble.model.FinalAsrModelStore
import com.goodlight.floatingvoicebubble.speech.SherpaFinalAsrEngine
import com.goodlight.floatingvoicebubble.speech.SherpaStreamingEngine
import com.goodlight.floatingvoicebubble.trace.SessionTraceStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class AsrCandidateScore(
    val id: String,
    val label: String,
    val kind: String,
    val attempted: Int,
    val succeeded: Int,
    val labeled: Int,
    val averageStrictCer: Double?,
    val averageContentCer: Double?,
    val averageWer: Double?,
    val averageRtf: Double?,
)

data class AsrTournamentSummary(
    val candidates: List<AsrCandidateScore>,
    val bestStreamingId: String?,
    val bestFinalId: String?,
    val reportFile: File,
) {
    fun oneLine(): String {
        val labeled = candidates.maxOfOrNull { it.labeled } ?: 0
        if (labeled == 0) return "正解ラベルがありません。TSVで正解文を付けてから実行してください。"
        val stream = bestStreamingId ?: "判定不能"
        val final = bestFinalId ?: "判定不能"
        return "正解付き最大 $labeled 件 / streaming候補 $stream / final候補 $final"
    }
}

/**
 * Exact-same-WAV tournament. It never infers ground truth from another recognizer.
 * Live, installed Nemotron variants, ReazonSpeech, and imported external systems share
 * the same human reference store and are reported with their labeled sample count.
 */
class AsrTournamentRunner(context: Context) {
    private val appContext = context.applicationContext
    private val traces = SessionTraceStore(appContext)
    private val references = BenchmarkReferenceStore(appContext)
    private val streamingStore = AsrModelStore(appContext)
    private val finalStore = FinalAsrModelStore(appContext)
    private val externalStore = ExternalAsrResultStore(appContext)
    private val reportDir = File(appContext.noBackupFilesDir, "benchmarks/asr").apply { mkdirs() }

    fun run(limit: Int = 20): AsrTournamentSummary {
        val sessionFiles = traces.recentSessionMetadata(limit)
        require(sessionFiles.isNotEmpty()) { "比較できる保存済みセッションがありません。" }
        val sessions = sessionFiles.mapNotNull(::loadSession)
        require(sessions.isNotEmpty()) { "有効なWAV付きセッションがありません。" }

        val scores = mutableListOf<AsrCandidateScore>()
        scores += scoreLive(sessions)
        streamingStore.listInstalled().forEach { model ->
            scores += scoreDecoded(
                id = model.id,
                label = "Nemotron ${model.chunkMs}ms",
                kind = "streaming-replay",
                sessions = sessions,
            ) { wav ->
                val decoded = SherpaStreamingEngine.decodeReplay(model, wav)
                Decode(decoded.text, decoded.realTimeFactor)
            }
        }
        finalStore.resolve(FinalAsrModelStore.MODEL_ID)?.let { model ->
            scores += scoreDecoded(
                id = model.id,
                label = "ReazonSpeech final",
                kind = "final-asr",
                sessions = sessions,
            ) { wav ->
                val decoded = SherpaFinalAsrEngine.decode(model, wav)
                Decode(decoded.text, decoded.realTimeFactor)
            }
        }
        scores += externalStore.scoreAll().map { external ->
            AsrCandidateScore(
                id = "external:${external.system}",
                label = external.system,
                kind = "external",
                attempted = external.labeled,
                succeeded = external.labeled,
                labeled = external.labeled,
                averageStrictCer = external.averageStrictCer,
                averageContentCer = external.averageContentCer,
                averageWer = external.averageWer,
                averageRtf = null,
            )
        }

        val bestStreaming = scores
            .filter { it.kind == "streaming-replay" && it.labeled > 0 && it.averageContentCer != null }
            .minWithOrNull(compareBy<AsrCandidateScore> { it.averageContentCer }.thenBy { it.averageRtf ?: Double.MAX_VALUE })
            ?.id
        val bestFinal = scores
            .filter { it.kind in setOf("live", "final-asr") && it.labeled > 0 && it.averageContentCer != null }
            .minWithOrNull(compareBy<AsrCandidateScore> { it.averageContentCer }.thenBy { it.averageRtf ?: Double.MAX_VALUE })
            ?.id

        val timestamp = System.currentTimeMillis()
        val report = JSONObject()
            .put("schema", 1)
            .put("createdAtMs", timestamp)
            .put("note", "Accuracy metrics use explicit human references only. streaming-replay is an exact-WAV benchmark, not a live latency claim.")
            .put("bestStreamingId", bestStreaming ?: JSONObject.NULL)
            .put("bestFinalId", bestFinal ?: JSONObject.NULL)
            .put("candidates", JSONArray().apply { scores.forEach { put(it.toJson()) } })
        val target = File(reportDir, "tournament-$timestamp.json")
        val temporary = File(reportDir, ".tournament-$timestamp.json.part")
        temporary.writeText(report.toString(2), Charsets.UTF_8)
        check(temporary.renameTo(target)) { "ASR比較レポートを保存できませんでした。" }

        return AsrTournamentSummary(scores, bestStreaming, bestFinal, target)
    }

    private fun scoreLive(sessions: List<Session>): AsrCandidateScore {
        val rows = sessions.mapNotNull { session ->
            val transcript = session.liveText.takeIf(String::isNotBlank) ?: return@mapNotNull null
            ScoredRow(transcript, rtf = null, reference = session.reference)
        }
        return aggregate("live-result", "現在のlive結果", "live", sessions.size, rows)
    }

    private fun scoreDecoded(
        id: String,
        label: String,
        kind: String,
        sessions: List<Session>,
        decode: (File) -> Decode,
    ): AsrCandidateScore {
        val rows = mutableListOf<ScoredRow>()
        sessions.forEach { session ->
            runCatching { decode(session.wav) }.getOrNull()?.let { decoded ->
                rows += ScoredRow(decoded.text, decoded.rtf, session.reference)
            }
        }
        return aggregate(id, label, kind, sessions.size, rows)
    }

    private fun aggregate(
        id: String,
        label: String,
        kind: String,
        attempted: Int,
        rows: List<ScoredRow>,
    ): AsrCandidateScore {
        var labeled = 0
        var strict = 0.0
        var content = 0.0
        var wer = 0.0
        var werCount = 0
        var rtf = 0.0
        var rtfCount = 0
        rows.forEach { row ->
            row.rtf?.let { rtf += it; rtfCount++ }
            row.reference?.let { reference ->
                val score = AsrAccuracyScorer.score(reference, row.text)
                labeled++
                strict += score.strictCer
                content += score.contentCer
                score.wer?.let { wer += it; werCount++ }
            }
        }
        return AsrCandidateScore(
            id = id,
            label = label,
            kind = kind,
            attempted = attempted,
            succeeded = rows.size,
            labeled = labeled,
            averageStrictCer = strict.takeIf { labeled > 0 }?.div(labeled),
            averageContentCer = content.takeIf { labeled > 0 }?.div(labeled),
            averageWer = wer.takeIf { werCount > 0 }?.div(werCount),
            averageRtf = rtf.takeIf { rtfCount > 0 }?.div(rtfCount),
        )
    }

    private fun loadSession(metadataFile: File): Session? = runCatching {
        val metadata = JSONObject(metadataFile.readText(Charsets.UTF_8))
        val id = metadata.optString("sessionId", metadataFile.nameWithoutExtension)
        val audioName = metadata.optString("audioFile").takeIf { it.isNotBlank() && it != "null" } ?: "$id.wav"
        val wav = File(traces.audioDir, audioName)
        require(wav.isFile && wav.length() > 44L)
        val live = if (metadata.has("liveRawTranscript")) {
            metadata.optString("liveRawTranscript")
        } else {
            metadata.optString("rawTranscript")
        }.trim()
        Session(id, wav, live, references.get(id))
    }.getOrNull()

    private data class Session(val id: String, val wav: File, val liveText: String, val reference: String?)
    private data class Decode(val text: String, val rtf: Double?)
    private data class ScoredRow(val text: String, val rtf: Double?, val reference: String?)

    private fun AsrCandidateScore.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("label", label)
        .put("kind", kind)
        .put("attempted", attempted)
        .put("succeeded", succeeded)
        .put("labeled", labeled)
        .put("averageStrictCer", averageStrictCer ?: JSONObject.NULL)
        .put("averageContentCer", averageContentCer ?: JSONObject.NULL)
        .put("averageWer", averageWer ?: JSONObject.NULL)
        .put("averageRtf", averageRtf ?: JSONObject.NULL)
}
