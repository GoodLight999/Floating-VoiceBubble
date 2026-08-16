package com.goodlight.floatingvoicebubble.correction

import com.goodlight.floatingvoicebubble.LineBreakMode
import com.goodlight.floatingvoicebubble.RecognitionRepairMode
import org.junit.Assert.assertEquals
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
    fun realWorldKikitoriAiMisrecognitionIsAcceptedInNormalMode() {
        val raw = "今思ったけど取り合いがだいぶがっつりと聞き取りミスをしたのを直せるようにしたい"
        val repaired = "今思ったけど聞き取りAIがだいぶがっつりと聞き取りミスをしたのを直せるようにしたい"
        val decision = CorrectionGuard.choose(
            raw,
            repaired,
            CorrectionPreferences(recognitionRepairMode = RecognitionRepairMode.NORMAL),
        )
        assertTrue(decision.accepted)
        assertEquals(repaired, decision.text)
    }

    @Test
    fun repairOffRejectsLexicalAsrCorrectionButStillAllowsPunctuation() {
        val off = CorrectionPreferences(recognitionRepairMode = RecognitionRepairMode.OFF)
        val raw = "取り合いが聞き取りミスをした"
        val lexical = CorrectionGuard.choose(raw, "聞き取りAIが聞き取りミスをした", off)
        assertFalse(lexical.accepted)
        assertEquals("recognition-repair-off", lexical.reason)

        val punctuation = CorrectionGuard.choose("今日は晴れ", "今日は晴れ。", off)
        assertTrue(punctuation.accepted)
    }

    @Test
    fun normalAndStrongAcceptMultiWordAcousticRepairAtComparableScale() {
        val raw = "現人の洗濯と温泉式の海鮮方針を原始と相談した"
        val repaired = "エンジンの設計と音声認識の改善方針をチームと相談した"
        val normal = CorrectionGuard.choose(
            raw,
            repaired,
            CorrectionPreferences(recognitionRepairMode = RecognitionRepairMode.NORMAL),
        )
        val strong = CorrectionGuard.choose(
            raw,
            repaired,
            CorrectionPreferences(recognitionRepairMode = RecognitionRepairMode.STRONG),
        )
        assertTrue(normal.accepted)
        assertTrue(strong.accepted)
        assertEquals(repaired, normal.text)
        assertEquals(repaired, strong.text)
    }

    @Test
    fun strongRepairStillRejectsRunawayContentExpansion() {
        val strong = CorrectionPreferences(recognitionRepairMode = RecognitionRepairMode.STRONG)
        val raw = "確認して"
        val runaway = "確認して、その結果を関係者全員へ共有し、今後の計画と予算と担当者まで決定したうえで明日の会議資料も作成しておいてください"
        val decision = CorrectionGuard.choose(raw, runaway, strong)
        assertFalse(decision.accepted)
        assertEquals("runaway-expansion", decision.reason)
    }

    @Test
    fun longUtteranceRejectsSummaryLikeContraction() {
        val raw = "今日は音声入力アプリの補正について話していて句読点と改行と聞き取りミスの修復を全部ちゃんと動くようにしたいという話をしている"
        val summarized = "補正を改善したい。"
        val decision = CorrectionGuard.choose(raw, summarized, CorrectionPreferences())
        assertFalse(decision.accepted)
        assertEquals("runaway-contraction", decision.reason)
    }

    @Test
    fun rejectsNewLineBreaksWhenDisabled() {
        val preferences = CorrectionPreferences(lineBreakMode = LineBreakMode.NONE)
        val decision = CorrectionGuard.choose("今日は晴れ。明日は雨。", "今日は晴れ。\n明日は雨。", preferences)
        assertFalse(decision.accepted)
        assertEquals("linebreak-not-allowed", decision.reason)
    }

    @Test
    fun acceptsPureFormattingLineBreaksWhenExplicitlyEnabled() {
        val preferences = CorrectionPreferences(lineBreakMode = LineBreakMode.SMART_SPACED)
        val raw = "今日は晴れ。明日は雨。週末は出かける。"
        val formatted = "今日は晴れ。\n\n明日は雨。\n\n週末は出かける。"
        val decision = CorrectionGuard.choose(raw, formatted, preferences)
        assertTrue(decision.accepted)
        assertEquals(formatted, decision.text)
    }

    @Test
    fun lineBreakPermissionDoesNotPermitRunawayExpansion() {
        val preferences = CorrectionPreferences(lineBreakMode = LineBreakMode.SMART)
        val raw = "今日は晴れ。明日は雨。"
        val rewritten = "今日は晴れ。\n明日は雨なので外出をやめ、予定をすべて取り消し、関係者へ連絡し、買い物も済ませ、家で一日中ゆっくり過ごすことにしました。さらに来週の予定も変更します。"
        val decision = CorrectionGuard.choose(raw, rewritten, preferences)
        assertFalse(decision.accepted)
    }

    @Test
    fun explicitPoliteRewriteGetsLargerStructuralBudget() {
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
