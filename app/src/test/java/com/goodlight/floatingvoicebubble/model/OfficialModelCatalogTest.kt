package com.goodlight.floatingvoicebubble.model

import com.goodlight.floatingvoicebubble.GemmaVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

class OfficialModelCatalogTest {
    @Test
    fun catalogIdsAndUrlsAreUniqueAndHttps() {
        assertEquals(OfficialModelCatalog.all.size, OfficialModelCatalog.all.map { it.id }.toSet().size)
        assertEquals(OfficialModelCatalog.all.size, OfficialModelCatalog.all.map { it.url }.toSet().size)
        OfficialModelCatalog.all.forEach { entry ->
            assertEquals("https", URI(entry.url).scheme)
            assertTrue(entry.estimatedInstalledBytes > 0L)
        }
    }

    @Test
    fun streamingCatalogContainsOnlySupportedProductChunks() {
        assertEquals(
            AsrModelStore.SUPPORTED_CHUNKS.sorted(),
            OfficialModelCatalog.streamingAsr.mapNotNull { it.chunkMs }.sorted(),
        )
        OfficialModelCatalog.streamingAsr.forEach { entry ->
            assertTrue(entry.url.contains("nemotron-3.5-asr-streaming-0.6b-${entry.chunkMs}ms-int8-2026-06-11.tar.bz2"))
        }
    }

    @Test
    fun gemmaPinsMatchVerifierCurrentArtifacts() {
        val e2b = OfficialModelCatalog.gemmaE2B
        val e4b = OfficialModelCatalog.gemmaE4B
        assertEquals(GemmaVariant.E2B, e2b.gemmaVariant)
        assertEquals(GemmaVariant.E4B, e4b.gemmaVariant)
        listOf(e2b, e4b).forEach { entry ->
            val fingerprint = GemmaModelVerifier.identify(
                requireNotNull(entry.expectedBytes),
                requireNotNull(entry.expectedSha256),
            )
            assertTrue(fingerprint.knownOfficialArtifact)
            assertEquals(entry.gemmaVariant, fingerprint.detectedVariant)
        }
    }

    @Test
    fun reazonCatalogPointsAtReviewedSherpaRelease() {
        assertEquals(CatalogModelKind.FINAL_ASR, OfficialModelCatalog.finalAsr.kind)
        assertTrue(OfficialModelCatalog.finalAsr.url.endsWith("sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01.tar.bz2"))
    }
}
