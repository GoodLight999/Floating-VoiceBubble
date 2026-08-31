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

    @Test
    fun exportedThreeColumnTemplateUsesReferenceNotLiveTranscript() {
        val fields = listOf("abc-123", "ライブ認識の誤り", "正しい文字起こし")
        val column = BenchmarkReferenceStore.referenceColumnFor(fields, headerReferenceColumn = 2)
        assertEquals(2, column)
        assertEquals("正しい文字起こし", fields[column])
    }

    @Test
    fun headerlessThreeColumnRowsAlsoPreferLastColumn() {
        val fields = listOf("abc-123", "ライブ認識", "正解")
        assertEquals(2, BenchmarkReferenceStore.referenceColumnFor(fields, headerReferenceColumn = null))
    }

    @Test
    fun twoColumnRowsRemainBackwardCompatible() {
        val fields = listOf("abc-123", "正解")
        assertEquals(1, BenchmarkReferenceStore.referenceColumnFor(fields, headerReferenceColumn = null))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnterminatedQuotedField() {
        BenchmarkReferenceStore.parseDelimited("abc,\"broken", ',')
    }
}
