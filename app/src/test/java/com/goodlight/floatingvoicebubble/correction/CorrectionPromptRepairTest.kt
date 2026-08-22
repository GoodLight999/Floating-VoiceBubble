package com.goodlight.floatingvoicebubble.correction

import com.goodlight.floatingvoicebubble.LineBreakMode
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
    fun normalPromptDefinesAsrPostEditingNotGenericWriting() {
        val prompt = CorrectionPrompt.system(request(RecognitionRepairMode.NORMAL))
        assertTrue(prompt.contains("音声認識のポストエディタ"))
        assertTrue(prompt.contains("作文・要約・文章改善はしません"))
        assertTrue(prompt.contains("N-best"))
        assertTrue(prompt.contains("文脈にだけ存在する事実・主張を出力へ追加してはいけない"))
        assertTrue(prompt.contains("根拠が弱ければRAWを残す"))
    }

    @Test
    fun strongPromptAllowsEvidenceBackedMultiWordRepairWithoutFreeRewriting() {
        val prompt = CorrectionPrompt.system(request(RecognitionRepairMode.STRONG))
        assertTrue(prompt.contains("複数文字・複数語"))
        assertTrue(prompt.contains("根拠のない推測はしない"))
        assertTrue(prompt.contains("口調・敬語レベルは変えない"))
    }

    @Test
    fun offPromptForbidsLexicalRepair() {
        val prompt = CorrectionPrompt.system(request(RecognitionRepairMode.OFF))
        assertTrue(prompt.contains("語句そのものは変更しない"))
        assertFalse(prompt.contains("積極"))
    }

    @Test
    fun smartLineBreakPromptRequestsSemanticBreaksWithoutArbitraryWidthRules() {
        val prompt = CorrectionPrompt.system(
            request(RecognitionRepairMode.NORMAL).copy(
                preferences = CorrectionPreferences(
                    lineBreakMode = LineBreakMode.SMART,
                    recognitionRepairMode = RecognitionRepairMode.NORMAL,
                ),
            ),
        )
        assertTrue(prompt.contains("複数文・話題・列挙の明確な境界"))
        assertTrue(prompt.contains("短い単一文は分割しない"))
        assertFalse(prompt.contains("40文字"))
        assertFalse(prompt.contains("80文字"))
    }

    @Test
    fun userPromptSendsOnlyThreeMateriallyDifferentAlternatives() {
        val raw = "元の認識"
        val prompt = CorrectionPrompt.user(
            request(RecognitionRepairMode.NORMAL).copy(
                rawTranscript = raw,
                alternatives = listOf(raw, "候補A", "候補A", "候補B", "候補C", "候補D", "候補E"),
            ),
        )
        assertTrue(prompt.contains("1: 候補A"))
        assertTrue(prompt.contains("2: 候補B"))
        assertTrue(prompt.contains("3: 候補C"))
        assertFalse(prompt.contains("候補D"))
        assertFalse(prompt.contains("候補E"))
    }

    @Test
    fun userPromptBoundsContextToTrailingSixHundredCharacters() {
        val droppedSentinel = "DROP_CONTEXT_SENTINEL"
        val keptSentinel = "KEEP_CONTEXT_SENTINEL"
        val context = droppedSentinel + "前".repeat(700) + keptSentinel + "後".repeat(120)
        val prompt = CorrectionPrompt.user(
            request(RecognitionRepairMode.NORMAL).copy(surroundingContext = context),
        )
        assertFalse(prompt.contains(droppedSentinel))
        assertTrue(prompt.contains(keptSentinel))
        assertTrue(prompt.contains(context.takeLast(80)))
    }
}
