package com.goodlight.floatingvoicebubble.correction

import com.goodlight.floatingvoicebubble.ReasoningEffort

/**
 * Timeout policy for interactive correction.
 *
 * Cloud calls use an *idle* read timeout: every received byte/chunk proves the provider is alive and
 * resets the socket read timer. We intentionally do not impose a short wall-clock deadline on a
 * reasoning model that is actively streaming progress. On-device inference still has a bounded
 * total runtime so a wedged local backend cannot occupy the inference worker forever.
 */
object CorrectionTimeoutPolicy {
    fun localCorrectionTimeoutMs(effort: ReasoningEffort): Long = when (effort) {
        ReasoningEffort.NONE, ReasoningEffort.MINIMAL, ReasoningEffort.LOW -> 45_000L
        ReasoningEffort.DEFAULT -> 60_000L
        ReasoningEffort.MEDIUM -> 75_000L
        ReasoningEffort.HIGH -> 90_000L
        ReasoningEffort.XHIGH, ReasoningEffort.MAX -> 120_000L
    }

    /** Maximum period with no response progress at all. This is not a total request deadline. */
    fun networkIdleTimeoutMs(effort: ReasoningEffort): Int = when (effort) {
        ReasoningEffort.NONE, ReasoningEffort.MINIMAL, ReasoningEffort.LOW -> 20_000
        ReasoningEffort.DEFAULT -> 30_000
        ReasoningEffort.MEDIUM -> 35_000
        ReasoningEffort.HIGH -> 45_000
        ReasoningEffort.XHIGH, ReasoningEffort.MAX -> 60_000
    }

    /**
     * Broad outer finalization safety cap. Cloud adapters still fail much sooner when response
     * progress actually stops; this cap only prevents a pathological continuously-active request
     * from occupying a finalization slot forever.
     */
    fun correctionTimeoutMs(effort: ReasoningEffort): Long = when (effort) {
        ReasoningEffort.NONE, ReasoningEffort.MINIMAL, ReasoningEffort.LOW -> 180_000L
        ReasoningEffort.DEFAULT, ReasoningEffort.MEDIUM -> 240_000L
        ReasoningEffort.HIGH, ReasoningEffort.XHIGH, ReasoningEffort.MAX -> 300_000L
    }

    /** Compatibility alias. Semantics are now idle-between-bytes, not total response time. */
    fun networkReadTimeoutMs(effort: ReasoningEffort): Int = networkIdleTimeoutMs(effort)
}
