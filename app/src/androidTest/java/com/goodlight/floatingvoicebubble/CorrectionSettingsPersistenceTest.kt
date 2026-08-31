package com.goodlight.floatingvoicebubble

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CorrectionSettingsPersistenceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun correctionProviderModelReasoningAndFormattingRoundTrip() {
        val store = SettingsStore(context)
        val previous = store.load()
        try {
            store.update {
                it.copy(
                    correctionMode = CorrectionMode.BYOK,
                    byokEndpoint = "https://api.z.ai/api/coding/paas/v4/chat/completions",
                    byokModel = "glm-4.7",
                    reasoningEffort = ReasoningEffort.HIGH,
                    correctionLineBreakMode = LineBreakMode.SMART_SPACED,
                    recognitionRepairMode = RecognitionRepairMode.STRONG,
                )
            }
            val runtimeReload = SettingsStore(context).load()
            assertEquals(CorrectionMode.BYOK, runtimeReload.correctionMode)
            assertEquals("https://api.z.ai/api/coding/paas/v4/chat/completions", runtimeReload.byokEndpoint)
            assertEquals("glm-4.7", runtimeReload.byokModel)
            assertEquals(ReasoningEffort.HIGH, runtimeReload.reasoningEffort)
            assertEquals(LineBreakMode.SMART_SPACED, runtimeReload.correctionLineBreakMode)
            assertEquals(RecognitionRepairMode.STRONG, runtimeReload.recognitionRepairMode)
        } finally {
            store.update { previous }
        }
    }
}
