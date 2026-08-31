package com.goodlight.floatingvoicebubble.correction

import com.goodlight.floatingvoicebubble.LineBreakMode
import com.goodlight.floatingvoicebubble.RecognitionRepairMode
import com.goodlight.floatingvoicebubble.dictionary.DictionaryTerm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

enum class QualityDimension {
    CASUAL_REGISTER,
    ROUGH_REGISTER,
    POLITE_REGISTER,
    ASR_HOMOPHONE_SEGMENTATION,
    N_BEST,
    PERSONAL_DICTIONARY,
    MIXED_JA_EN,
    FILLER_ON_OFF,
    PUNCTUATION_ON_OFF,
    LINE_BREAK_MODES,
    MULTI_TOPIC_LONG,
    CLEAN_NO_OP,
    HALLUCINATION_TRAP,
}

class CorrectionQualityContractTest {
    @Test
    fun corpusCoversEveryRequiredQualityDimension() {
        assertEquals(QualityDimension.entries.toSet(), corpus.flatMap { it.dimensions }.toSet())
    }

    @Test
    fun registerCasesStayAnchoredToSpeakerStyleUnlessRewriteIsExplicit() {
        corpus.filter { dimension ->
            dimension.dimensions.any {
                it == QualityDimension.CASUAL_REGISTER ||
                    it == QualityDimension.ROUGH_REGISTER ||
                    it == QualityDimension.POLITE_REGISTER
            }
        }.forEach { case ->
            val system = CorrectionPrompt.system(case.request)
            val user = CorrectionPrompt.user(case.request)
            assertTrue(case.id, user.contains(case.request.rawTranscript))
            assertTrue(case.id, system.contains("口調・敬語レベルは変えない"))
            assertFalse(case.id, case.request.preferences.registerRewriteRequested)
        }
    }

    @Test
    fun evidencePacketCarriesNBestDictionaryAndMixedLanguageWithoutInventingEvidence() {
        val nBest = case("n-best-disambiguation")
        val nBestPrompt = CorrectionPrompt.user(nBest.request)
        assertTrue(nBestPrompt.contains("<N_BEST_ALTERNATIVES>"))
        assertTrue(nBestPrompt.contains("聞き取りAI"))

        val dictionary = case("personal-dictionary-name")
        val dictionaryPrompt = CorrectionPrompt.user(dictionary.request)
        assertTrue(dictionaryPrompt.contains("<RELEVANT_DICTIONARY>"))
        assertTrue(dictionaryPrompt.contains("Floating VoiceBubble"))
        assertTrue(dictionaryPrompt.contains("フローティング ボイスバブル"))

        val mixed = case("mixed-ja-en")
        val mixedPrompt = CorrectionPrompt.user(mixed.request)
        assertTrue(mixedPrompt.contains("OpenRouter"))
        assertTrue(mixedPrompt.contains("reasoning effort"))
    }

    @Test
    fun formattingSwitchesProduceOppositeExplicitContracts() {
        val enabled = case("formatting-enabled").request
        val disabled = case("formatting-disabled").request
        val enabledSystem = CorrectionPrompt.system(enabled)
        val disabledSystem = CorrectionPrompt.system(disabled)

        assertTrue(enabledSystem.contains("自然な読点「、」を必要な箇所に追加する"))
        assertTrue(enabledSystem.contains("自然な文末に句点「。」を追加する"))
        assertTrue(enabledSystem.contains("フィラーを削除する"))
        assertTrue(disabledSystem.contains("読点「、」を新たに追加しない"))
        assertTrue(disabledSystem.contains("句点「。」を新たに追加しない"))
        assertTrue(disabledSystem.contains("フィラーを削除しない"))
    }

    @Test
    fun everyLineBreakModeHasAnUnambiguousPromptContract() {
        val none = CorrectionPrompt.system(case("linebreak-none").request)
        val smart = CorrectionPrompt.system(case("linebreak-smart").request)
        val spaced = CorrectionPrompt.system(case("linebreak-spaced").request)

        assertTrue(none.contains("新しい改行を入れない"))
        assertTrue(smart.contains("明確な境界で適宜改行"))
        assertTrue(smart.contains("短い単一文は分割しない"))
        assertTrue(spaced.contains("段落間は1行空ける"))
        assertFalse(smart.contains("40文字"))
        assertFalse(spaced.contains("80文字"))
    }

    @Test
    fun repairAndHallucinationContractsStayEvidenceBounded() {
        val repair = case("asr-homophone-segmentation").request
        val repairSystem = CorrectionPrompt.system(repair)
        assertTrue(repairSystem.contains("複数文字・複数語にまたがる誤認識"))
        assertTrue(repairSystem.contains("根拠のない推測はしない"))

        val trap = case("hallucination-trap").request
        val trapSystem = CorrectionPrompt.system(trap)
        val trapUser = CorrectionPrompt.user(trap)
        assertTrue(trapSystem.contains("文脈にだけ存在する事実・主張を出力へ追加してはいけない"))
        assertTrue(trapUser.contains("来週の予算は100万円"))
        assertTrue(trapUser.contains("<SURROUNDING_CONTEXT_FOR_DISAMBIGUATION_ONLY>"))
        assertFalse(trap.rawTranscript.contains("100万円"))
    }

    @Test
    fun cleanNoOpIsAcceptedWithoutSyntheticFormatting() {
        val clean = case("clean-no-op").request
        val candidate = CorrectionPostProcessor.apply(
            clean.rawTranscript,
            clean.rawTranscript,
            clean.preferences,
        )
        val decision = CorrectionGuard.choose(clean.rawTranscript, candidate, clean.preferences)
        assertTrue(decision.accepted)
        assertEquals(clean.rawTranscript, decision.text)
    }

    @Test
    fun longMultiTopicPacketRemainsBoundedAndKeepsRawAnchor() {
        val long = case("multi-topic-long").request
        val user = CorrectionPrompt.user(long)
        assertTrue(user.contains("<RAW>"))
        assertTrue(user.contains(long.rawTranscript))
        assertTrue(user.contains("<SURROUNDING_CONTEXT_FOR_DISAMBIGUATION_ONLY>"))
        assertTrue(long.rawTranscript.length > 150)
        assertFalse(user.contains("DROP_SENTINEL"))
        assertTrue(user.contains("KEEP_SENTINEL"))
    }

    private fun case(id: String): CorpusCase = corpus.single { it.id == id }

    private data class CorpusCase(
        val id: String,
        val dimensions: Set<QualityDimension>,
        val request: CorrectionRequest,
    )

    companion object {
        private val basePreferences = CorrectionPreferences(
            addCommas = false,
            addPeriods = false,
            removeFillers = false,
            lineBreakMode = LineBreakMode.NONE,
            recognitionRepairMode = RecognitionRepairMode.NORMAL,
        )

        private val longRaw =
            "今日は音声入力の精度を確認していて聞き取り間違いだけは直してほしいけど話し方は変えてほしくない。" +
                "それから句読点と改行は読みやすい範囲で入れてほしい。" +
                "次の話題としてOpenRouterとZ.AIのモデル設定も確認したい。" +
                "補正に時間がかかっている間も次の発話を始められて、確定結果の順番だけは絶対に入れ替わらないようにしたい。" +
                "APIが失敗した場合は元の認識結果へ戻しつつ、どの段階で失敗したのか診断画面から分かるようにしてほしい。" +
                "最後に個人辞書の固有名詞が長い発話でも落ちないことを確かめたい。"

        private val corpus = listOf(
            CorpusCase(
                "casual-register",
                setOf(QualityDimension.CASUAL_REGISTER),
                CorrectionRequest("これマジで使いやすいんだよね", emptyList(), "", emptyList(), basePreferences),
            ),
            CorpusCase(
                "rough-register",
                setOf(QualityDimension.ROUGH_REGISTER),
                CorrectionRequest("そこ勝手に変えるなって言っただろ", emptyList(), "", emptyList(), basePreferences),
            ),
            CorpusCase(
                "polite-register",
                setOf(QualityDimension.POLITE_REGISTER),
                CorrectionRequest("こちらの設定で問題ありません", emptyList(), "", emptyList(), basePreferences),
            ),
            CorpusCase(
                "asr-homophone-segmentation",
                setOf(QualityDimension.ASR_HOMOPHONE_SEGMENTATION),
                CorrectionRequest(
                    "音声入力の取り合いが聞き取りミスをした",
                    listOf("音声入力の聞き取りAIが聞き取りミスをした"),
                    "音声認識AIについて話している",
                    emptyList(),
                    basePreferences.copy(recognitionRepairMode = RecognitionRepairMode.STRONG),
                ),
            ),
            CorpusCase(
                "n-best-disambiguation",
                setOf(QualityDimension.N_BEST),
                CorrectionRequest(
                    "取り合いを使う",
                    listOf("聞き取りAIを使う", "聞き取り合いを使う"),
                    "音声認識モデルの話",
                    emptyList(),
                    basePreferences,
                ),
            ),
            CorpusCase(
                "personal-dictionary-name",
                setOf(QualityDimension.PERSONAL_DICTIONARY),
                CorrectionRequest(
                    "フローティングボイスバブルを開く",
                    emptyList(),
                    "",
                    listOf(DictionaryTerm("Floating VoiceBubble", "フローティング ボイスバブル", listOf("FVB"))),
                    basePreferences,
                ),
            ),
            CorpusCase(
                "mixed-ja-en",
                setOf(QualityDimension.MIXED_JA_EN),
                CorrectionRequest(
                    "OpenRouterのreasoning effortを低にする",
                    emptyList(),
                    "",
                    emptyList(),
                    basePreferences,
                ),
            ),
            CorpusCase(
                "formatting-enabled",
                setOf(QualityDimension.FILLER_ON_OFF, QualityDimension.PUNCTUATION_ON_OFF),
                CorrectionRequest(
                    "えー今日はテストです",
                    emptyList(),
                    "",
                    emptyList(),
                    basePreferences.copy(addCommas = true, addPeriods = true, removeFillers = true),
                ),
            ),
            CorpusCase(
                "formatting-disabled",
                setOf(QualityDimension.FILLER_ON_OFF, QualityDimension.PUNCTUATION_ON_OFF),
                CorrectionRequest("えー今日はテストです", emptyList(), "", emptyList(), basePreferences),
            ),
            CorpusCase(
                "linebreak-none",
                setOf(QualityDimension.LINE_BREAK_MODES),
                CorrectionRequest("一つ目の話です。二つ目の話です。", emptyList(), "", emptyList(), basePreferences),
            ),
            CorpusCase(
                "linebreak-smart",
                setOf(QualityDimension.LINE_BREAK_MODES),
                CorrectionRequest(
                    "一つ目の話です。二つ目の話です。",
                    emptyList(),
                    "",
                    emptyList(),
                    basePreferences.copy(lineBreakMode = LineBreakMode.SMART),
                ),
            ),
            CorpusCase(
                "linebreak-spaced",
                setOf(QualityDimension.LINE_BREAK_MODES),
                CorrectionRequest(
                    "一つ目の話です。二つ目の話です。",
                    emptyList(),
                    "",
                    emptyList(),
                    basePreferences.copy(lineBreakMode = LineBreakMode.SMART_SPACED),
                ),
            ),
            CorpusCase(
                "multi-topic-long",
                setOf(QualityDimension.MULTI_TOPIC_LONG),
                CorrectionRequest(
                    longRaw,
                    listOf(longRaw.replace("Z.AI", "Z AI")),
                    "DROP_SENTINEL" + "前".repeat(700) + "KEEP_SENTINEL 同じ入力欄で音声入力アプリの設定を話している。",
                    emptyList(),
                    basePreferences.copy(lineBreakMode = LineBreakMode.SMART),
                ),
            ),
            CorpusCase(
                "clean-no-op",
                setOf(QualityDimension.CLEAN_NO_OP),
                CorrectionRequest("今日は晴れです", emptyList(), "", emptyList(), basePreferences),
            ),
            CorpusCase(
                "hallucination-trap",
                setOf(QualityDimension.HALLUCINATION_TRAP),
                CorrectionRequest(
                    "明日の会議について確認する",
                    emptyList(),
                    "周辺文脈だけのダミー情報: 来週の予算は100万円。",
                    emptyList(),
                    basePreferences,
                ),
            ),
        )
    }
}
