package com.goodlight.floatingvoicebubble

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.goodlight.floatingvoicebubble.diagnostics.DiagnosticStatus
import com.goodlight.floatingvoicebubble.diagnostics.SelfDiagnostics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DiagnosticsRegressionTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun byokEndpointAndAppProfileSelfChecksStayGreenAndPrivate() {
        val report = SelfDiagnostics(context).run(includeExternalProbes = false)
        val endpoint = report.items.firstOrNull { it.id == "byok-endpoint-resolution" }
        val profiles = report.items.firstOrNull { it.id == "app-profile-store" }
        assertNotNull(endpoint)
        assertNotNull(profiles)
        assertEquals(DiagnosticStatus.PASS, endpoint!!.status)
        assertEquals(DiagnosticStatus.PASS, profiles!!.status)

        val redacted = report.toRedactedJson()
        assertFalse(redacted.contains("com.google.android.gm"))
        assertFalse(redacted.contains("FVB_SIGNING"))
        assertFalse(redacted.contains("apiKey", ignoreCase = true))
    }

    @Test
    fun effectiveCorrectionRouteShowsExactRedactedReasoningWireSetting() {
        val store = SettingsStore(context)
        val previous = store.load()
        try {
            store.update {
                it.copy(
                    offlineMode = false,
                    correctionMode = CorrectionMode.BYOK,
                    byokEndpoint = "https://api.z.ai/api/paas/v4/chat/completions",
                    byokModel = "glm-4.7",
                    reasoningEffort = ReasoningEffort.NONE,
                )
            }
            val report = SelfDiagnostics(context, store).run(includeExternalProbes = false)
            val route = report.items.first { it.id == "effective-correction-route" }
            assertEquals(DiagnosticStatus.PASS, route.status)
            assertTrue(route.detail.contains("model=glm-4.7"))
            assertTrue(route.detail.contains("reasoning=思考OFF"))
            assertTrue(route.detail.contains("wire=thinking.type=disabled"))
            assertFalse(route.detail.contains("apiKey", ignoreCase = true))
        } finally {
            store.update { previous }
        }
    }

    @Test
    fun legacyGemmaContentReferenceIsReportedAsUnrunnableInsteadOfNoCopy() {
        val store = SettingsStore(context)
        val previous = store.load()
        try {
            store.update {
                it.copy(
                    gemmaModelPath = "content://legacy.provider/document/old-model.litertlm",
                    gemmaVariant = GemmaVariant.E2B,
                )
            }
            val report = SelfDiagnostics(context, store).run(includeExternalProbes = false)
            val gemma = report.items.first { it.id == "gemma-model" }
            assertEquals(DiagnosticStatus.FAIL, gemma.status)
            assertTrue(gemma.detail.contains("not runnable by LiteRT-LM"))
            assertFalse(gemma.detail.contains("without app-private copy"))
        } finally {
            store.update { previous }
        }
    }
}
