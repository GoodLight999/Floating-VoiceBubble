package com.goodlight.floatingvoicebubble.correction

import com.goodlight.floatingvoicebubble.ReasoningEffort

/**
 * One latency budget shared by runtime finalization, provider HTTP adapters and model verification.
 * Voice correction is interactive: a request must either finish within its selected reasoning budget
 * or fail visibly. There is intentionally no hidden second LM attempt.
 *
 * Provider read deadlines are deliberately shorter than the FinalizationEngine deadline. This lets
 * the transport report a structured network-timeout (provider/model/attempt preserved) before the
 * outer Future watchdog has to cancel the worker and potentially leave blocking I/O alive briefly.
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
        (correctionTimeoutMs(effort) - TRANSPORT_BEFORE_ENGINE_MARGIN_MS)
            .coerceAtLeast(MIN_NETWORK_READ_TIMEOUT_MS)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()

    private const val TRANSPORT_BEFORE_ENGINE_MARGIN_MS = 1_000L
    private const val MIN_NETWORK_READ_TIMEOUT_MS = 4_000L
}
