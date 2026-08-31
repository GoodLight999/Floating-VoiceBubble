package com.goodlight.floatingvoicebubble.correction

import com.goodlight.floatingvoicebubble.ReasoningEffort
import org.junit.Assert.assertTrue
import org.junit.Test

class CorrectionTimeoutPolicyTest {
    @Test
    fun networkReadTimeoutAlwaysPrecedesEngineDeadline() {
        ReasoningEffort.entries.forEach { effort ->
            val transport = CorrectionTimeoutPolicy.networkReadTimeoutMs(effort).toLong()
            val local = CorrectionTimeoutPolicy.localCorrectionTimeoutMs(effort)
            val engine = CorrectionTimeoutPolicy.correctionTimeoutMs(effort)
            assertTrue("$effort transport=$transport engine=$engine", transport < engine)
            assertTrue("$effort transport too small: $transport", transport >= 4_000L)
            assertTrue(
                "$effort outer safety cap is still acting like a short model deadline: local=$local engine=$engine",
                engine >= local + 120_000L,
            )
        }
    }
}
