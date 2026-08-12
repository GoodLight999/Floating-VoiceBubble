package com.goodlight.floatingvoicebubble.correction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CorrectionGuardTest {
    @Test fun acceptsMinimalCorrection() {
        val decision = CorrectionGuard.choose("今日はがんだむ見に行く", "今日はガンダム見に行く。")
        assertTrue(decision.accepted); assertEquals("今日はガンダム見に行く。", decision.text)
    }
    @Test fun rejectsHelpfulRewriteThatChangesRegisterAndContent() {
        val raw = "これマジでやばい、あとで見る"
        val decision = CorrectionGuard.choose(raw, "これは非常に興味深い内容ですので、後ほど詳しく確認いたします。")
        assertFalse(decision.accepted); assertEquals(raw, decision.text)
    }
    @Test fun stripsWrapperWithoutTouchingJapaneseQuotesInsideSentence() {
        assertEquals("そのままでいい", CorrectionGuard.sanitize("```text\nそのままでいい\n```"))
        assertEquals("そのままでいい", CorrectionGuard.sanitize("「そのままでいい」"))
    }
}
