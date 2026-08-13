package com.goodlight.floatingvoicebubble.correction

import com.goodlight.floatingvoicebubble.LineBreakMode
import com.goodlight.floatingvoicebubble.RecognitionRepairMode
import com.goodlight.floatingvoicebubble.dictionary.DictionaryTerm

data class CorrectionPreferences(
    val addCommas: Boolean = true,
    val addPeriods: Boolean = true,
    val removeFillers: Boolean = true,
    val polite: Boolean = false,
    val businessPolite: Boolean = false,
    val lineBreakMode: LineBreakMode = LineBreakMode.NONE,
    val recognitionRepairMode: RecognitionRepairMode = RecognitionRepairMode.NORMAL,
) {
    val registerRewriteRequested: Boolean get() = polite || businessPolite
    val lineBreakRewriteRequested: Boolean get() = lineBreakMode != LineBreakMode.NONE
    val strongRecognitionRepairRequested: Boolean get() = recognitionRepairMode == RecognitionRepairMode.STRONG
}

data class CorrectionRequest(
    val rawTranscript: String,
    val alternatives: List<String>,
    val surroundingContext: String,
    val dictionaryTerms: List<DictionaryTerm>,
    val preferences: CorrectionPreferences = CorrectionPreferences(),
    /** Used only for a bounded retry/probe when a provider returned RAW unchanged. */
    val forceCorrection: Boolean = false,
)

interface TextCorrector {
    val id: String
    fun correct(request: CorrectionRequest): String
}

object CorrectionPrompt {
    private const val BASE: String = """
あなたは日本語音声入力の訂正器です。作文家ではありません。

絶対規則:
- ユーザーが明示的に語調変換を指定していない限り、入力話者の語調、敬語レベル、タメ語、俗語、荒い表現、口癖、キャラクター性を保存する。
- 指定されていない美化、婉曲化、要約、意訳、内容追加、論旨整理をしない。
- 音声認識誤りの復元では、RAWだけでなくN-best、周辺文脈、個人辞書、文中の自己参照を根拠として使う。
- 音として近いが文脈上不自然な語句は、話者が実際に言った可能性の高い語へ戻す。複数文字・複数語にまたがる誤認識でも、根拠が強ければ一まとまりで修復する。
- 例: 「取り合いがだいぶがっつりと聞き取りミスをした」かつ文脈が音声認識AIについてなら、「聞き取りAIがだいぶがっつりと聞き取りミスをした」のような復元を検討する。
- 固有名詞は個人辞書とN-best候補を強く参照する。
- 周辺文脈は誤認識の判定に使ってよいが、話者が発言していない新しい事実・主張・理由を足してはいけない。
- 確信できない箇所は原文を保存する。
- 下記の「ユーザーが選択した整形」は許可リストではなく実行要求である。該当箇所が存在するなら必ず反映する。
- 出力は訂正後本文のみ。説明、引用符、Markdown、JSONを付けない。
"""

    fun system(request: CorrectionRequest): String = buildString {
        append(BASE.trim())
        appendLine()
        appendLine()
        appendLine("聞き取りミス修復:")
        when (request.preferences.recognitionRepairMode) {
            RecognitionRepairMode.OFF -> appendLine(
                "- 語句そのものの音声認識誤りは直さない。選択された句読点・フィラー・語調・改行だけを処理する。",
            )
            RecognitionRepairMode.NORMAL -> appendLine(
                "- 明白または文脈上かなり確度の高い音声認識誤りを直す。RAWが日本語として不自然で、N-best・周辺文脈・辞書・同一文中の語から意図語が強く推定できる場合は、文字列差が数文字あっても修復する。意味が変わる推測はしない。",
            )
            RecognitionRepairMode.STRONG -> appendLine(
                "- 強めに復元する。不自然なRAWを丸写しして安全側へ逃げず、複数語が連続して壊れていても、発音類似・N-best・周辺文脈・辞書・文意が同じ候補へ収束するなら積極的に置換する。ただし話者の意図・事実・論旨を新しく作ってはいけない。候補が複数残るなら原文を優先する。",
            )
        }
        if (request.forceCorrection) {
            appendLine(
                "- これは補正能力の確認または再試行である。RAWの単純な丸写しをしない。N-bestと明示整形を再確認し、根拠のある修正を必ず反映する。",
            )
        }
        appendLine()
        appendLine("ユーザーが選択した整形:")
        appendLine(
            if (request.preferences.addCommas) {
                "- 読点「、」が自然な可読性に必要な箇所には追加する。短すぎる文へ機械的に乱発はしない。"
            } else {
                "- 読点「、」を新たに追加しない。"
            },
        )
        appendLine(
            if (request.preferences.addPeriods) {
                "- 句点「。」を自然な文末へ追加する。文末に句点等がなく通常の平叙文なら、原則として必ず句点を付ける。"
            } else {
                "- 句点「。」を新たに追加しない。"
            },
        )
        appendLine(
            if (request.preferences.removeFillers) {
                "- 「えー」「えっと」「あのー」「そのー」等、意味を持たないフィラーは必ず除去する。意味を持つ語まで削らない。"
            } else {
                "- フィラーを勝手に削除しない。"
            },
        )
        when (request.preferences.lineBreakMode) {
            LineBreakMode.NONE -> appendLine("- 改行を新たに追加しない。原文の改行だけを保存する。")
            LineBreakMode.SMART -> appendLine(
                "- 話題・文意の区切りが明確な場所だけに適宜1回の改行を入れる。短文ごとに細切れにはしない。空行は作らない。",
            )
            LineBreakMode.SMART_SPACED -> appendLine(
                "- 話題・文意の区切りが明確な場所だけに適宜2回改行して1行分の空行を入れる。短文ごとに細切れにはしない。",
            )
        }
        when {
            request.preferences.businessPolite -> appendLine(
                "- ユーザーの明示指定として、内容を一切増減せず、社外文面にも使える自然なビジネス敬語へ変換する。過剰な定型挨拶や謝辞は追加しない。",
            )
            request.preferences.polite -> appendLine(
                "- ユーザーの明示指定として、内容を一切増減せず、自然なです・ます調へ変換する。ビジネス定型句は追加しない。",
            )
            else -> appendLine("- 敬語レベル・語調を変更しない。")
        }
    }

    fun user(request: CorrectionRequest): String = buildString {
        appendLine("[RAW]"); appendLine(request.rawTranscript); appendLine()
        appendLine("[N_BEST]")
        request.alternatives.take(8).forEachIndexed { index, text -> appendLine("${index + 1}. $text") }
        appendLine(); appendLine("[PERSONAL_DICTIONARY]")
        if (request.dictionaryTerms.isEmpty()) appendLine("(none)") else request.dictionaryTerms.take(96).forEach { item ->
            append(item.term)
            if (item.reading.isNotBlank()) append(" / ${item.reading}")
            if (item.aliases.isNotEmpty()) append(" / aliases=${item.aliases.joinToString("|")}")
            appendLine()
        }
        appendLine(); appendLine("[SURROUNDING_CONTEXT]")
        appendLine(request.surroundingContext.takeLast(1_500).ifBlank { "(none)" })
        appendLine()
        appendLine("選択された整形は『してもよい』ではなく要求事項です。該当箇所を必ず処理してください。")
        appendLine("RAWを上記の明示設定だけに従って訂正し、本文だけを返してください。")
    }
}