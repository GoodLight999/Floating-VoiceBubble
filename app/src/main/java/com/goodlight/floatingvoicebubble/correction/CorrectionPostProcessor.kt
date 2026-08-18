package com.goodlight.floatingvoicebubble.correction

import com.goodlight.floatingvoicebubble.LineBreakMode
import com.goodlight.floatingvoicebubble.RecognitionRepairMode
import java.text.BreakIterator
import java.util.Locale
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

    fun probeFailure(output: String): String? {
        val request = correctionProbeRequest()
        val processed = apply(request.rawTranscript, output, request.preferences)
        val decision = CorrectionGuard.choose(request.rawTranscript, processed, request.preferences)
        if (!decision.accepted) return "モデル出力が安全ガードに拒否されました: ${decision.reason ?: "unknown"}"
        val text = decision.text
        if (text == request.rawTranscript) return "APIは応答しましたが、補正結果がRAWと完全に同一です。"
        if (LEADING_FILLER.containsMatchIn(text)) return "フィラー除去の契約を実行していません。"
        if (!text.contains("ガンダム")) return "N-bestにある明白な聞き取り候補を反映していません。"
        if (!hasTerminalPunctuation(text)) return "句点追加の契約を実行していません。"
        return null
    }

    fun correctionProbeRequest(): CorrectionRequest = CorrectionRequest(
        rawTranscript = "えー今日はがんだむを見に行く",
        alternatives = listOf(
            "えー今日はがんだむを見に行く",
            "えー今日はガンダムを見に行く",
        ),
        surroundingContext = "アニメ作品のガンダムについて話している",
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
     * "適宜改行" is an execution contract, not permission for the model to maybe add one newline.
     *
     * The model still gets first choice of semantic boundaries. We then paragraphize every long
     * remaining line independently, so a single model newline cannot suppress formatting of the rest
     * of a long dictation. Sentence ends are preferred, followed by discourse/topic transitions and
     * Japanese comma/clause boundaries. Unicode line-break opportunities are the final fallback.
     *
     * This deliberately supports the common SpeechRecognizer shape where a long Japanese utterance
     * contains only "、" and no internal "。". Only newline characters are inserted; transcript
     * characters are otherwise preserved.
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

        // A moderately long single paragraph should still visibly honor the selected option even if
        // the greedy target did not fire. Use the best safe boundary near the midpoint exactly once.
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
                add(index, PRIORITY_TOPIC)
                from = index + marker.length
            }
        }

        // Punctuation-free Japanese has few lexical delimiters. BreakIterator applies Unicode line
        // breaking rules and avoids illegal surrogate/grapheme boundaries better than code-unit cuts.
        val iterator = BreakIterator.getLineInstance(Locale.JAPANESE).apply { setText(value) }
        var index = iterator.first()
        while (index != BreakIterator.DONE) {
            add(index, PRIORITY_UNICODE)
            index = iterator.next()
        }
        return priorities.map { (indexValue, priority) -> Boundary(indexValue, priority) }
    }

    private data class Boundary(val index: Int, val priority: Int) {
        fun score(ideal: Int): Int = abs(index - ideal) + when (priority) {
            PRIORITY_SENTENCE -> 0
            PRIORITY_TOPIC -> 5
            PRIORITY_CLAUSE -> 9
            else -> 24
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
        "ちなみに", "それから", "そして", "ところで", "一方で", "ただ", "つまり", "なので", "だから", "あと",
        "でも", "それで", "まず", "次に", "最後に", "要するに", "逆に",
    )
    private const val PRIORITY_UNICODE = 1
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
