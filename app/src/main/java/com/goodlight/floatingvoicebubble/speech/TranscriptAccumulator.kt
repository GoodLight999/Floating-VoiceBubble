package com.goodlight.floatingvoicebubble.speech

/**
 * Keeps Android SpeechRecognizer's provider-imposed segments lossless from the user's perspective.
 * Providers can emit a final result while the microphone session is still active; that result is
 * committed as one segment and a fresh recognizer turn continues on the same caller-owned PCM pipe.
 */
class TranscriptAccumulator {
    private val committed = mutableListOf<String>()

    fun commit(text: String) {
        val normalized = text.trim()
        if (normalized.isNotEmpty() && committed.lastOrNull() != normalized) committed += normalized
    }

    fun display(partial: String = ""): String = join(committed + partial.trim().takeIf(String::isNotEmpty).orEmpty())

    fun finalCandidates(candidates: List<String>, fallbackPartial: String): List<String> {
        val normalized = candidates.map(String::trim).filter(String::isNotEmpty).distinct()
        val tails = normalized.ifEmpty { fallbackPartial.trim().takeIf(String::isNotEmpty)?.let(::listOf).orEmpty() }
        if (tails.isEmpty()) return committed.takeIf { it.isNotEmpty() }?.let { listOf(join(it)) }.orEmpty()
        return tails.map { tail -> join(committed + tail) }.filter(String::isNotEmpty).distinct()
    }

    fun hasContent(partial: String = ""): Boolean = committed.isNotEmpty() || partial.isNotBlank()

    private fun join(parts: List<String>): String {
        val clean = parts.filter(String::isNotBlank)
        if (clean.isEmpty()) return ""
        var result = clean.first()
        for (index in 1 until clean.size) result = merge(result, clean[index])
        return result
    }

    private fun merge(left: String, right: String): String {
        if (right.startsWith(left)) return right
        if (left.endsWith(right)) return left
        val maxOverlap = minOf(left.length, right.length, MAX_OVERLAP_CHARS)
        for (size in maxOverlap downTo MIN_OVERLAP_CHARS) {
            if (left.regionMatches(left.length - size, right, 0, size)) {
                return left + right.substring(size)
            }
        }
        return if (needsAsciiSpace(left, right)) "$left $right" else left + right
    }

    private fun needsAsciiSpace(left: String, right: String): Boolean {
        val a = left.lastOrNull() ?: return false
        val b = right.firstOrNull() ?: return false
        return a.isLetterOrDigit() && b.isLetterOrDigit() && a.code < 128 && b.code < 128
    }

    companion object {
        private const val MIN_OVERLAP_CHARS = 2
        private const val MAX_OVERLAP_CHARS = 48
    }
}
