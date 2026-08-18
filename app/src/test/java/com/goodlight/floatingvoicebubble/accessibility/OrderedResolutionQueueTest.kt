package com.goodlight.floatingvoicebubble.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderedResolutionQueueTest {
    @Test
    fun laterResultWaitsForEarlierThenDrainsInOrder() {
        val queue = OrderedResolutionQueue<Long, String>()
        queue.register(1)
        queue.register(2)

        assertTrue(queue.resolve(2, "second").isEmpty())
        assertEquals(listOf(1L to "first", 2L to "second"), queue.resolve(1, "first"))
        assertEquals(0, queue.size())
    }

    @Test
    fun rawDiscardOfEarlierItemReleasesResolvedLaterItem() {
        val queue = OrderedResolutionQueue<Long, String>()
        queue.register(1)
        queue.register(2)
        assertTrue(queue.resolve(2, "second").isEmpty())

        assertEquals(listOf(2L to "second"), queue.discard(1))
        assertFalse(queue.contains(1))
        assertFalse(queue.contains(2))
    }

    @Test
    fun timeoutFallbackStillPreservesUtteranceOrder() {
        val queue = OrderedResolutionQueue<Long, String>()
        queue.register(10)
        queue.register(11)
        assertTrue(queue.resolve(11, "second-success").isEmpty())

        assertEquals(
            listOf(10L to "first-raw-timeout", 11L to "second-success"),
            queue.resolve(10, "first-raw-timeout"),
        )
    }

    @Test
    fun firstResolutionWinsAndLateModelResultCannotOverwriteTimeout() {
        val queue = OrderedResolutionQueue<Long, String>()
        queue.register(1)

        assertEquals(listOf(1L to "raw-timeout"), queue.resolve(1, "raw-timeout"))
        assertTrue(queue.resolve(1, "late-model-output").isEmpty())
    }

    @Test
    fun cancellationDropsAllAndLateResultsStayDropped() {
        val queue = OrderedResolutionQueue<Long, String>()
        queue.register(1)
        queue.register(2)
        queue.clear()

        assertTrue(queue.resolve(1, "late-first").isEmpty())
        assertTrue(queue.resolve(2, "late-second").isEmpty())
        assertEquals(0, queue.size())
    }
}
