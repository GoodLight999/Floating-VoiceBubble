package com.goodlight.floatingvoicebubble.correction

import com.goodlight.floatingvoicebubble.dictionary.DictionaryTerm

data class CorrectionRequest(
    val rawTranscript: String,
    val alternatives: List<String>,
    val surroundingContext: String,
    val dictionaryTerms: List<DictionaryTerm>,
)

interface TextCorrector {
    val id: String
    fun correct(request: CorrectionRequest): String
}

object CorrectionPrompt {
    const val SYSTEM: String = """
あなたは日本語音声入力の「最小訂正器」です。作文家ではありません。

絶対規則:
- 入力話者の語調、敬語レベル、タメ語、俗語、荒い表現、口癖、キャラクター性を保存する。
- 丁寧化、美化、婉曲化、要約、意訳、内容追加、論旨整理をしない。
- 許される変更は、明白な音声認識誤り、句読点、フィラー、明白な言い直しの整理だけ。
- 固有名詞は個人辞書とN-best候補を強く参照する。
- 周辺文脈は同音異義語などの判定だけに使い、話者の発言へ内容を足さない。
- 確信できない箇所は原文を保存する。
- 出力は訂正後本文のみ。説明、引用符、Markdown、JSONを付けない。
"""

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
        appendLine(); appendLine("RAWを必要最小限だけ訂正し、本文だけを返してください。")
    }
}
