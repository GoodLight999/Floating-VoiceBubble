package com.goodlight.floatingvoicebubble.benchmark

import java.text.Normalizer
import java.util.Locale

/** Ground-truth ASR metrics. No score is produced without an explicit reference transcript. */
data class AsrAccuracyScore(
    val strictCer: Double,
    val contentCer: Double,
    val wer: Double?,
    val referenceCodePoints: Int,
    val hypothesisCodePoints: Int,
)

object AsrAccuracyScorer {
    fun score(reference: String, hypothesis: String): AsrAccuracyScore {
        val strictReference = normalize(reference, stripPunctuation = false)
        val strictHypothesis = normalize(hypothesis, stripPunctuation = false)
        val contentReference = normalize(reference, stripPunctuation = true)
        val contentHypothesis = normalize(hypothesis, stripPunctuation = true)

        val strictRefCodePoints = strictReference.codePoints().toArray()
        val strictHypCodePoints = strictHypothesis.codePoints().toArray()
        val contentRefCodePoints = contentReference.codePoints().toArray()
        val contentHypCodePoints = contentHypothesis.codePoints().toArray()

        return AsrAccuracyScore(
            strictCer = errorRate(strictRefCodePoints, strictHypCodePoints),
            contentCer = errorRate(contentRefCodePoints, contentHypCodePoints),
            wer = wordErrorRate(reference, hypothesis),
            referenceCodePoints = strictRefCodePoints.size,
            hypothesisCodePoints = strictHypCodePoints.size,
        )
    }

    internal fun normalize(text: String, stripPunctuation: Boolean): String {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
        val out = StringBuilder(normalized.length)
        normalized.codePoints().forEach { codePoint ->
            if (Character.isWhitespace(codePoint)) return@forEach
            if (stripPunctuation && isPunctuationOrSymbol(codePoint)) return@forEach
            out.appendCodePoint(codePoint)
        }
        return out.toString()
    }

    private fun wordErrorRate(reference: String, hypothesis: String): Double? {
        val refWords = tokenizeWords(reference)
        // Japanese text normally has no spaces. Reporting a one-token "WER" would be misleading,
        // so CER remains the primary metric unless the reference actually has multiple words.
        if (refWords.size < 2) return null
        val hypWords = tokenizeWords(hypothesis)
        return errorRate(refWords, hypWords)
    }

    private fun tokenizeWords(text: String): List<String> = Normalizer.normalize(text, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .trim()
        .split(Regex("\\s+"))
        .filter(String::isNotBlank)

    private fun isPunctuationOrSymbol(codePoint: Int): Boolean = when (Character.getType(codePoint)) {
        Character.CONNECTOR_PUNCTUATION.toInt(),
        Character.DASH_PUNCTUATION.toInt(),
        Character.START_PUNCTUATION.toInt(),
        Character.END_PUNCTUATION.toInt(),
        Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
        Character.FINAL_QUOTE_PUNCTUATION.toInt(),
        Character.OTHER_PUNCTUATION.toInt(),
        Character.MATH_SYMBOL.toInt(),
        Character.CURRENCY_SYMBOL.toInt(),
        Character.MODIFIER_SYMBOL.toInt(),
        Character.OTHER_SYMBOL.toInt() -> true
        else -> false
    }

    private fun errorRate(reference: IntArray, hypothesis: IntArray): Double {
        if (reference.isEmpty()) return if (hypothesis.isEmpty()) 0.0 else 1.0
        return editDistance(reference, hypothesis).toDouble() / reference.size.toDouble()
    }

    private fun errorRate(reference: List<String>, hypothesis: List<String>): Double {
        if (reference.isEmpty()) return if (hypothesis.isEmpty()) 0.0 else 1.0
        return editDistance(reference, hypothesis).toDouble() / reference.size.toDouble()
    }

    private fun editDistance(left: IntArray, right: IntArray): Int {
        var previous = IntArray(right.size + 1) { it }
        var current = IntArray(right.size + 1)
        for (i in left.indices) {
            current[0] = i + 1
            for (j in right.indices) {
                val substitution = previous[j] + if (left[i] == right[j]) 0 else 1
                current[j + 1] = minOf(
                    previous[j + 1] + 1,
                    current[j] + 1,
                    substitution,
                )
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[right.size]
    }

    private fun <T> editDistance(left: List<T>, right: List<T>): Int {
        var previous = IntArray(right.size + 1) { it }
        var current = IntArray(right.size + 1)
        for (i in left.indices) {
            current[0] = i + 1
            for (j in right.indices) {
                val substitution = previous[j] + if (left[i] == right[j]) 0 else 1
                current[j + 1] = minOf(
                    previous[j + 1] + 1,
                    current[j] + 1,
                    substitution,
                )
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[right.size]
    }
}
