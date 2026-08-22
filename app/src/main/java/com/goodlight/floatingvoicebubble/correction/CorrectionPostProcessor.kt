package com.goodlight.floatingvoicebubble.correction

import com.goodlight.floatingvoicebubble.LineBreakMode
import com.goodlight.floatingvoicebubble.RecognitionRepairMode
import kotlin.math.abs

/** Deterministic last-mile handling for explicit user formatting choices. */
object CorrectionPostProcessor {
    fun apply(raw: String, modelOutput: String, preferences: CorrectionPreferences): String {
        var text = CorrectionGuard.sanitize(modelOutput)
        if (preferences.removeFillers) text = removeObviousFillers(text)
        if (preferences.addPeriods) text = ensureTerminalPunctuation(text)
        text = ensureRequestedLineBreaks(text, preferences.lineBreakMode)
        text = enforceDisabledFormatting(raw, text, preferences)
        return text.ifBlank { raw }
    }

    /** Production no longer performs a hidden second LM call. */
    fun shouldRetryNoOp(request: CorrectionRequest, modelOutput: String): Boolean = false

    fun probeFailure(output: String): String? {
        val request = correctionProbeRequest()
        val processed = apply(request.rawTranscript, output, request.preferences)
        val decision = CorrectionGuard.choose(request.rawTranscript, processed, request.preferences)
        if (!decision.accepted) return "モデル出力を採用できませんでした: ${decision.reason ?: "unknown"}"
        val text = decision.text
        if (text == request.rawTranscript) return "APIは応答しましたが、補正結果がRAWと完全に同一です。"
        if (LEADING_FILLER.containsMatchIn(text)) return "『えー』『あのー』等の削除設定を実行していません。"
        if (!text.contains("聞き取りAI") || text.contains("取り合い")) {
            return "N-bestと周辺文脈にある明白な『聞き取りAI』への復元を実行していません。"
        }
        if (!hasTerminalPunctuation(text)) return "句点追加の設定を実行していません。"
        return null
    }

    fun correctionProbeRequest(): CorrectionRequest = CorrectionRequest(
        rawTranscript = "えー音声入力の取り合いがだいぶがっつり聞き取りミスをした",
        alternatives = listOf(
            "えー音声入力の取り合いがだいぶがっつり聞き取りミスをした",
            "えー音声入力の聞き取りAIがだいぶがっつり聞き取りミスをした",
        ),
        surroundingContext = "音声認識AIとLM補正について話している。直前から聞き取りAIの誤認識を検証している。",
        dictionaryTerms = emptyList(),
        preferences = CorrectionPreferences(
            addCommas = true,
            addPeriods = true,
            removeFillers = true,
            recognitionRepairMode = RecognitionRepairMode.STRONG,
        ),
        forceCorrection = true,
    )

    private fun removeObviousFillers(value: String): String {
        var text = value.trim()
        repeat(6) {
            val next = text.replaceFirst(LEADING_FILLER, "").trimStart()
            if (next == text) return@repeat
            text = next
        }
        return text.replace(BOUNDARY_FILLER) { match -> match.groupValues[1] }
            .replace(Regex("[、,]{2,}"), "、")
            .trim()
    }

    private fun ensureTerminalPunctuation(value: String): String {
        val trimmed = value.trimEnd()
        if (trimmed.isBlank() || hasTerminalPunctuation(trimmed)) return trimmed
        return "$trimmed。"
    }

    private fun enforceDisabledFormatting(
        raw: String,
        value: String,
        preferences: CorrectionPreferences,
    ): String {
        var text = value
        // When RAW contains none of a punctuation type, stripping newly invented punctuation is
        // deterministic and cannot erase a punctuation mark the user actually dictated.
        if (!preferences.addCommas && '、' !in raw && ',' !in raw) {
            text = text.replace("、", "").replace(",", "")
        }
        if (!preferences.addPeriods && '。' !in raw) {
            text = text.replace("。", "")
        }
        if (preferences.lineBreakMode == LineBreakMode.NONE && '\n' !in raw && '\r' !in raw) {
            text = text.replace("\r", "").replace("\n", "")
        }
        return text
    }

    private fun ensureRequestedLineBreaks(value: String, mode: LineBreakMode): String {
        if (mode == LineBreakMode.NONE || value.isBlank()) return value
        val separator = if (mode == LineBreakMode.SMART_SPACED) "\n\n" else "\n"
        val normalized = value.replace("\r\n", "\n").replace('\r', '\n')
        val paragraphs = normalized.split(Regex("\n+"))
        return paragraphs.joinToString(separator) { paragraph -> paragraphize(paragraph, mode, separator) }
    }

    private fun paragraphize(value: String, mode: LineBreakMode, separator: String): String {
        if (value.length < FORCE_ONE_BREAK_CHARS) return value

        val target = if (mode == LineBreakMode.SMART_SPACED) SPACED_TARGET_CHARS else SMART_TARGET_CHARS
        val minChunk = if (mode == LineBreakMode.SMART_SPACED) SPACED_MIN_CHUNK_CHARS else SMART_MIN_CHUNK_CHARS
        val maxChunk = if (mode == LineBreakMode.SMART_SPACED) SPACED_MAX_CHUNK_CHARS else SMART_MAX_CHUNK_CHARS
        val candidates = paragraphBoundaries(value)
        if (candidates.isEmpty()) return value

        val breaks = mutableListOf<Int>()
        var start = 0
        while (value.length - start >= target + minChunk) {
            val minBoundary = start + minChunk
            val maxBoundary = minOf(start + maxChunk, value.length - minChunk)
            if (minBoundary > maxBoundary) break
            val ideal = minOf(start + target, maxBoundary)
            val boundary = candidates.asSequence()
                .filter { it.index in minBoundary..maxBoundary }
                .minByOrNull { it.score(ideal) }
                ?.index
                ?: break
            if (boundary <= start || boundary >= value.length) break
            breaks += boundary
            start = boundary
        }

        if (breaks.isEmpty()) {
            val minBoundary = minChunk.coerceAtMost(value.length / 2)
            val maxBoundary = (value.length - minChunk).coerceAtLeast(value.length / 2)
            val midpoint = value.length / 2
            candidates.asSequence()
                .filter { it.index in minBoundary..maxBoundary }
                .minByOrNull { it.score(midpoint) }
                ?.index
                ?.takeIf { it in 1 until value.length }
                ?.let(breaks::add)
        }

        if (breaks.isEmpty()) return value
        val breakSet = breaks.toHashSet()
        return buildString(value.length + breaks.size * separator.length) {
            value.forEachIndexed { index, char ->
                append(char)
                if (index + 1 in breakSet) append(separator)
            }
        }
    }

    private fun paragraphBoundaries(value: String): List<Boundary> {
        val priorities = linkedMapOf<Int, Int>()
        fun add(index: Int, priority: Int) {
            if (index !in 1 until value.length) return
            priorities[index] = maxOf(priorities[index] ?: 0, priority)
        }

        value.forEachIndexed { index, char ->
            when {
                char in SENTENCE_BOUNDARIES -> add(index + 1, PRIORITY_SENTENCE)
                char in CLAUSE_BOUNDARIES -> add(index + 1, PRIORITY_CLAUSE)
            }
        }
        TOPIC_BOUNDARIES.forEach { marker ->
            var from = 0
            while (true) {
                val index = value.indexOf(marker, from)
                if (index < 0) break
                add(index, PRIORITY_TOPIC)
                from = index + marker.length
            }
        }
        CLAUSE_ENDINGS.forEach { marker ->
            var from = 0
            while (true) {
                val index = value.indexOf(marker, from)
                if (index < 0) break
                add(index + marker.length, PRIORITY_CLAUSE_ENDING)
                from = index + marker.length
            }
        }
        return priorities.map { (indexValue, priority) -> Boundary(indexValue, priority) }
    }

    private data class Boundary(val index: Int, val priority: Int) {
        fun score(ideal: Int): Int = abs(index - ideal) + when (priority) {
            PRIORITY_SENTENCE -> 0
            PRIORITY_TOPIC -> 4
            PRIORITY_CLAUSE -> 8
            PRIORITY_CLAUSE_ENDING -> 12
            else -> 32
        }
    }

    private fun hasTerminalPunctuation(value: String): Boolean {
        val last = value.trimEnd().lastOrNull() ?: return false
        return last in TERMINAL_PUNCTUATION
    }

    private val LEADING_FILLER = Regex(
        "^(?:(?:えー+|ええと|えっと|えーと|あー+|あのー+|そのー+|うーん+|んー+)\\s*[、,]?\\s*)+",
    )
    private val BOUNDARY_FILLER = Regex(
        "([。！？!?、,\\n])\\s*(?:えー+|ええと|えっと|えーと|あー+|あのー+|そのー+|うーん+|んー+)\\s*[、,]?\\s*",
    )
    private const val TERMINAL_PUNCTUATION = "。．.!！?？…」』）)]}"
    private const val SENTENCE_BOUNDARIES = "。.!！?？"
    private const val CLAUSE_BOUNDARIES = "、,；;：:"
    private val TOPIC_BOUNDARIES = listOf(
        "ちなみに", "それから", "そして", "ところで", "一方で", "一方", "ただし", "ただ", "つまり", "なので",
        "だから", "あと", "でも", "それで", "まず", "次に", "最後に", "要するに", "逆に", "さらに", "また",
        "しかし", "そのため", "その結果", "加えて", "とはいえ", "そもそも", "というか", "それとは別に",
        "もう一つ", "一つ目", "二つ目", "三つ目",
    )
    private val CLAUSE_ENDINGS = listOf(
        "にもかかわらず", "けれども", "けれど", "けど", "ものの", "というので", "場合でも", "場合は",
        "必要があり", "必要がある", "しているが", "していて", "しており", "ていて", "ており", "ほしいし",
        "であるが", "であり", "ですが", "だが", "ならば", "なら", "ながら", "つつ", "ときには", "ときに",
        "ときは", "ので", "ために", "ため", "から",
    )
    private const val PRIORITY_CLAUSE_ENDING = 1
    private const val PRIORITY_CLAUSE = 2
    private const val PRIORITY_TOPIC = 3
    private const val PRIORITY_SENTENCE = 4
    private const val FORCE_ONE_BREAK_CHARS = 28
    private const val SMART_TARGET_CHARS = 36
    private const val SPACED_TARGET_CHARS = 44
    private const val SMART_MIN_CHUNK_CHARS = 12
    private const val SPACED_MIN_CHUNK_CHARS = 16
    private const val SMART_MAX_CHUNK_CHARS = 60
    private const val SPACED_MAX_CHUNK_CHARS = 72
}
