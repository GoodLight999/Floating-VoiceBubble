package com.goodlight.floatingvoicebubble

import android.content.Context

data class LastCorrectionFailure(
    val occurredAtMs: Long,
    val provider: String,
    val model: String,
    val reasoning: String,
    val latencyMs: Long?,
    val reason: String,
    val fallback: String,
)

/**
 * Persists only redacted operational metadata. Transcript, context, dictionary values and API keys
 * never enter this store. A successful model correction clears the previous failure.
 */
class CorrectionStatusStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun saveFailure(value: LastCorrectionFailure) {
        prefs.edit()
            .putLong(KEY_TIME, value.occurredAtMs)
            .putString(KEY_PROVIDER, value.provider.take(48))
            .putString(KEY_MODEL, value.model.take(160))
            .putString(KEY_REASONING, value.reasoning.take(64))
            .putLong(KEY_LATENCY, value.latencyMs ?: -1L)
            .putString(KEY_REASON, value.reason.take(240))
            .putString(KEY_FALLBACK, value.fallback.take(80))
            .apply()
    }

    fun clearFailure() {
        prefs.edit().clear().apply()
    }

    fun loadFailure(): LastCorrectionFailure? {
        val time = prefs.getLong(KEY_TIME, -1L)
        if (time <= 0L) return null
        return LastCorrectionFailure(
            occurredAtMs = time,
            provider = prefs.getString(KEY_PROVIDER, "").orEmpty(),
            model = prefs.getString(KEY_MODEL, "").orEmpty(),
            reasoning = prefs.getString(KEY_REASONING, "").orEmpty(),
            latencyMs = prefs.getLong(KEY_LATENCY, -1L).takeIf { it >= 0L },
            reason = prefs.getString(KEY_REASON, "").orEmpty(),
            fallback = prefs.getString(KEY_FALLBACK, "").orEmpty(),
        )
    }

    companion object {
        private const val PREFS = "floating_voice_bubble_last_correction_status"
        private const val KEY_TIME = "occurred_at_ms"
        private const val KEY_PROVIDER = "provider"
        private const val KEY_MODEL = "model"
        private const val KEY_REASONING = "reasoning"
        private const val KEY_LATENCY = "latency_ms"
        private const val KEY_REASON = "reason"
        private const val KEY_FALLBACK = "fallback"
    }
}
