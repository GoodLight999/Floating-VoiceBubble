package com.goodlight.floatingvoicebubble.correction

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CorrectionPreferencesGuardTest {
    @Test
    fun rejectsPunctuationThatWasExplicitlyDisabled() {
        val noPeriods = CorrectionPreferences(addPeriods = false)
        val decision = CorrectionGuard.choose("今日は晴れ", "今日は晴れ。", noPeriods)
        assertFalse(decision.accepted)
    }

    @Test
    fun acceptsPunctuationWhenEnabled() {
        val decision = CorrectionGuard.choose("今日は晴れ", "今日は晴れ。", CorrectionPreferences(addPeriods = true))
        assertTrue(decision.accepted)
    }

    @Test
    fun rejectsFillerRemovalWhenDisabled() {
        val keepFillers = CorrectionPreferences(removeFillers = false)
        val decision = CorrectionGuard.choose("えー今日は晴れ", "今日は晴れ", keepFillers)
        assertFalse(decision.accepted)
    }

    @Test
    fun explicitPoliteRewriteGetsLargerEditBudget() {
        val polite = CorrectionPreferences(polite = true)
        val decision = CorrectionGuard.choose("やってくれ", "やってください", polite)
        assertTrue(decision.accepted)
    }
}
