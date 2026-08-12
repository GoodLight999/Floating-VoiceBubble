package com.goodlight.floatingvoicebubble.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrAccuracyScorerTest {
    @Test
    fun identicalJapaneseHasZeroCerAndNoFakeWer() {
        val score = AsrAccuracyScorer.score("今日はガンダムを見る", "今日はガンダムを見る")
        assertEquals(0.0, score.strictCer, 0.0)
        assertEquals(0.0, score.contentCer, 0.0)
        assertNull(score.wer)
    }

    @Test
    fun nfkcAndCaseNormalizationAvoidsFalseErrors() {
        val score = AsrAccuracyScorer.score("ＡＢＣ１２３", "abc123")
        assertEquals(0.0, score.strictCer, 0.0)
        assertEquals(0.0, score.contentCer, 0.0)
    }

    @Test
    fun punctuationCanBeMeasuredStrictlyWithoutPollutingContentCer() {
        val score = AsrAccuracyScorer.score("今日は、ガンダムを見る。", "今日はガンダムを見る")
        assertTrue(score.strictCer > 0.0)
        assertEquals(0.0, score.contentCer, 0.0)
    }

    @Test
    fun japaneseSubstitutionProducesCharacterErrorRate() {
        val score = AsrAccuracyScorer.score("ガンダム", "ガンダメ")
        assertEquals(0.25, score.strictCer, 1e-9)
        assertEquals(0.25, score.contentCer, 1e-9)
    }

    @Test
    fun codePointDistanceDoesNotSplitSurrogatePairs() {
        val score = AsrAccuracyScorer.score("A😀B", "A😃B")
        assertEquals(1.0 / 3.0, score.strictCer, 1e-9)
        // Emoji are symbols and intentionally excluded from content CER.
        assertEquals(0.0, score.contentCer, 0.0)
    }

    @Test
    fun whitespaceDelimitedEnglishAlsoGetsWer() {
        val score = AsrAccuracyScorer.score(
            "the quick brown fox",
            "the quick red fox",
        )
        assertEquals(0.25, score.wer!!, 1e-9)
    }

    @Test
    fun emptyReferenceIsWellDefined() {
        assertEquals(0.0, AsrAccuracyScorer.score("", "").strictCer, 0.0)
        assertEquals(1.0, AsrAccuracyScorer.score("", "音声").strictCer, 0.0)
    }
}
