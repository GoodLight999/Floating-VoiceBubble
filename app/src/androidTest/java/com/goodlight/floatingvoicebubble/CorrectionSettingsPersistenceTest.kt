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
    fun reasoningLineBreakAndRecognitionRepairRoundTrip() {
        val store = SettingsStore(context)
        val previous = store.load()
        try {
            store.update {
                it.copy(
                    reasoningEffort = ReasoningEffort.HIGH,
                    correctionLineBreakMode = LineBreakMode.SMART_SPACED,
                    recognitionRepairMode = RecognitionRepairMode.STRONG,
                )
            }
            val reloaded = SettingsStore(context).load()
            assertEquals(ReasoningEffort.HIGH, reloaded.reasoningEffort)
            assertEquals(LineBreakMode.SMART_SPACED, reloaded.correctionLineBreakMode)
            assertEquals(RecognitionRepairMode.STRONG, reloaded.recognitionRepairMode)
        } finally {
            store.update { previous }
        }
    }
}