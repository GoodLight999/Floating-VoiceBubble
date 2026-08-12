package com.goodlight.floatingvoicebubble.correction

import com.goodlight.floatingvoicebubble.AppSettings
import com.goodlight.floatingvoicebubble.CorrectionMode
import org.junit.Assert.assertEquals
import org.junit.Test

class CorrectionBackendResolverTest {
    @Test
    fun offlineModeNeverSelectsCloudEvenWhenByokIsExplicit() {
        val settings = AppSettings(
            offlineMode = true,
            correctionMode = CorrectionMode.BYOK,
            byokModel = "cloud-model",
        )
        assertEquals(
            CorrectionBackend.GEMMA,
            CorrectionBackendResolver.resolve(settings, gemmaAvailable = true),
        )
        assertEquals(
            CorrectionBackend.NONE,
            CorrectionBackendResolver.resolve(settings, gemmaAvailable = false),
        )
    }

    @Test
    fun autoPrefersConfiguredByokThenGemma() {
        assertEquals(
            CorrectionBackend.BYOK,
            CorrectionBackendResolver.resolve(
                AppSettings(correctionMode = CorrectionMode.AUTO, byokModel = "cloud-model"),
                gemmaAvailable = true,
            ),
        )
        assertEquals(
            CorrectionBackend.GEMMA,
            CorrectionBackendResolver.resolve(
                AppSettings(correctionMode = CorrectionMode.AUTO),
                gemmaAvailable = true,
            ),
        )
        assertEquals(
            CorrectionBackend.NONE,
            CorrectionBackendResolver.resolve(
                AppSettings(correctionMode = CorrectionMode.AUTO),
                gemmaAvailable = false,
            ),
        )
    }

    @Test
    fun explicitGemmaRequiresModelAvailability() {
        val settings = AppSettings(correctionMode = CorrectionMode.GEMMA)
        assertEquals(CorrectionBackend.GEMMA, CorrectionBackendResolver.resolve(settings, true))
        assertEquals(CorrectionBackend.NONE, CorrectionBackendResolver.resolve(settings, false))
    }
}
