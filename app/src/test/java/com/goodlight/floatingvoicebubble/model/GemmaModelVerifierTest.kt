package com.goodlight.floatingvoicebubble.model

import com.goodlight.floatingvoicebubble.GemmaVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GemmaModelVerifierTest {
    @Test
    fun recognizesOfficialE2BExactly() {
        val result = GemmaModelVerifier.identify(
            2_583_085_056L,
            "ab7838cdfc8f77e54d8ca45eadceb20452d9f01e4bfade03e5dce27911b27e42",
        )
        assertTrue(result.knownOfficialArtifact)
        assertEquals(GemmaVariant.E2B, result.detectedVariant)
        assertTrue(result.artifactId!!.contains("E2B"))
    }

    @Test
    fun recognizesCurrentOfficialE4BExactly() {
        val result = GemmaModelVerifier.identify(
            3_659_530_240L,
            "0B2A8980CE155FD97673D8E820B4D29D9C7D99B8FA6806F425D969B145BD52E0",
        )
        assertTrue(result.knownOfficialArtifact)
        assertEquals(GemmaVariant.E4B, result.detectedVariant)
    }

    @Test
    fun recognizesPreviousOfficialE4BRevision() {
        val result = GemmaModelVerifier.identify(
            3_654_467_584L,
            "f335f2bfd1b758dc6476db16c0f41854bd6237e2658d604cbe566bcefd00a7bc",
        )
        assertTrue(result.knownOfficialArtifact)
        assertEquals(GemmaVariant.E4B, result.detectedVariant)
    }

    @Test
    fun sizeMatchWithoutHashIsOnlyAHint() {
        val result = GemmaModelVerifier.identify(
            2_583_085_056L,
            "0".repeat(64),
        )
        assertFalse(result.knownOfficialArtifact)
        assertEquals(GemmaVariant.E2B, result.detectedVariant)
        assertNull(result.artifactId)
    }

    @Test
    fun unknownArtifactStaysUnknown() {
        val result = GemmaModelVerifier.identify(123_456_789L, "1".repeat(64))
        assertFalse(result.knownOfficialArtifact)
        assertEquals(GemmaVariant.UNKNOWN, result.detectedVariant)
    }
}
