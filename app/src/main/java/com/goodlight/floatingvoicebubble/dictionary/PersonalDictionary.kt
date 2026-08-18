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

enum class DictionarySort {
    PRIORITY,
    MOST_USED,
    RECENT,
    TERM,
}

data class DictionaryImportResult(val imported: Int, val skipped: Int)

class PersonalDictionary(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        createTermsTable(db)
        createAliasTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            createAliasTable(db)
            migratePackedAliases(db)
        }
    }

    fun count(): Long = DatabaseUtils.queryNumEntries(readableDatabase, "dictionary_terms")

    fun upsert(term: DictionaryTerm) {
        save(originalTerm = null, term = term)
    }

    /**
     * Saves a dictionary row as a real edit operation.
     *
     * When [originalTerm] differs from [term.term], the old row is renamed atomically instead of
     * silently creating a second row. Runtime use_count is preserved across the rename. A rename
     * never overwrites another existing term; the caller gets an error and can resolve the conflict.
     */
    fun save(originalTerm: String?, term: DictionaryTerm) {
        val normalized = normalize(term)
        require(normalized.term.isNotEmpty()) { "term must not be blank" }
        val original = originalTerm?.trim().orEmpty()
        val db = writableDatabase
        db.beginTransaction()
        try {
            when {
                original.isBlank() || original == normalized.term -> upsert(db, normalized)
                else -> rename(db, original, normalized)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun get(term: String): DictionaryTerm? {
        val key = term.trim()
        if (key.isEmpty()) return null
        return get(readableDatabase, key)
    }

    fun delete(term: String): Boolean {
        val key = term.trim()
        if (key.isEmpty()) return false
        return writableDatabase.delete("dictionary_terms", "term = ?", arrayOf(key)) > 0
    }

    /** Search terms/readings/aliases without loading the unbounded dictionary into memory. */
    fun search(
        query: String = "",
        limit: Int = 50,
        offset: Int = 0,
        sort: DictionarySort = DictionarySort.PRIORITY,
    ): List<DictionaryTerm> {
        val boundedLimit = limit.coerceIn(1, 500)
        val boundedOffset = offset.coerceAtLeast(0)
        val needle = query.trim()
        if (needle.isBlank()) return topTerms(boundedLimit, boundedOffset, sort)
        return readableDatabase.rawQuery(
            """
            SELECT DISTINCT t.term, t.reading, t.aliases, t.weight, t.use_count
            FROM dictionary_terms t
            LEFT JOIN dictionary_aliases a ON a.term_id = t.id
            WHERE instr(lower(t.term), lower(?)) > 0
               OR instr(lower(t.reading), lower(?)) > 0
               OR (a.alias IS NOT NULL AND instr(lower(a.alias), lower(?)) > 0)
            ORDER BY ${orderBy(sort)}
            LIMIT ? OFFSET ?
            """.trimIndent(),
            arrayOf(needle, needle, needle, boundedLimit.toString(), boundedOffset.toString()),
        ).use(::readTerms)
    }

    /** Portable TSV accepted by [importText]. Runtime use_count is intentionally not exported. */
    fun exportTsv(): String = buildString {
        append("term\treading\taliases\tweight\n")
        var offset = 0
        while (true) {
            val batch = topTerms(EXPORT_BATCH, offset, DictionarySort.PRIORITY)
            if (batch.isEmpty()) break
            batch.forEach { entry ->
                append(tsv(entry.term)).append('\t')
                    .append(tsv(entry.reading)).append('\t')
                    .append(tsv(entry.aliases.joinToString("|"))).append('\t')
                    .append(entry.weight).append('\n')
            }
            offset += batch.size
        }
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
                if (index == 0 && columns.firstOrNull()?.trim()?.lowercase() in setOf("term", "単語", "word")) {
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
                upsert(db, normalize(DictionaryTerm(term, reading, aliases, weight)))
                imported++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return DictionaryImportResult(imported, skipped)
    }

    /**
     * Android's biasing extra is intentionally a bounded transport. The dictionary itself is not.
     * Rank candidates locally, then spend the recognizer's finite bias budget on terms/readings/aliases
     * that have the highest configured and observed usefulness.
     */
    fun topBiasTerms(limit: Int = 384): List<String> {
        val source = topTerms(
            (limit / 2).coerceAtLeast(96).coerceAtMost(1_000),
            sort = DictionarySort.PRIORITY,
        )
        val output = LinkedHashSet<String>(limit)
        for (entry in source) {
            sequenceOf(entry.term, entry.reading)
                .plus(entry.aliases.asSequence())
                .map(String::trim)
                .filter(String::isNotEmpty)
                .forEach {
                    if (output.size < limit) output += it
                }
            if (output.size >= limit) break
        }
        return output.toList()
    }

    fun relevantTerms(rawTranscript: String, limit: Int = 96): List<DictionaryTerm> {
        if (rawTranscript.isBlank()) return topTerms(limit, sort = DictionarySort.PRIORITY)
        val matched = readableDatabase.rawQuery(
            """
            SELECT DISTINCT t.term, t.reading, t.aliases, t.weight, t.use_count
            FROM dictionary_terms t
            LEFT JOIN dictionary_aliases a ON a.term_id = t.id
            WHERE instr(?, t.term) > 0
               OR (t.reading <> '' AND instr(?, t.reading) > 0)
               OR (a.alias IS NOT NULL AND instr(?, a.alias) > 0)
            ORDER BY t.weight DESC, t.use_count DESC, t.updated_at DESC
            LIMIT ?
            """.trimIndent(),
            arrayOf(rawTranscript, rawTranscript, rawTranscript, limit.coerceAtLeast(1).toString()),
        ).use(::readTerms)

        if (matched.size >= limit) return matched
        val seen = matched.mapTo(HashSet()) { it.term }
        val highPriority = topTerms(limit, sort = DictionarySort.PRIORITY).filterNot { it.term in seen }
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
                    arrayOf<Any>(System.currentTimeMillis(), term),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun rename(db: SQLiteDatabase, original: String, updated: DictionaryTerm) {
        val current = get(db, original) ?: error("dictionary term no longer exists: $original")
        val collision = DatabaseUtils.longForQuery(
            db,
            "SELECT COUNT(*) FROM dictionary_terms WHERE term = ?",
            arrayOf(updated.term),
        ) > 0
        require(!collision) { "dictionary term already exists: ${updated.term}" }

        check(db.delete("dictionary_terms", "term = ?", arrayOf(original)) == 1) {
            "dictionary term could not be renamed: $original"
        }
        insertFresh(db, updated, current.useCount)
    }

    private fun upsert(db: SQLiteDatabase, term: DictionaryTerm) {
        val aliasesPacked = term.aliases.joinToString("|")
        db.execSQL(
            """
            INSERT INTO dictionary_terms(term, reading, aliases, weight, use_count, updated_at)
            VALUES(?, ?, ?, ?, 0, ?)
            ON CONFLICT(term) DO UPDATE SET
                reading = excluded.reading,
                aliases = excluded.aliases,
                weight = excluded.weight,
                updated_at = excluded.updated_at
            """.trimIndent(),
            arrayOf<Any>(term.term, term.reading, aliasesPacked, term.weight, System.currentTimeMillis()),
        )
        replaceAliases(db, term)
    }

    private fun insertFresh(db: SQLiteDatabase, term: DictionaryTerm, useCount: Int) {
        val id = db.insertOrThrow(
            "dictionary_terms",
            null,
            ContentValues().apply {
                put("term", term.term)
                put("reading", term.reading)
                put("aliases", term.aliases.joinToString("|"))
                put("weight", term.weight)
                put("use_count", useCount.coerceAtLeast(0))
                put("updated_at", System.currentTimeMillis())
            },
        )
        insertAliases(db, id, term.aliases)
    }

    private fun replaceAliases(db: SQLiteDatabase, term: DictionaryTerm) {
        val id = DatabaseUtils.longForQuery(
            db,
            "SELECT id FROM dictionary_terms WHERE term = ?",
            arrayOf(term.term),
        )
        db.delete("dictionary_aliases", "term_id = ?", arrayOf(id.toString()))
        insertAliases(db, id, term.aliases)
    }

    private fun insertAliases(db: SQLiteDatabase, id: Long, aliases: List<String>) {
        aliases.forEach { alias ->
            db.insertWithOnConflict(
                "dictionary_aliases",
                null,
                ContentValues().apply {
                    put("term_id", id)
                    put("alias", alias)
                },
                SQLiteDatabase.CONFLICT_IGNORE,
            )
        }
    }

    private fun get(db: SQLiteDatabase, term: String): DictionaryTerm? = db.query(
        "dictionary_terms",
        arrayOf("term", "reading", "aliases", "weight", "use_count"),
        "term = ?",
        arrayOf(term),
        null,
        null,
        null,
        "1",
    ).use(::readTerms).firstOrNull()

    private fun topTerms(
        limit: Int,
        offset: Int = 0,
        sort: DictionarySort = DictionarySort.PRIORITY,
    ): List<DictionaryTerm> = readableDatabase.rawQuery(
        """
        SELECT term, reading, aliases, weight, use_count
        FROM dictionary_terms
        ORDER BY ${orderBy(sort)}
        LIMIT ? OFFSET ?
        """.trimIndent(),
        arrayOf(limit.coerceAtLeast(1).toString(), offset.coerceAtLeast(0).toString()),
    ).use(::readTerms)

    private fun orderBy(sort: DictionarySort): String = when (sort) {
        DictionarySort.PRIORITY -> "weight DESC, use_count DESC, updated_at DESC, term COLLATE NOCASE ASC"
        DictionarySort.MOST_USED -> "use_count DESC, weight DESC, updated_at DESC, term COLLATE NOCASE ASC"
        DictionarySort.RECENT -> "updated_at DESC, weight DESC, use_count DESC, term COLLATE NOCASE ASC"
        DictionarySort.TERM -> "term COLLATE NOCASE ASC, weight DESC, use_count DESC"
    }

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

    private fun normalize(term: DictionaryTerm): DictionaryTerm {
        val normalizedTerm = term.term.trim()
        return term.copy(
            term = normalizedTerm,
            reading = term.reading.trim(),
            aliases = term.aliases.map(String::trim)
                .filter(String::isNotEmpty)
                .filterNot { it.equals(normalizedTerm, ignoreCase = true) }
                .distinctBy { it.lowercase() },
            weight = term.weight.coerceIn(1, 10_000),
        )
    }

    private fun createTermsTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS dictionary_terms (
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
        db.execSQL("CREATE INDEX IF NOT EXISTS dictionary_rank ON dictionary_terms(weight DESC, use_count DESC, updated_at DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS dictionary_reading ON dictionary_terms(reading)")
    }

    private fun createAliasTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS dictionary_aliases (
                term_id INTEGER NOT NULL,
                alias TEXT NOT NULL,
                PRIMARY KEY(term_id, alias),
                FOREIGN KEY(term_id) REFERENCES dictionary_terms(id) ON DELETE CASCADE
            ) WITHOUT ROWID
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS dictionary_alias_lookup ON dictionary_aliases(alias)")
    }

    private fun migratePackedAliases(db: SQLiteDatabase) {
        db.query("dictionary_terms", arrayOf("id", "aliases"), "aliases <> ''", null, null, null, null).use { cursor ->
            while (cursor.moveToNext()) {
                val termId = cursor.getLong(0)
                cursor.getString(1).split('|').map(String::trim).filter(String::isNotEmpty).distinct().forEach { alias ->
                    db.insertWithOnConflict(
                        "dictionary_aliases",
                        null,
                        ContentValues().apply {
                            put("term_id", termId)
                            put("alias", alias)
                        },
                        SQLiteDatabase.CONFLICT_IGNORE,
                    )
                }
            }
        }
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
        require(!quoted) { "unterminated quoted field" }
        output += current.toString()
        return output
    }

    private fun tsv(value: String): String = value
        .replace('\t', ' ')
        .replace('\r', ' ')
        .replace('\n', ' ')

    companion object {
        private const val DB_NAME = "personal_dictionary.db"
        private const val DB_VERSION = 2
        private const val EXPORT_BATCH = 500
    }
}
