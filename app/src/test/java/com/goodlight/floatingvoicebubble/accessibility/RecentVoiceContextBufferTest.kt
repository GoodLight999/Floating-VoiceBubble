package com.goodlight.floatingvoicebubble.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentVoiceContextBufferTest {
    private val key = VoiceContextKey("com.example.chat", 7, "message")

    @Test
    fun clearedEditorStillGetsRecentVoiceContext() {
        val buffer = RecentVoiceContextBuffer(ttlMs = 10_000)
        buffer.add(key, "さっきの音声認識AIの話だけど", nowMs = 1_000)
        buffer.add(key, "LM補正が働いているか確認したい", nowMs = 2_000)

        val context = buffer.build(key, currentEditor = "", nowMs = 3_000)

        assertTrue(context.contains("[RECENT_VOICE_INPUT]"))
        assertTrue(context.contains("さっきの音声認識AIの話だけど"))
        assertTrue(context.contains("LM補正が働いているか確認したい"))
    }

    @Test
    fun editorTextAlreadyContainingRecentCommitIsNotDuplicated() {
        val buffer = RecentVoiceContextBuffer(ttlMs = 10_000)
        val previous = "まだ送信していない同じ入力欄の文章"
        buffer.add(key, previous, nowMs = 1_000)

        val context = buffer.build(key, currentEditor = previous + " 続きを入力中", nowMs = 2_000)

        assertFalse(context.contains("[RECENT_VOICE_INPUT]"))
        assertEquals(1, Regex(Regex.escape(previous)).findAll(context).count())
        assertTrue(context.contains("[CURRENT_EDITOR]"))
    }

    @Test
    fun contextNeverLeaksAcrossAnotherAppOrField() {
        val buffer = RecentVoiceContextBuffer(ttlMs = 10_000)
        buffer.add(key, "秘密ではないが別欄の文脈", nowMs = 1_000)

        assertEquals("", buffer.build(VoiceContextKey("com.other.app", 7, "message"), "", 2_000))
        assertEquals("", buffer.build(VoiceContextKey("com.example.chat", 8, "search"), "", 2_000))
    }

    @Test
    fun expiredContextIsDropped() {
        val buffer = RecentVoiceContextBuffer(ttlMs = 1_000)
        buffer.add(key, "古い文脈", nowMs = 1_000)

        assertEquals("", buffer.build(key, "", nowMs = 2_001))
    }

    @Test
    fun onlyMostRecentConfiguredUtterancesAreKept() {
        val buffer = RecentVoiceContextBuffer(ttlMs = 10_000, maxUtterances = 3)
        buffer.add(key, "one", 1_000)
        buffer.add(key, "two", 2_000)
        buffer.add(key, "three", 3_000)
        buffer.add(key, "four", 4_000)

        val context = buffer.build(key, "", 5_000)
        assertFalse(context.contains("one"))
        assertTrue(context.contains("two"))
        assertTrue(context.contains("three"))
        assertTrue(context.contains("four"))
    }

    @Test
    fun contextLengthIsBounded() {
        val buffer = RecentVoiceContextBuffer(
            ttlMs = 10_000,
            maxUtterances = 3,
            maxSingleChars = 100,
            maxContextChars = 120,
        )
        buffer.add(key, "a".repeat(100), 1_000)
        buffer.add(key, "b".repeat(100), 2_000)

        val context = buffer.build(key, "c".repeat(100), 3_000)
        assertTrue(context.length <= 120)
    }
}
