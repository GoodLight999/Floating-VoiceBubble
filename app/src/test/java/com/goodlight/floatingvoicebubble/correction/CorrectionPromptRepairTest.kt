package com.goodlight.floatingvoicebubble.correction

import com.goodlight.floatingvoicebubble.RecognitionRepairMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CorrectionPromptRepairTest {
    private fun request(mode: RecognitionRepairMode) = CorrectionRequest(
        rawTranscript = "取り合いが聞き取りミスをした",
        alternatives = listOf("取り合いが聞き取りミスをした", "聞き取りAIが聞き取りミスをした"),
        surroundingContext = "音声認識AIの精度について話している",
        dictionaryTerms = emptyList(),
        preferences = CorrectionPreferences(recognitionRepairMode = mode),
    )

    @Test
    fun normalPromptExplicitlyAllowsContextBackedMisrecognitionRepair() {
        val prompt = CorrectionPrompt.system(request(RecognitionRepairMode.NORMAL))
        assertTrue(prompt.contains("聞き取りAI"))
        assertTrue(prompt.contains("文脈上かなり確度"))
        assertTrue(prompt.contains("N-best"))
        assertTrue(prompt.contains("新しい事実・主張・理由を足してはいけない"))
    }

    @Test
    fun strongPromptAllowsMultiWordRepairWithoutAuthorizingWriting() {
        val prompt = CorrectionPrompt.system(request(RecognitionRepairMode.STRONG))
        assertTrue(prompt.contains("複数語が連続して壊れていても"))
        assertTrue(prompt.contains("話者の意図・事実・論旨を新しく作ってはいけない"))
    }

    @Test
    fun offPromptForbidsLexicalRepair() {
        val prompt = CorrectionPrompt.system(request(RecognitionRepairMode.OFF))
        assertTrue(prompt.contains("語句そのものの音声認識誤りは直さない"))
        assertFalse(prompt.contains("強めに復元する"))
    }
}