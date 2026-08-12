package com.goodlight.floatingvoicebubble.benchmark

import org.junit.Assert.assertEquals
import org.junit.Test

class ExternalAsrResultStoreTest {
    @Test
    fun parsesQuotedCompetitorCsv() {
        assertEquals(
            listOf("session-1", "Wispr Flow", "今日は, ガンダムを見る"),
            ExternalAsrResultStore.parseDelimited(
                "session-1,Wispr Flow,\"今日は, ガンダムを見る\"",
                ',',
            ),
        )
    }

    @Test
    fun parsesTsvWithoutChangingJapanese() {
        assertEquals(
            listOf("session-2", "Gboard", "固有名詞を試す"),
            ExternalAsrResultStore.parseDelimited("session-2\tGboard\t固有名詞を試す", '\t'),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsBrokenQuotedCsv() {
        ExternalAsrResultStore.parseDelimited("session,Gboard,\"broken", ',')
    }
}
