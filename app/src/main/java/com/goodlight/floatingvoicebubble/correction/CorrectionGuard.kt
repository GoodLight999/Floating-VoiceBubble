package com.goodlight.floatingvoicebubble.correction

import com.goodlight.floatingvoicebubble.RecognitionRepairMode
import kotlin.math.max

/**
 * Output-integrity check, not content moderation.
 *
 * It catches only structurally broken model output and the explicit "do not repair words" contract.
 * Punctuation, filler handling, paragraphing and normal ASR repair are not grounds for rejecting the
 * whole model response. The historical class name is retained temporarily to avoid a risky mass
 * rename during stabilization; user-facing UI must never call this a "safety guard".
 */
object CorrectionGuard {
    data class Decision(val text: String, val accepted: Boolean, val normalizedDistance: Double, val reason: String? = null)

    fun choose(raw: String, modelOutput: String, preferences: CorrectionPreferences): Decision {
        val decision = choose(
            raw = raw,
            modelOutput = modelOutput,
            allowRegisterRewrite = preferences.registerRewriteRequested,
            ignoreLineBreaksForDistance = preferences.lineBreakRewriteRequested,
            recognitionRepairMode = preferences.recognitionRepairMode,
        )
        if (!decision.accepted) return decision

        if (
            preferences.recognitionRepairMode == RecognitionRepairMode.OFF &&
            !preferences.registerRewriteRequested &&
            lexicalSkeleton(decision.text, preferences.removeFillers) != lexicalSkeleton(raw, preferences.removeFillers)
        ) {
            return decision.copy(text = raw, accepted = false, reason = "word-changes-disabled")
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

        // Edit distance is diagnostic only. Japanese ASR repair can legitimately replace an entire
        // multi-character phrase. Reject only output that is structurally implausible for a post-edit.
        val rawLength = rawPoints.size.coerceAtLeast(1)
        val newLength = newPoints.size
        val expansionLimit = when {
            allowRegisterRewrite -> max(rawLength * 6, rawLength + 96)
            recognitionRepairMode == RecognitionRepairMode.STRONG -> max(rawLength * 5, rawLength + 80)
            else -> max(rawLength * 4, rawLength + 64)
        }
        if (newLength > expansionLimit) {
            return Decision(raw, false, normalized, "output-expanded-too-much")
        }

        // Only very large, long-utterance content loss is rejected. Normal filler removal, concise
        // punctuation repair and sentence restructuring must never trigger this check.
        if (rawLength >= 80) {
            val minimum = when {
                allowRegisterRewrite -> (rawLength * 0.18).toInt()
                recognitionRepairMode == RecognitionRepairMode.STRONG -> (rawLength * 0.22).toInt()
                else -> (rawLength * 0.25).toInt()
            }.coerceAtLeast(1)
            if (newLength < minimum) {
                return Decision(raw, false, normalized, "output-lost-too-much")
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
