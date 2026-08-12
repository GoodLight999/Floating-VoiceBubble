package com.goodlight.floatingvoicebubble.benchmark

import android.content.Context
import com.goodlight.floatingvoicebubble.model.AtomicFileInstaller
import com.goodlight.floatingvoicebubble.trace.SessionTraceStore
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class ExternalResultImport(val imported: Int, val skipped: Int)

data class ExternalAsrScore(
    val system: String,
    val labeled: Int,
    val averageStrictCer: Double?,
    val averageContentCer: Double?,
    val averageWer: Double?,
)

/** Human-captured competitor transcripts keyed to the exact VoiceBubble WAV/session. */
class ExternalAsrResultStore(context: Context) {
    private val appContext = context.applicationContext
    private val traceStore = SessionTraceStore(appContext)
    private val referenceStore = BenchmarkReferenceStore(appContext)
    private val dir = File(appContext.noBackupFilesDir, "benchmarks/asr").apply {
        mkdirs()
        AtomicFileInstaller.recoverBackups(this)
    }
    private val file = File(dir, "external-asr-results.json")

    init {
        AtomicFileInstaller.recoverBackups(dir)
    }

    fun set(sessionId: String, system: String, transcript: String) {
        val id = validateSessionId(sessionId)
        val name = validateSystem(system)
        val root = readRoot()
        val systems = root.optJSONObject("systems") ?: JSONObject().also { root.put("systems", it) }
        val rows = systems.optJSONObject(name) ?: JSONObject().also { systems.put(name, it) }
        val normalized = transcript.trim()
        if (normalized.isEmpty()) rows.remove(id) else rows.put(id, normalized)
        writeRoot(root)
    }

    fun importText(text: String): ExternalResultImport {
        var imported = 0
        var skipped = 0
        text.lineSequence().forEachIndexed { index, raw ->
            val line = raw.trimEnd()
            if (line.isBlank() || line.trimStart().startsWith("#")) return@forEachIndexed
            val delimiter = if ('\t' in line) '\t' else ','
            val fields = parseDelimited(line, delimiter)
            if (index == 0 && fields.firstOrNull()?.trim()?.equals("sessionId", ignoreCase = true) == true) {
                return@forEachIndexed
            }
            val ok = runCatching {
                val sessionId = fields.getOrNull(0).orEmpty().trim()
                val system = fields.getOrNull(1).orEmpty().trim()
                val transcript = fields.getOrNull(2).orEmpty().trim()
                if (transcript.isBlank()) false else {
                    set(sessionId, system, transcript)
                    true
                }
            }.getOrDefault(false)
            if (ok) imported++ else skipped++
        }
        return ExternalResultImport(imported, skipped)
    }

    /** Template for capturing competitor output while replaying the exact same source utterance. */
    fun exportTemplate(
        systems: List<String> = DEFAULT_SYSTEMS,
        limit: Int = 30,
    ): String = buildString {
        append("sessionId\tsystem\ttranscript\n")
        val validatedSystems = systems.map(::validateSystem).distinct()
        traceStore.recentSessionMetadata(limit).forEach { metadata ->
            val id = metadata.nameWithoutExtension
            validatedSystems.forEach { system ->
                append(tsv(id)).append('\t').append(tsv(system)).append('\t')
                    .append(tsv(get(id, system).orEmpty())).append('\n')
            }
        }
    }

    fun get(sessionId: String, system: String): String? = readRoot()
        .optJSONObject("systems")
        ?.optJSONObject(validateSystem(system))
        ?.optString(validateSessionId(sessionId))
        ?.trim()
        ?.takeIf(String::isNotBlank)

    fun scoreAll(): List<ExternalAsrScore> {
        val systems = readRoot().optJSONObject("systems") ?: return emptyList()
        return systems.keys().asSequence().mapNotNull { system ->
            val rows = systems.optJSONObject(system) ?: return@mapNotNull null
            var labeled = 0
            var strictSum = 0.0
            var contentSum = 0.0
            var werSum = 0.0
            var werCount = 0
            rows.keys().forEach { sessionId ->
                val reference = referenceStore.get(sessionId) ?: return@forEach
                val transcript = rows.optString(sessionId).trim()
                if (transcript.isBlank()) return@forEach
                val score = AsrAccuracyScorer.score(reference, transcript)
                labeled++
                strictSum += score.strictCer
                contentSum += score.contentCer
                score.wer?.let {
                    werSum += it
                    werCount++
                }
            }
            ExternalAsrScore(
                system = system,
                labeled = labeled,
                averageStrictCer = strictSum.takeIf { labeled > 0 }?.div(labeled),
                averageContentCer = contentSum.takeIf { labeled > 0 }?.div(labeled),
                averageWer = werSum.takeIf { werCount > 0 }?.div(werCount),
            )
        }.sortedWith(compareByDescending<ExternalAsrScore> { it.labeled }.thenBy { it.averageContentCer ?: Double.MAX_VALUE })
            .toList()
    }

    private fun readRoot(): JSONObject = runCatching {
        if (!file.isFile) return@runCatching JSONObject().put("schema", 1).put("systems", JSONObject())
        JSONObject(file.readText(Charsets.UTF_8)).also { require(it.optInt("schema") == 1) }
    }.getOrElse { JSONObject().put("schema", 1).put("systems", JSONObject()) }

    private fun writeRoot(root: JSONObject) {
        root.put("schema", 1)
        val temporary = File(dir, ".external-asr-results.json.part-${UUID.randomUUID()}")
        temporary.writeText(root.toString(2), Charsets.UTF_8)
        AtomicFileInstaller.replace(temporary, file, "外部ASR比較結果")
    }

    private fun validateSessionId(value: String): String {
        val normalized = value.trim()
        require(SESSION_ID.matches(normalized)) { "invalid session id" }
        return normalized
    }

    private fun validateSystem(value: String): String {
        val normalized = value.trim()
        require(normalized.length in 1..64 && normalized.none(Char::isISOControl)) { "invalid ASR system name" }
        return normalized
    }

    companion object {
        val DEFAULT_SYSTEMS = listOf("Gboard", "Wispr Flow", "Aqua Voice")
        private val SESSION_ID = Regex("[A-Za-z0-9._-]{1,128}")

        internal fun parseDelimited(line: String, delimiter: Char): List<String> {
            val fields = mutableListOf<String>()
            val current = StringBuilder()
            var quoted = false
            var index = 0
            while (index < line.length) {
                when (val char = line[index]) {
                    '"' -> if (quoted && index + 1 < line.length && line[index + 1] == '"') {
                        current.append('"')
                        index++
                    } else quoted = !quoted
                    delimiter -> if (quoted) current.append(char) else {
                        fields += current.toString()
                        current.clear()
                    }
                    else -> current.append(char)
                }
                index++
            }
            require(!quoted) { "unterminated quoted field" }
            fields += current.toString()
            return fields
        }

        private fun tsv(value: String): String = value.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ')
    }
}
