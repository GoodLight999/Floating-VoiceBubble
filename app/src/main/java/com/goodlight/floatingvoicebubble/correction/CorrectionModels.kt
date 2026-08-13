package com.goodlight.floatingvoicebubble.correction

import com.goodlight.floatingvoicebubble.dictionary.DictionaryTerm

data class CorrectionPreferences(
    val addCommas: Boolean = true,
    val addPeriods: Boolean = true,
    val removeFillers: Boolean = true,
    val polite: Boolean = false,
    val businessPolite: Boolean = false,
) {
    val registerRewriteRequested: Boolean get() = polite || businessPolite
}

data class CorrectionRequest(
    val rawTranscript: String,
    val alternatives: List<String>,
    val surroundingContext: String,
    val dictionaryTerms: List<DictionaryTerm>,
    val preferences: CorrectionPreferences = CorrectionPreferences(),
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
- 明白な音声認識誤り、明白な言い直しの整理、ユーザーが明示的に選択した整形だけを行う。
- 固有名詞は個人辞書とN-best候補を強く参照する。
- 周辺文脈は同音異義語などの判定だけに使い、話者の発言へ内容を足さない。
- 確信できない箇所は原文を保存する。
- 出力は訂正後本文のみ。説明、引用符、Markdown、JSONを付けない。
"""

    fun system(request: CorrectionRequest): String = buildString {
        append(BASE.trim())
        appendLine()
        appendLine()
        appendLine("ユーザーが選択した整形:")
        appendLine(if (request.preferences.addCommas) "- 読点「、」を自然な位置へ追加してよい。" else "- 読点「、」を新たに追加しない。")
        appendLine(if (request.preferences.addPeriods) "- 句点「。」を自然な文末へ追加してよい。" else "- 句点「。」を新たに追加しない。")
        appendLine(if (request.preferences.removeFillers) "- 「えー」「あの」「そのー」等の意味を持たないフィラーを除去してよい。" else "- フィラーを勝手に削除しない。")
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
        appendLine(); appendLine("RAWを上記の明示設定だけに従って必要最小限に訂正し、本文だけを返してください。")
    }
}
