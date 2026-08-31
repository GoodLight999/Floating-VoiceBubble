package com.goodlight.floatingvoicebubble

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.goodlight.floatingvoicebubble.trace.SessionTraceStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class TraceAudioPrivacyTest {
    @Test
    fun startupCleanupRemovesUncommittedAudioButKeepsCommittedTracePair() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SessionTraceStore(context)
        val token = "privacy-${System.nanoTime()}"
        val orphanWav = File(store.audioDir, "$token-orphan.wav")
        val orphanPcm = File(store.audioDir, "$token-orphan.pcm")
        val partial = File(store.audioDir, ".$token.json.part")
        val keptJson = File(store.audioDir, "$token-kept.json")
        val keptWav = File(store.audioDir, "$token-kept.wav")
        try {
            orphanWav.writeBytes(ByteArray(80))
            orphanPcm.writeBytes(ByteArray(80))
            partial.writeText("partial")
            keptJson.writeText("{}")
            keptWav.writeBytes(ByteArray(80))

            store.cleanupOrphans()

            assertFalse(orphanWav.exists())
            assertFalse(orphanPcm.exists())
            assertFalse(partial.exists())
            assertTrue(keptJson.exists())
            assertTrue(keptWav.exists())
        } finally {
            listOf(orphanWav, orphanPcm, partial, keptJson, keptWav).forEach(File::delete)
        }
    }
}
