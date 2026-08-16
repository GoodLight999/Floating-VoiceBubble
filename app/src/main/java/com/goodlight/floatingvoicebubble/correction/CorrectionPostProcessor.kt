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
     * The model gets first choice of semantic boundaries. If it returned no line break at all while
     * the user explicitly requested them, add conservative paragraph breaks. Sentence boundaries are
     * preferred; for punctuation-free long Japanese dictation, Unicode line-break opportunities are
     * the final fallback. Only whitespace/newlines change — transcript characters are preserved.
     */
    private fun ensureRequestedLineBreaks(value: String, mode: LineBreakMode): String {
        if (mode == LineBreakMode.NONE || value.contains('\n')) return value
        val separator = if (mode == LineBreakMode.SMART_SPACED) "\n\n" else "\n"
        val sentences = splitSentences(value)
        val totalLength = sentences.sumOf { it.length }

        if (sentences.size < 2) {
            if (value.length < FORCE_ONE_BREAK_CHARS) return value
            val boundary = fallbackLineBoundary(value) ?: return value
            return insertBreak(value, boundary, separator)
        }
        if (totalLength < MIN_LINE_BREAK_TEXT_CHARS) return value

        val target = if (mode == LineBreakMode.SMART_SPACED) SPACED_TARGET_CHARS else SMART_TARGET_CHARS
        val builder = StringBuilder(value.length + sentences.size * separator.length)
        var currentParagraphLength = 0
        var breaks = 0

        sentences.forEachIndexed { index, sentence ->
            builder.append(sentence)
            currentParagraphLength += sentence.length
            if (index == sentences.lastIndex) return@forEachIndexed
            val nextLength = sentences[index + 1].length
            if (currentParagraphLength >= target || currentParagraphLength + nextLength > MAX_PARAGRAPH_CHARS) {
                builder.append(separator)
                currentParagraphLength = 0
                breaks += 1
            }
        }

        if (breaks > 0) return builder.toString()
        if (totalLength < FORCE_ONE_BREAK_CHARS) return value

        // Several short sentences can still form a long dictation. Insert exactly one break at the
        // sentence boundary nearest the midpoint rather than leaving the option inert.
        var cumulative = 0
        var bestBoundary = 0
        var bestDistance = Int.MAX_VALUE
        val midpoint = totalLength / 2
        for (index in 0 until sentences.lastIndex) {
            cumulative += sentences[index].length
            val distance = abs(cumulative - midpoint)
            if (distance < bestDistance) {
                bestDistance = distance
                bestBoundary = index
            }
        }
        return buildString(value.length + separator.length) {
            sentences.forEachIndexed { index, sentence ->
                append(sentence)
                if (index == bestBoundary) append(separator)
            }
        }
    }

    private fun fallbackLineBoundary(value: String): Int? {
        val midpoint = value.length / 2
        val minBoundary = (value.length * 0.28).toInt().coerceAtLeast(MIN_SIDE_CHARS)
        val maxBoundary = (value.length * 0.72).toInt().coerceAtMost(value.length - MIN_SIDE_CHARS)
        if (minBoundary >= maxBoundary) return null

        // Prefer visible clause/topic separators if the provider supplied them.
        val preferred = mutableListOf<Int>()
        value.forEachIndexed { index, char ->
            if (char in CLAUSE_BOUNDARIES) preferred += index + 1
        }
        TOPIC_BOUNDARIES.forEach { marker ->
            var from = 0
            while (true) {
                val index = value.indexOf(marker, from)
                if (index < 0) break
                preferred += index
                from = index + marker.length
            }
        }
        preferred.filter { it in minBoundary..maxBoundary }
            .minByOrNull { abs(it - midpoint) }
            ?.let { return it }

        // Punctuation-free Japanese has few lexical delimiters. BreakIterator applies Unicode line
        // breaking rules and avoids illegal boundaries better than cutting at an arbitrary code unit.
        val iterator = BreakIterator.getLineInstance(Locale.JAPANESE).apply { setText(value) }
        var boundary = iterator.first()
        var best: Int? = null
        var bestDistance = Int.MAX_VALUE
        while (boundary != BreakIterator.DONE) {
            if (boundary in minBoundary..maxBoundary) {
                val distance = abs(boundary - midpoint)
                if (distance < bestDistance) {
                    best = boundary
                    bestDistance = distance
                }
            }
            boundary = iterator.next()
        }
        return best
    }

    private fun insertBreak(value: String, boundary: Int, separator: String): String {
        val left = value.substring(0, boundary).trimEnd()
        val right = value.substring(boundary).trimStart()
        if (left.isBlank() || right.isBlank()) return value
        return left + separator + right
    }

    private fun splitSentences(value: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        value.forEach { char ->
            current.append(char)
            if (char in SENTENCE_BOUNDARIES) {
                current.toString().trim().takeIf(String::isNotEmpty)?.let(result::add)
                current.setLength(0)
            }
        }
        current.toString().trim().takeIf(String::isNotEmpty)?.let(result::add)
        return result
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
    private val TOPIC_BOUNDARIES = listOf("ちなみに", "それから", "そして", "ところで", "一方で", "ただ", "つまり", "なので", "だから", "あと")
    private const val COMMA_RETRY_MIN_CHARS = 24
    private const val LINE_BREAK_RETRY_MIN_CHARS = 32
    private const val MIN_LINE_BREAK_TEXT_CHARS = 24
    private const val FORCE_ONE_BREAK_CHARS = 36
    private const val MIN_SIDE_CHARS = 12
    private const val SMART_TARGET_CHARS = 42
    private const val SPACED_TARGET_CHARS = 52
    private const val MAX_PARAGRAPH_CHARS = 84
}