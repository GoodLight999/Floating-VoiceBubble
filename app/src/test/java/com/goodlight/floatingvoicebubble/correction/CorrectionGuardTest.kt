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

    @Test fun acceptsLargeSameScaleRepairInsteadOfUsingEditDistanceAsSemantics() {
        val raw = "現人の洗濯と温泉式の海鮮方針を原始と相談した"
        val repaired = "エンジンの設計と音声認識の改善方針をチームと相談した"
        val decision = CorrectionGuard.choose(raw, repaired)
        assertTrue(decision.accepted)
        assertEquals(repaired, decision.text)
        assertTrue(decision.normalizedDistance > 0.0)
    }

    @Test fun rejectsCatastrophicHelpfulRewriteByStructureRatherThanCharacterDistance() {
        val raw = "これマジでやばい、あとで見る"
        val runaway = "これは非常に興味深い内容です。背景を整理し、関係者全員へ共有し、今後の計画と予算と担当者を決定し、明日の会議資料まで作成してから詳細を確認いたします。さらに来月の計画も再設計して関係者全員へ説明します。"
        val decision = CorrectionGuard.choose(raw, runaway)
        assertFalse(decision.accepted)
        assertEquals(raw, decision.text)
        assertEquals("output-expanded-too-much", decision.reason)
    }

    @Test fun stripsWrapperWithoutTouchingJapaneseQuotesInsideSentence() {
        assertEquals("そのままでいい", CorrectionGuard.sanitize("```text\nそのままでいい\n```"))
        assertEquals("そのままでいい", CorrectionGuard.sanitize("「そのままでいい」"))
    }
}
