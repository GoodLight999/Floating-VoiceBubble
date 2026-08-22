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
    /** Retained for deterministic diagnostic vectors; production no longer performs hidden retries. */
    val forceCorrection: Boolean = false,
)

interface TextCorrector {
    val id: String
    fun correct(request: CorrectionRequest): String
}

/**
 * ASR post-editing prompt derived from the evidence/design record in
 * docs/CORRECTION_PROMPT_RESEARCH.md. It intentionally avoids generic “improve this writing”
 * language and keeps the evidence packet small for interactive latency.
 */
object CorrectionPrompt {
    fun system(request: CorrectionRequest): String = buildString {
        appendLine("あなたは日本語音声認識のポストエディタです。作文・要約・文章改善はしません。")
        appendLine("RAWを話者の発言として扱い、必要な訂正と明示された整形だけを行い、本文だけを返してください。")
        appendLine()
        appendLine("語句訂正の規則:")
        when (request.preferences.recognitionRepairMode) {
            RecognitionRepairMode.OFF -> appendLine("- 語句そのものは変更しない。句読点・フィラー・改行・明示された口調変換だけを行う。")
            RecognitionRepairMode.NORMAL -> appendLine(
                "- 語句を変えるのは、N-best・個人辞書・発音上妥当な周辺文脈が訂正候補を十分に支持するときだけ。根拠が弱ければRAWを残す。",
            )
            RecognitionRepairMode.STRONG -> appendLine(
                "- 複数文字・複数語にまたがる誤認識も、N-best・個人辞書・文脈が同じ候補を強く支持するなら修復する。根拠のない推測はしない。",
            )
        }
        appendLine("- 周辺文脈は曖昧な候補の判定材料であり、文脈にだけ存在する事実・主張を出力へ追加してはいけない。")
        appendLine("- 話者のタメ語、荒い表現、俗語、断片文、口癖、敬語レベルを欠点として直さない。")
        appendLine("- 説明、引用符、Markdown、JSON、前置きは出力しない。")
        appendLine()
        appendLine("ユーザー指定の整形:")
        appendLine(if (request.preferences.addCommas) "- 自然な読点「、」を必要な箇所に追加する。" else "- 読点「、」を新たに追加しない。")
        appendLine(if (request.preferences.addPeriods) "- 自然な文末に句点「。」を追加する。" else "- 句点「。」を新たに追加しない。")
        appendLine(
            if (request.preferences.removeFillers) {
                "- 意味を持たない「えー」「えっと」「あのー」「そのー」等のフィラーを削除する。"
            } else {
                "- フィラーを削除しない。"
            },
        )
        when (request.preferences.lineBreakMode) {
            LineBreakMode.NONE -> appendLine("- 新しい改行を入れない。")
            LineBreakMode.SMART -> appendLine("- 複数文・話題・列挙の明確な境界で適宜改行する。短い単一文は分割しない。")
            LineBreakMode.SMART_SPACED -> appendLine("- 複数文・話題・列挙の明確な境界で段落を分け、段落間は1行空ける。短い単一文は分割しない。")
        }
        when {
            request.preferences.businessPolite -> appendLine("- 内容を増減せず、自然なビジネス敬語へ変換する。定型挨拶は足さない。")
            request.preferences.polite -> appendLine("- 内容を増減せず、自然なです・ます調へ変換する。")
            else -> appendLine("- 口調・敬語レベルは変えない。")
        }
        if (request.forceCorrection) {
            appendLine("- これは補正契約の診断入力です。根拠が明白な誤認識と指定整形を確実に処理する。")
        }
    }.trim()

    fun user(request: CorrectionRequest): String = buildString {
        val raw = request.rawTranscript.trim()
        val alternatives = request.alternatives.asSequence()
            .map(String::trim)
            .filter { it.isNotBlank() && it != raw }
            .distinct()
            .take(MAX_ALTERNATIVES)
            .toList()

        appendLine("<RAW>")
        appendLine(raw)
        appendLine("</RAW>")

        if (alternatives.isNotEmpty()) {
            appendLine("<N_BEST_ALTERNATIVES>")
            alternatives.forEachIndexed { index, value -> appendLine("${index + 1}: $value") }
            appendLine("</N_BEST_ALTERNATIVES>")
        }

        val dictionary = request.dictionaryTerms.take(MAX_DICTIONARY_TERMS)
        if (dictionary.isNotEmpty()) {
            appendLine("<RELEVANT_DICTIONARY>")
            dictionary.forEach { item ->
                append(item.term)
                if (item.reading.isNotBlank()) append(" / ${item.reading}")
                if (item.aliases.isNotEmpty()) append(" / ${item.aliases.take(4).joinToString("|")}")
                appendLine()
            }
            appendLine("</RELEVANT_DICTIONARY>")
        }

        val context = request.surroundingContext.takeLast(MAX_CONTEXT_CHARS).trim()
        if (context.isNotBlank()) {
            appendLine("<SURROUNDING_CONTEXT_FOR_DISAMBIGUATION_ONLY>")
            appendLine(context)
            appendLine("</SURROUNDING_CONTEXT_FOR_DISAMBIGUATION_ONLY>")
        }

        append("上の証拠だけを使ってRAWを訂正・整形し、完成した発言本文だけを返してください。")
    }

    private const val MAX_ALTERNATIVES = 3
    private const val MAX_DICTIONARY_TERMS = 24
    private const val MAX_CONTEXT_CHARS = 600
}
