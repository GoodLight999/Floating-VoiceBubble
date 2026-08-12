package com.goodlight.floatingvoicebubble.benchmark

import android.content.Context
import com.goodlight.floatingvoicebubble.model.AtomicFileInstaller
import com.goodlight.floatingvoicebubble.trace.SessionTraceStore
import org.json.JSONObject
import java.io.File

data class ReferenceImportResult(val imported: Int, val skipped: Int)

/** Ground-truth labels are stored beside no-backup session traces and never inferred from ASR output. */
class BenchmarkReferenceStore(context: Context) {
    private val traceStore = SessionTraceStore(context.applicationContext).also {
        AtomicFileInstaller.recoverBackups(it.audioDir)
    }

    fun get(sessionId: String): String? {
        val id = validatedSessionId(sessionId)
        val file = referenceFile(id)
        return file.takeIf(File::isFile)
            ?.readText(Charsets.UTF_8)
            ?.trim()
            ?.takeIf(String::isNotBlank)
    }

    fun count(): Int = traceStore.audioDir
        .listFiles { file -> file.isFile && file.name.endsWith(REFERENCE_SUFFIX) }
        ?.size
        ?: 0

    fun set(sessionId: String, reference: String) {
        val id = validatedSessionId(sessionId)
        val normalized = reference.trim()
        val file = referenceFile(id)
        if (normalized.isEmpty()) {
            file.delete()
            return
        }
        val temp = File(traceStore.audioDir, ".$id.reference.txt.part")
        temp.writeText(normalized, Charsets.UTF_8)
        AtomicFileInstaller.replace(temp, file, "ASR正解ラベル")
    }

    /**
     * Accepted layouts:
     * - sessionId, reference
     * - sessionId, liveTranscript, reference (the format emitted by [exportTemplate])
     *
     * TSV/CSV are both accepted, a header is optional, and quoted CSV fields are supported.
     */
    fun importText(text: String): ReferenceImportResult {
        var imported = 0
        var skipped = 0
        var headerReferenceColumn: Int? = null

        text.lineSequence().forEachIndexed { index, rawLine ->
            val line = rawLine.trimEnd()
            if (line.isBlank() || line.trimStart().startsWith("#")) return@forEachIndexed
            val delimiter = if ('\t' in line) '\t' else ','
            val fields = parseDelimited(line, delimiter)

            if (index == 0 && fields.firstOrNull()?.trim()?.equals("sessionId", ignoreCase = true) == true) {
                headerReferenceColumn = fields.indexOfFirst { it.trim().equals("reference", ignoreCase = true) }
                    .takeIf { it >= 0 }
                    ?: error("reference column is missing")
                return@forEachIndexed
            }

            val sessionId = fields.getOrNull(0)?.trim().orEmpty()
            val referenceColumn = referenceColumnFor(fields, headerReferenceColumn)
            val reference = fields.getOrNull(referenceColumn)?.trim().orEmpty()
            val valid = runCatching {
                set(sessionId, reference)
                reference.isNotBlank()
            }.getOrDefault(false)
            if (valid) imported++ else skipped++
        }
        return ReferenceImportResult(imported, skipped)
    }

    /** Returns a TSV template with the live transcript for orientation and an editable reference column. */
    fun exportTemplate(limit: Int = 30): String = buildString {
        append("sessionId\tliveTranscript\treference\n")
        traceStore.recentSessionMetadata(limit).forEach { metadataFile ->
            runCatching {
                val metadata = JSONObject(metadataFile.readText(Charsets.UTF_8))
                val id = metadata.optString("sessionId", metadataFile.nameWithoutExtension)
                val live = metadata.optString("liveRawTranscript", metadata.optString("rawTranscript"))
                append(tsvEscape(id)).append('\t')
                    .append(tsvEscape(live)).append('\t')
                    .append(tsvEscape(get(id).orEmpty())).append('\n')
            }
        }
    }

    private fun referenceFile(sessionId: String): File = File(traceStore.audioDir, "$sessionId$REFERENCE_SUFFIX")

    private fun validatedSessionId(value: String): String {
        val trimmed = value.trim()
        require(SESSION_ID.matches(trimmed)) { "invalid session id" }
        return trimmed
    }

    companion object {
        private val SESSION_ID = Regex("[A-Za-z0-9._-]{1,128}")
        private const val REFERENCE_SUFFIX = ".reference.txt"

        internal fun referenceColumnFor(fields: List<String>, headerReferenceColumn: Int?): Int =
            headerReferenceColumn ?: if (fields.size >= 3) fields.lastIndex else 1

        internal fun parseDelimited(line: String, delimiter: Char): List<String> {
            val fields = mutableListOf<String>()
            val current = StringBuilder()
            var quoted = false
            var index = 0
            while (index < line.length) {
                val char = line[index]
                when {
                    char == '"' && quoted && index + 1 < line.length && line[index + 1] == '"' -> {
                        current.append('"')
                        index++
                    }
                    char == '"' -> quoted = !quoted
                    char == delimiter && !quoted -> {
                        fields += current.toString()
                        current.setLength(0)
                    }
                    else -> current.append(char)
                }
                index++
            }
            require(!quoted) { "unterminated quoted field" }
            fields += current.toString()
            return fields
        }

        private fun tsvEscape(value: String): String = value
            .replace("\t", " ")
            .replace("\r", " ")
            .replace("\n", " ")
    }
}
