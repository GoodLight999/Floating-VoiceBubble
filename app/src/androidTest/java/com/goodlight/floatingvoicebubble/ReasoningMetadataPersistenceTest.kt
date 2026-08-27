package com.goodlight.floatingvoicebubble

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReasoningMetadataPersistenceTest {
    @Test
    fun exactOpenRouterCapabilitySurvivesStoreRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SettingsStore(context)
        val previous = store.load()
        try {
            store.update {
                it.copy(
                    byokEndpoint = "https://openrouter.ai/api/v1/chat/completions",
                    byokModel = "deepseek/deepseek-v4-flash-0731",
                    reasoningEffort = ReasoningEffort.HIGH,
                    byokReasoningMetadataKnown = true,
                    byokReasoningEfforts = setOf(
                        ReasoningEffort.MAX,
                        ReasoningEffort.HIGH,
                        ReasoningEffort.LOW,
                    ),
                )
            }

            val reloaded = SettingsStore(context).load()
            assertTrue(reloaded.byokReasoningMetadataKnown)
            assertEquals(
                setOf(ReasoningEffort.MAX, ReasoningEffort.HIGH, ReasoningEffort.LOW),
                reloaded.byokReasoningEfforts,
            )
            assertEquals(ReasoningEffort.HIGH, reloaded.reasoningEffort)
        } finally {
            store.update { previous }
        }
    }

    @Test
    fun defaultSettingsDoNotPretendCapabilityWasCatalogVerified() {
        val defaults = AppSettings()
        assertFalse(defaults.byokReasoningMetadataKnown)
        assertTrue(defaults.byokReasoningEfforts.isEmpty())
    }
}
