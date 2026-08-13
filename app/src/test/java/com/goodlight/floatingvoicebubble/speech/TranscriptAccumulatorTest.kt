package com.goodlight.floatingvoicebubble.speech

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TranscriptAccumulatorTest {
    @Test
    fun `provider final segments are preserved before the next partial`() {
        val accumulator = TranscriptAccumulator()
        accumulator.commit("今日は長く")
        accumulator.commit("話しています")
        assertEquals("今日は長く話していますそして続き", accumulator.display("そして続き"))
    }

    @Test
    fun `final alternatives keep the committed prefix`() {
        val accumulator = TranscriptAccumulator()
        accumulator.commit("前半です")
        assertEquals(
            listOf("前半です後半です", "前半です後半でした"),
            accumulator.finalCandidates(listOf("後半です", "後半でした"), ""),
        )
    }

    @Test
    fun `provider overlap is merged instead of duplicated`() {
        val accumulator = TranscriptAccumulator()
        accumulator.commit("今日は天気が")
        assertEquals("今日は天気がいいですね", accumulator.display("天気がいいですね"))
    }

    @Test
    fun `english segments receive one separating space`() {
        val accumulator = TranscriptAccumulator()
        accumulator.commit("hello")
        assertEquals("hello world", accumulator.display("world"))
        assertTrue(accumulator.hasContent())
    }
}
