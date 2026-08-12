package com.goodlight.floatingvoicebubble.dictionary

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class DictionaryTerm(
    val term: String,
    val reading: String = "",
    val aliases: List<String> = emptyList(),
    val weight: Int = 100,
    val useCount: Int = 0,
)

data class DictionaryImportResult(val imported: Int, val skipped: Int)

class PersonalDictionary(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE dictionary_terms (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                term TEXT NOT NULL UNIQUE,
                reading TEXT NOT NULL DEFAULT '',
                aliases TEXT NOT NULL DEFAULT '',
                weight INTEGER NOT NULL DEFAULT 100,
                use_count INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX dictionary_rank ON dictionary_terms(weight DESC, use_count DESC, updated_at DESC)")
        db.execSQL("CREATE INDEX dictionary_reading ON dictionary_terms(reading)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun count(): Long = DatabaseUtils.queryNumEntries(readableDatabase, "dictionary_terms")

    fun upsert(term: DictionaryTerm) {
        val normalized = term.copy(term = term.term.trim(), reading = term.reading.trim())
        require(normalized.term.isNotEmpty())
        writableDatabase.insertWithOnConflict(
            "dictionary_terms",
            null,
            normalized.toValues(),
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun importText(text: String): DictionaryImportResult {
        var imported = 0
        var skipped = 0
        val db = writableDatabase
        db.beginTransaction()
        try {
            text.lineSequence().forEachIndexed { index, rawLine ->
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
                val columns = splitRecord(line)
                if (index == 0 && columns.firstOrNull()?.lowercase() in setOf("term", "単語", "word")) {
                    return@forEachIndexed
                }
                val term = columns.getOrNull(0)?.trim().orEmpty()
                if (term.isEmpty()) {
                    skipped++
                    return@forEachIndexed
                }
                val reading = columns.getOrNull(1)?.trim().orEmpty()
                val aliases = columns.getOrNull(2)
                    ?.split('|', '／')
                    ?.map(String::trim)
                    ?.filter(String::isNotEmpty)
                    .orEmpty()
                val weight = columns.getOrNull(3)?.trim()?.toIntOrNull()?.coerceIn(1, 10_000) ?: 100
                db.insertWithOnConflict(
                    "dictionary_terms",
                    null,
                    DictionaryTerm(term, reading, aliases, weight).toValues(),
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
                imported++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return DictionaryImportResult(imported, skipped)
    }

    fun topBiasTerms(limit: Int = 384): List<String> = readableDatabase.query(
        "dictionary_terms",
        arrayOf("term"),
        null,
        null,
        null,
        null,
        "weight DESC, use_count DESC, updated_at DESC",
        limit.coerceIn(1, 1_000).toString(),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(cursor.getString(0))
        }
    }

    fun relevantTerms(rawTranscript: String, limit: Int = 96): List<DictionaryTerm> {
        if (rawTranscript.isBlank()) return topTerms(limit)
        val matched = readableDatabase.rawQuery(
            """
            SELECT term, reading, aliases, weight, use_count
            FROM dictionary_terms
            WHERE instr(?, term) > 0
               OR (reading <> '' AND instr(?, reading) > 0)
               OR (aliases <> '' AND instr(?, term) > 0)
            ORDER BY weight DESC, use_count DESC, updated_at DESC
            LIMIT ?
            """.trimIndent(),
            arrayOf(rawTranscript, rawTranscript, rawTranscript, limit.toString()),
        ).use(::readTerms)

        if (matched.size >= limit) return matched
        val seen = matched.mapTo(HashSet()) { it.term }
        val highPriority = topTerms(limit - matched.size).filterNot { it.term in seen }
        return (matched + highPriority).take(limit)
    }

    fun markUsed(terms: Collection<String>) {
        if (terms.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            terms.distinct().forEach { term ->
                db.execSQL(
                    "UPDATE dictionary_terms SET use_count = use_count + 1, updated_at = ? WHERE term = ?",
                    arrayOf(System.currentTimeMillis(), term),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun topTerms(limit: Int): List<DictionaryTerm> = readableDatabase.query(
        "dictionary_terms",
        arrayOf("term", "reading", "aliases", "weight", "use_count"),
        null,
        null,
        null,
        null,
        "weight DESC, use_count DESC, updated_at DESC",
        limit.coerceAtLeast(1).toString(),
    ).use(::readTerms)

    private fun readTerms(cursor: android.database.Cursor): List<DictionaryTerm> = buildList {
        while (cursor.moveToNext()) {
            add(
                DictionaryTerm(
                    term = cursor.getString(0),
                    reading = cursor.getString(1),
                    aliases = cursor.getString(2).split('|').filter(String::isNotBlank),
                    weight = cursor.getInt(3),
                    useCount = cursor.getInt(4),
                )
            )
        }
    }

    private fun DictionaryTerm.toValues() = ContentValues().apply {
        put("term", term)
        put("reading", reading)
        put("aliases", aliases.joinToString("|"))
        put("weight", weight)
        put("use_count", useCount)
        put("updated_at", System.currentTimeMillis())
    }

    private fun splitRecord(line: String): List<String> {
        if ('\t' in line) return line.split('\t')
        val output = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> {
                    current.append('"')
                    i++
                }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> {
                    output += current.toString()
                    current.clear()
                }
                else -> current.append(c)
            }
            i++
        }
        output += current.toString()
        return output
    }

    companion object {
        private const val DB_NAME = "personal_dictionary.db"
        private const val DB_VERSION = 1
    }
}
