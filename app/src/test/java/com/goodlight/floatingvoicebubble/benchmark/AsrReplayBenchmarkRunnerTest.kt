package com.goodlight.floatingvoicebubble.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrReplayBenchmarkRunnerTest {
    @Test
    fun identicalJapaneseTextHasZeroDistanceAfterPunctuationNormalization() {
        assertEquals(
            0.0,
            AsrReplayBenchmarkRunner.normalizedEditDistance("今日はガンダム。", "今日はガンダム"),
            0.000001,
        )
    }

    @Test
    fun unrelatedTextHasLargeDistance() {
        val distance = AsrReplayBenchmarkRunner.normalizedEditDistance("今日はガンダム", "明日はラーメン")
        assertTrue(distance > 0.5)
    }
}
