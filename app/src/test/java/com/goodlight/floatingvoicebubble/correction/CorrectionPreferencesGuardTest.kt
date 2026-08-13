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

    @Test
    fun explicitBusinessRewriteAllowsNormalJapaneseExpansion() {
        val business = CorrectionPreferences(businessPolite = true)
        val decision = CorrectionGuard.choose("資料見て", "資料をご確認いただけますでしょうか", business)
        assertTrue(decision.accepted)
    }

    @Test
    fun explicitRegisterRewriteStillRejectsRunawayExpansion() {
        val business = CorrectionPreferences(businessPolite = true)
        val raw = "確認して"
        val runaway = "ご確認ください。なお本件につきましては背景事情を踏まえ、今後の進め方や関係者への共有方法まで含めて慎重に検討する必要があると考えております。"
        val decision = CorrectionGuard.choose(raw, runaway, business)
        assertFalse(decision.accepted)
    }
}
