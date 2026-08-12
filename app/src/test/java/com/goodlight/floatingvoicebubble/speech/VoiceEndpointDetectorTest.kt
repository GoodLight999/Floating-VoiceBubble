package com.goodlight.floatingvoicebubble.speech

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceEndpointDetectorTest {
    private fun frame(amplitude: Int, samples: Int = 320) = ShortArray(samples) { amplitude.toShort() }
    @Test fun endsOnlyAfterSpeechThenTrailingSilence() {
        val detector = VoiceEndpointDetector(sampleRate = 16_000)
        repeat(20) { assertFalse(detector.accept(frame(20), 320)) }
        repeat(20) { assertFalse(detector.accept(frame(5_000), 320)) }
        assertTrue(detector.hasSpeech)
        repeat(44) { assertFalse(detector.accept(frame(20), 320)) }
        assertTrue(detector.accept(frame(20), 320))
    }
    @Test fun neverEndsOnSilenceAlone() {
        val detector = VoiceEndpointDetector(sampleRate = 16_000)
        repeat(200) { assertFalse(detector.accept(frame(15), 320)) }
        assertFalse(detector.hasSpeech)
    }
}
