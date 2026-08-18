package com.goodlight.floatingvoicebubble.correction

import com.goodlight.floatingvoicebubble.LineBreakMode
import com.goodlight.floatingvoicebubble.RecognitionRepairMode
import kotlin.math.abs

/**
 * Deterministic last-mile rules for user-selected formatting.
 *
 * The LLM remains responsible for semantic ASR repair and natural comma placement. Explicit
 * formatting choices, however, must not silently become no-ops because a provider was conservative.
 */
object CorrectionPostProcessor {
    fun apply(raw: String, modelOutput: String, preferences: CorrectionPreferences): String {
        var text = CorrectionGuard.sanitize(modelOutput)
        if (preferences.removeFillers) text = removeObviousLeadingFillers(text)
        if (preferences.addPeriods) text = ensureTerminalPunctuation(text)
        text = ensureRequestedLineBreaks(text, preferences.lineBreakMode)
        return text.ifBlank { raw }
    }

    /**
     * A provider gets one bounded retry when it simply echoes RAW even though the selected mode
     * explicitly asks for visible work. STRONG is deliberately allowed to challenge a RAW echo
     * even without a differing N-best candidate: an ASR's best alternatives can all share the same
     * corruption, and making N-best disagreement a prerequisite made STRONG largely inert.
     */
    fun shouldRetryNoOp(request: CorrectionRequest, modelOutput: String): Boolean {
        val sanitized = CorrectionGuard.sanitize(modelOutput)
        if (sanitized != request.rawTranscript.trim()) return false
        if (request.preferences.recognitionRepairMode == RecognitionRepairMode.STRONG) return true
        if (request.alternatives.any { it.isNotBlank() && it.trim() != request.rawTranscript.trim() }) return true
        if (
            request.preferences.addCommas &&
            request.rawTranscript.length >= COMMA_RETRY_MIN_CHARS &&
            '、' !in request.rawTranscript
        ) return true
        if (
            request.preferences.lineBreakMode != LineBreakMode.NONE &&
            request.rawTranscript.length >= LINE_BREAK_RETRY_MIN_CHARS &&
            '\n' !in request.rawTranscript
        ) return true
        return false
    }

    /**
     * Evaluates a real model response against a deliberately unambiguous context-sensitive ASR
     * repair vector. Merely returning non-empty text is not a correction test: an API can be alive
     * while the selected model ignores N-best/context and echoes RAW forever.
     */
    fun probeFailure(output: String): String? {
        val request = correctionProbeRequest()
        val processed = apply(request.rawTranscript, output, request.preferences)
        val decision = CorrectionGuard.choose(request.rawTranscript, processed, request.preferences)
        if (!decision.accepted) return "モデル出力が安全ガードに拒否されました: ${decision.reason ?: "unknown"}"
        val text = decision.text
        if (text == request.rawTranscript) return "APIは応答しましたが、補正結果がRAWと完全に同一です。"
        if (LEADING_FILLER.containsMatchIn(text)) return "フィラー除去の契約を実行していません。"
        if (!text.contains("聞き取りAI") || text.contains("取り合い")) {
            return "N-bestと周辺文脈にある明白な『聞き取りAI』への復元を実行していません。"
        }
        if (!hasTerminalPunctuation(text)) return "句点追加の契約を実行していません。"
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

    private fun removeObviousLeadingFillers(value: String): String {
        var text = value.trim()
        repeat(4) {
            val next = text.replaceFirst(LEADING_FILLER, "").trimStart()
            if (next == text) return text
            text = next
        }
        return text
    }

    private fun ensureTerminalPunctuation(value: String): String {
        val trimmed = value.trimEnd()
        if (trimmed.isBlank() || hasTerminalPunctuation(trimmed)) return trimmed
        return "$trimmed。"
    }

    /**
     * "適宜改行" is an execution contract, but deterministic code must not pretend that a Unicode
     * line-wrap opportunity is a semantic paragraph boundary.
     *
     * The model gets first choice of semantic boundaries. We then paragraphize every long remaining
     * line independently using only evidence that is meaningful for Japanese prose: sentence
     * punctuation, commas/clause punctuation, discourse/topic markers, and a conservative set of
     * clause endings. If none exists, the deterministic layer leaves the text intact instead of
     * cutting near an arbitrary character count. This prevents the old failure mode where a provider
     * error/no-op produced a context-blind split such as "そもそも\nまともに" merely because the
     * location happened to be near the target width.
     */
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

        // A moderately long paragraph can still honor the selected option, but only when we found
        // an actual linguistic boundary. There is intentionally no arbitrary code-point fallback.
        if (breaks.isEmpty() && value.length >= FORCE_ONE_BREAK_CHARS) {
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
                // A discourse marker starts the next thought, so break BEFORE it. The previous
                // implementation's arbitrary width fallback could split after the marker instead.
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
    private const val TERMINAL_PUNCTUATION = "。．.!！?？…」』）)]}"
    private const val SENTENCE_BOUNDARIES = "。.!！?？"
    private const val CLAUSE_BOUNDARIES = "、,；;：:"
    private val TOPIC_BOUNDARIES = listOf(
        "ちなみに", "それから", "そして", "ところで", "一方で", "一方", "ただし", "ただ", "つまり", "なので",
        "だから", "あと", "でも", "それで", "まず", "次に", "最後に", "要するに", "逆に", "さらに", "また",
        "しかし", "そのため", "その結果", "加えて", "とはいえ", "そもそも", "というか",
    )
    private val CLAUSE_ENDINGS = listOf(
        "けれども", "けれど", "けど", "ものの", "にもかかわらず", "というので", "ので", "ために", "ため", "から",
    )
    private const val PRIORITY_CLAUSE_ENDING = 1
    private const val PRIORITY_CLAUSE = 2
    private const val PRIORITY_TOPIC = 3
    private const val PRIORITY_SENTENCE = 4
    private const val COMMA_RETRY_MIN_CHARS = 24
    private const val LINE_BREAK_RETRY_MIN_CHARS = 32
    private const val FORCE_ONE_BREAK_CHARS = 36
    private const val SMART_TARGET_CHARS = 42
    private const val SPACED_TARGET_CHARS = 52
    private const val SMART_MIN_CHUNK_CHARS = 16
    private const val SPACED_MIN_CHUNK_CHARS = 20
    private const val SMART_MAX_CHUNK_CHARS = 68
    private const val SPACED_MAX_CHUNK_CHARS = 82
}
