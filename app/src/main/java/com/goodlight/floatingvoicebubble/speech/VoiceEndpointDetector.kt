package com.goodlight.floatingvoicebubble.speech

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

class VoiceEndpointDetector(
    private val sampleRate: Int = 16_000,
    private val speechStartMs: Int = 120,
    private val trailingSilenceMs: Int = 900,
    private val minimumSpeechMs: Int = 240,
) {
    private var noiseFloorDb = -58.0
    private var aboveThresholdMs = 0.0
    private var belowThresholdMs = 0.0
    private var speechMs = 0.0
    var hasSpeech: Boolean = false
        private set

    fun accept(samples: ShortArray, count: Int): Boolean {
        if (count <= 0) return false
        val frameMs = count.toDouble() * 1_000.0 / sampleRate
        val db = dbFs(samples, count)
        val startThreshold = max(noiseFloorDb + 11.0, -43.0)
        val keepThreshold = max(noiseFloorDb + 6.0, -50.0)

        if (!hasSpeech) {
            if (db > startThreshold) {
                aboveThresholdMs += frameMs
                if (aboveThresholdMs >= speechStartMs) {
                    hasSpeech = true
                    speechMs = aboveThresholdMs
                    belowThresholdMs = 0.0
                }
            } else {
                aboveThresholdMs = 0.0
                noiseFloorDb = (noiseFloorDb * 0.96 + db.coerceAtMost(-35.0) * 0.04).coerceIn(-75.0, -30.0)
            }
            return false
        }

        speechMs += frameMs
        if (db > keepThreshold) belowThresholdMs = 0.0 else belowThresholdMs += frameMs
        return speechMs >= minimumSpeechMs && belowThresholdMs >= trailingSilenceMs
    }

    fun reset() {
        noiseFloorDb = -58.0
        aboveThresholdMs = 0.0
        belowThresholdMs = 0.0
        speechMs = 0.0
        hasSpeech = false
    }

    private fun dbFs(samples: ShortArray, count: Int): Double {
        var sum = 0.0
        for (i in 0 until count) {
            val normalized = samples[i] / 32768.0
            sum += normalized * normalized
        }
        val rms = sqrt(sum / count).coerceAtLeast(1e-9)
        return 20.0 * log10(rms)
    }
}
