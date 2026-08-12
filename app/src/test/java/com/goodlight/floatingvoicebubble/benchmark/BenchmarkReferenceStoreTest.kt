package com.goodlight.floatingvoicebubble.benchmark

import org.junit.Assert.assertEquals
import org.junit.Test

class BenchmarkReferenceStoreTest {
    @Test
    fun parsesTsvWithoutDestroyingJapaneseText() {
        assertEquals(
            listOf("abc-123", "今日はガンダムを見る。"),
            BenchmarkReferenceStore.parseDelimited("abc-123\t今日はガンダムを見る。", '\t'),
        )
    }

    @Test
    fun parsesQuotedCsvAndEscapedQuotes() {
        assertEquals(
            listOf("abc", "彼は\"ガンダム\"と言った, 本当に"),
            BenchmarkReferenceStore.parseDelimited(
                "abc,\"彼は\"\"ガンダム\"\"と言った, 本当に\"",
                ',',
            ),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnterminatedQuotedField() {
        BenchmarkReferenceStore.parseDelimited("abc,\"broken", ',')
    }
}
