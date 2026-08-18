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
- 確信できない語句は原文を保存する。ただし、句読点・フィラー除去・改行などユーザーが明示した整形まで「確信できない」として省略してはいけない。
- 下記の「ユーザーが選択した整形」は許可リストではなく実行要求である。該当箇所が存在するなら必ず反映する。
- 出力前に、選択された各整形要求を一つずつ満たしたか内部で確認する。
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
                "- 強めに復元する。不自然なRAWを丸写しして安全側へ逃げず、複数語が連続して壊れていても、発音類似・N-best・周辺文脈・辞書・文意が同じ候補へ収束するなら積極的に置換する。文字列の見た目が大きく変わること自体を避ける理由にしない。ただし話者の意図・事実・論旨を新しく作ってはいけない。根拠のない候補が複数残る箇所だけは原文を優先する。",
            )
        }
        if (request.forceCorrection) {
            appendLine(
                "- これは補正能力の再確認である。RAWの単純な丸写しをしない。語句修復の根拠がなければ語句は維持してよいが、N-best・文脈・個人辞書を再確認し、句読点・フィラー・改行など明示された整形要求は必ず実行する。",
            )
        }
        appendLine()
        appendLine("ユーザーが選択した整形:")
        appendLine(
            if (request.preferences.addCommas) {
                "- 読点「、」が自然な可読性に必要な箇所には追加する。複数の節が続く長めの発話では、明確な節境界を読点なしのまま放置しない。短すぎる文へ機械的に乱発はしない。"
            } else {
                "- 読点「、」を新たに追加しない。"
            },
        )
        appendLine(
            if (request.preferences.addPeriods) {
                "- 句点「。」を自然な文末へ追加する。文末に句点等がなく通常の平叙文なら、原則として必ず句点を付ける。複数文の発話なら文境界にも句点を復元する。"
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
                "- 話題・文意・列挙の区切りが明確な場所へ適宜改行を入れる。回数を1回に固定しない。RAWに内部句点「。」がなく読点「、」だけでも、節・話題の境界から判断する。40文字程度以上の連続発話で複数文・複数論点があるなら少なくとも1か所は改行し、80文字以上など十分長く明確な区切りが複数ある発話では必要に応じて複数回改行する。短い単一文を無理に分割せず、短文ごとにも細切れにしない。空行は作らない。",
            )
            LineBreakMode.SMART_SPACED -> appendLine(
                "- 話題・文意・列挙の区切りが明確な場所へ2回改行して1行分の空行を適宜入れる。回数を1か所に固定しない。RAWに内部句点「。」がなく読点「、」だけでも、節・話題の境界から判断する。40文字程度以上の連続発話で複数文・複数論点があるなら少なくとも1か所は空行を入れ、80文字以上など十分長く明確な区切りが複数ある発話では必要に応じて複数の段落へ分ける。短い単一文を無理に分割せず、短文ごとにも細切れにしない。",
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