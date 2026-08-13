package com.goodlight.floatingvoicebubble.speech

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceEndpointDetectorTest {
    private fun frame(amplitude: Int, samples: Int = 320) = ShortArray(samples) { amplitude.toShort() }

    @Test
    fun shortUtteranceEndsAfterConservativeTrailingSilence() {
        val detector = VoiceEndpointDetector(sampleRate = 16_000)
        repeat(20) { assertFalse(detector.accept(frame(20), 320)) }
        repeat(100) { assertFalse(detector.accept(frame(5_000), 320)) }
        assertTrue(detector.hasSpeech)
        repeat(69) { assertFalse(detector.accept(frame(20), 320)) }
        assertTrue(detector.accept(frame(20), 320))
    }

    @Test
    fun longDictationSurvivesNaturalPauseButStillFinalizes() {
        val detector = VoiceEndpointDetector(sampleRate = 16_000)
        repeat(500) { detector.accept(frame(5_000), 320) } // 10 s speech

        var ended = false
        repeat(90) { ended = ended || detector.accept(frame(20), 320) } // 1.8 s pause
        assertFalse("Natural pauses inside long dictation must not end the session", ended)

        repeat(21) { ended = ended || detector.accept(frame(20), 320) } // 2.22 s total
        assertTrue("Sustained silence should still auto-finalize", ended)
    }

    @Test
    fun neverEndsOnSilenceAlone() {
        val detector = VoiceEndpointDetector(sampleRate = 16_000)
        repeat(200) { assertFalse(detector.accept(frame(15), 320)) }
        assertFalse(detector.hasSpeech)
    }
}
