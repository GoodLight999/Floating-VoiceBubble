package com.goodlight.floatingvoicebubble.correction

import kotlin.math.abs
import kotlin.math.max

object CorrectionGuard {
    data class Decision(val text: String, val accepted: Boolean, val normalizedDistance: Double, val reason: String? = null)

    fun choose(raw: String, modelOutput: String): Decision {
        val cleaned = sanitize(modelOutput)
        if (raw.isBlank()) return Decision(cleaned, cleaned.isNotBlank(), 0.0)
        if (cleaned.isBlank()) return Decision(raw, false, 1.0, "empty-output")
        if (cleaned == raw) return Decision(raw, true, 0.0)
        val rawPoints = raw.codePoints().toArray(); val newPoints = cleaned.codePoints().toArray()
        val distance = levenshtein(rawPoints, newPoints)
        val normalized = distance.toDouble() / max(rawPoints.size, newPoints.size).coerceAtLeast(1)
        val lengthDelta = abs(newPoints.size - rawPoints.size).toDouble() / rawPoints.size.coerceAtLeast(1)
        val threshold = when { rawPoints.size <= 8 -> 0.72; rawPoints.size <= 20 -> 0.58; else -> 0.46 }
        return if (normalized <= threshold && lengthDelta <= 0.40) Decision(cleaned, true, normalized)
        else Decision(raw, false, normalized, "edit-budget-exceeded")
    }

    fun sanitize(value: String): String {
        var text = value.trim()
        if (text.startsWith("```") && text.endsWith("```")) {
            text = text.removePrefix("```").removePrefix("text").removePrefix("plaintext").removeSuffix("```").trim()
        }
        if (text.length >= 2 && ((text.first() == '"' && text.last() == '"') || (text.first() == '「' && text.last() == '」'))) {
            text = text.substring(1, text.length - 1).trim()
        }
        return text
    }

    private fun levenshtein(a: IntArray, b: IntArray): Int {
        if (a.isEmpty()) return b.size
        if (b.isEmpty()) return a.size
        var previous = IntArray(b.size + 1) { it }; var current = IntArray(b.size + 1)
        for (i in a.indices) {
            current[0] = i + 1
            for (j in b.indices) {
                val cost = if (a[i] == b[j]) 0 else 1
                current[j + 1] = minOf(current[j] + 1, previous[j + 1] + 1, previous[j] + cost)
            }
            val swap = previous; previous = current; current = swap
        }
        return previous[b.size]
    }
}
