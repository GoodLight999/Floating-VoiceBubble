package com.goodlight.floatingvoicebubble.correction

import com.goodlight.floatingvoicebubble.ReasoningEffort

/**
 * One latency budget shared by runtime finalization, provider HTTP adapters and model verification.
 * Voice correction is interactive: a request must either finish within its selected reasoning budget
 * or fail visibly. There is intentionally no hidden second LM attempt.
 */
object CorrectionTimeoutPolicy {
    fun correctionTimeoutMs(effort: ReasoningEffort): Long = when (effort) {
        ReasoningEffort.NONE, ReasoningEffort.MINIMAL, ReasoningEffort.LOW -> 12_000L
        ReasoningEffort.DEFAULT -> 18_000L
        ReasoningEffort.MEDIUM -> 22_000L
        ReasoningEffort.HIGH -> 32_000L
        ReasoningEffort.XHIGH, ReasoningEffort.MAX -> 40_000L
    }

    fun networkReadTimeoutMs(effort: ReasoningEffort): Int =
        (correctionTimeoutMs(effort) + 2_000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}
