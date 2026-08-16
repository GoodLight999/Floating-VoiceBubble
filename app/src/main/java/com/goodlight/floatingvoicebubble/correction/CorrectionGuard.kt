package com.goodlight.floatingvoicebubble.correction

import com.goodlight.floatingvoicebubble.LineBreakMode
import com.goodlight.floatingvoicebubble.RecognitionRepairMode
import kotlin.math.max

object CorrectionGuard {
    data class Decision(val text: String, val accepted: Boolean, val normalizedDistance: Double, val reason: String? = null)

    fun choose(raw: String, modelOutput: String, preferences: CorrectionPreferences): Decision {
        val decision = choose(
            raw,
            modelOutput,
            allowRegisterRewrite = preferences.registerRewriteRequested,
            ignoreLineBreaksForDistance = preferences.lineBreakRewriteRequested,
            recognitionRepairMode = preferences.recognitionRepairMode,
        )
        if (!decision.accepted) return decision
        val cleaned = decision.text
        if (!preferences.addCommas && cleaned.count { it == '、' } > raw.count { it == '、' }) {
            return decision.copy(text = raw, accepted = false, reason = "comma-not-allowed")
        }
        if (!preferences.addPeriods && cleaned.count { it == '。' } > raw.count { it == '。' }) {
            return decision.copy(text = raw, accepted = false, reason = "period-not-allowed")
        }
        if (!preferences.removeFillers && FILLERS.any { filler ->
                occurrences(cleaned, filler) < occurrences(raw, filler)
            }
        ) {
            return decision.copy(text = raw, accepted = false, reason = "filler-removal-not-allowed")
        }
        if (preferences.lineBreakMode == LineBreakMode.NONE && newlineCount(cleaned) > newlineCount(raw)) {
            return decision.copy(text = raw, accepted = false, reason = "linebreak-not-allowed")
        }
        if (
            preferences.recognitionRepairMode == RecognitionRepairMode.OFF &&
            !preferences.registerRewriteRequested &&
            lexicalSkeleton(cleaned, preferences.removeFillers) != lexicalSkeleton(raw, preferences.removeFillers)
        ) {
            return decision.copy(text = raw, accepted = false, reason = "recognition-repair-off")
        }
        return decision
    }

    fun choose(
        raw: String,
        modelOutput: String,
        allowRegisterRewrite: Boolean = false,
        ignoreLineBreaksForDistance: Boolean = false,
        recognitionRepairMode: RecognitionRepairMode = RecognitionRepairMode.NORMAL,
    ): Decision {
        val cleaned = sanitize(modelOutput)
        if (raw.isBlank()) return Decision(cleaned, cleaned.isNotBlank(), 0.0)
        if (cleaned.isBlank()) return Decision(raw, false, 1.0, "empty-output")
        if (cleaned == raw) return Decision(raw, true, 0.0)

        val rawBasis = if (ignoreLineBreaksForDistance) stripLineBreaks(raw) else raw
        val cleanedBasis = if (ignoreLineBreaksForDistance) stripLineBreaks(cleaned) else cleaned
        val rawPoints = rawBasis.codePoints().toArray()
        val newPoints = cleanedBasis.codePoints().toArray()
        val distance = levenshtein(rawPoints, newPoints)
        val normalized = distance.toDouble() / max(rawPoints.size, newPoints.size).coerceAtLeast(1)

        // Character edit distance is intentionally diagnostic only. Japanese ASR can corrupt an
        // entire phrase while preserving the intended meaning and approximate length, so rejecting
        // a correction merely because many code points changed systematically defeats STRONG repair.
        // Safety is enforced by explicit user-option invariants above and coarse runaway bounds here.
        val rawLength = rawPoints.size.coerceAtLeast(1)
        val newLength = newPoints.size
        val maxLength = when {
            allowRegisterRewrite -> max(rawLength * 4, rawLength + 48)
            recognitionRepairMode == RecognitionRepairMode.STRONG -> max(rawLength * 3, rawLength + 40)
            else -> max(rawLength * 2, rawLength + 32)
        }
        if (newLength > maxLength) {
            return Decision(raw, false, normalized, "runaway-expansion")
        }

        // A large contraction is much more characteristic of summarization/content loss than ASR
        // repair. Do not apply this to short utterances where removing fillers can be proportionally
        // large, and keep the threshold deliberately coarse rather than lexical-distance based.
        if (rawLength >= 32) {
            val minLength = when {
                allowRegisterRewrite -> (rawLength * 0.35).toInt()
                recognitionRepairMode == RecognitionRepairMode.STRONG -> (rawLength * 0.42).toInt()
                else -> (rawLength * 0.50).toInt()
            }.coerceAtLeast(1)
            if (newLength < minLength) {
                return Decision(raw, false, normalized, "runaway-contraction")
            }
        }

        return Decision(cleaned, true, normalized)
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

    private fun lexicalSkeleton(value: String, allowFillerRemoval: Boolean): String {
        var text = value
            .replace("\r", "")
            .replace("\n", "")
            .replace(Regex("[\\s、。,.!?！？・:：;；()（）『』「」【】\\[\\]]+"), "")
        if (allowFillerRemoval) FILLERS.forEach { filler -> text = text.replace(filler, "") }
        return text
    }

    private fun stripLineBreaks(value: String): String = value.replace("\r", "").replace("\n", "")

    private fun newlineCount(value: String): Int = value.count { it == '\n' }

    private fun occurrences(text: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var index = 0
        while (true) {
            index = text.indexOf(needle, index)
            if (index < 0) return count
            count += 1
            index += needle.length
        }
    }

    private fun levenshtein(a: IntArray, b: IntArray): Int {
        if (a.isEmpty()) return b.size
        if (b.isEmpty()) return a.size
        var previous = IntArray(b.size + 1) { it }
        var current = IntArray(b.size + 1)
        for (i in a.indices) {
            current[0] = i + 1
            for (j in b.indices) {
                val cost = if (a[i] == b[j]) 0 else 1
                current[j + 1] = minOf(current[j] + 1, previous[j + 1] + 1, previous[j] + cost)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.size]
    }

    private val FILLERS = listOf("えー", "ええと", "えっと", "えーと", "あー", "あのー", "そのー", "うーん", "んー", "まあ", "まー")
}