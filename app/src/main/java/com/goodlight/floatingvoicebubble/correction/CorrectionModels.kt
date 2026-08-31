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
    val strongRecognitionRepairRequested: Boolean get() = recognitionRepairMode in setOf(
        RecognitionRepairMode.STRONG,
        RecognitionRepairMode.AGGRESSIVE,
        RecognitionRepairMode.MAXIMUM,
    )
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

/** Redacted phase timings for one provider attempt. No request/response content is retained. */
data class CorrectionAttemptTiming(
    val attempt: Int,
    val connectMs: Long? = null,
    val requestWriteMs: Long? = null,
    val responseHeadersMs: Long? = null,
    val responseBodyMs: Long? = null,
    val totalMs: Long,
) {
    fun redactedSummary(): String = buildString {
        append("attempt=").append(attempt)
        connectMs?.let { append(" connect=").append(it).append("ms") }
        requestWriteMs?.let { append(" write=").append(it).append("ms") }
        responseHeadersMs?.let { append(" headers=").append(it).append("ms") }
        responseBodyMs?.let { append(" body=").append(it).append("ms") }
        append(" total=").append(totalMs).append("ms")
    }
}

/** Redacted transport metadata returned with a successful model call. */
data class CorrectionCallMetadata(
    val attempts: Int = 1,
    val httpStatus: Int? = null,
    val responsePresent: Boolean = true,
    val attemptTimings: List<CorrectionAttemptTiming> = emptyList(),
)

data class CorrectionCallResult(
    val text: String,
    val metadata: CorrectionCallMetadata = CorrectionCallMetadata(),
)

/**
 * Structured model-call failure. It deliberately carries no transcript, surrounding context,
 * dictionary value or credential, so it is safe to persist in redacted operational diagnostics.
 */
class CorrectionCallException(
    message: String,
    val stage: String,
    val attempts: Int = 1,
    val httpStatus: Int? = null,
    val responsePresent: Boolean = false,
    val errorClass: String = "CorrectionCallException",
    val attemptTimings: List<CorrectionAttemptTiming> = emptyList(),
    cause: Throwable? = null,
) : RuntimeException(message, cause)

interface TextCorrector {
    val id: String
    fun correct(request: CorrectionRequest): String

    fun correctDetailed(request: CorrectionRequest): CorrectionCallResult = try {
        CorrectionCallResult(correct(request))
    } catch (failure: Throwable) {
        if (failure is CorrectionCallException) throw failure
        throw CorrectionCallException(
            message = failure.message ?: failure.javaClass.simpleName,
            stage = "model-call",
            attempts = 1,
            responsePresent = false,
            errorClass = failure.javaClass.simpleName,
            cause = failure,
        )
    }
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
            RecognitionRepairMode.CONSERVATIVE -> appendLine(
                "- 語句を変えるのは、N-best・個人辞書・発音の一致など強い直接根拠がある明白な誤認識だけ。少しでも迷うならRAWを残す。",
            )
            RecognitionRepairMode.NORMAL -> appendLine(
                "- 語句を変えるのは、N-best・個人辞書・発音上妥当な周辺文脈が訂正候補を十分に支持するとき。一般的な同音異義語・助詞・分かち誤りも自然に直す。根拠が弱ければRAWを残す。",
            )
            RecognitionRepairMode.STRONG -> appendLine(
                "- 複数文字・複数語にまたがる誤認識も、N-best・個人辞書・発音・文脈が候補を支持するなら強めに修復する。RAWの表面形より話された可能性が高い語列を優先する。",
            )
            RecognitionRepairMode.AGGRESSIVE -> appendLine(
                "- 積極修復。RAWを正解として保守的に守らない。文脈上不自然な単語、同音・類音語、かな漢字変換、カタカナ・英字表記、助詞、語の区切り、複数語の取り違えは、音の近さ・文法・同一入力欄の文脈から妥当な候補が立つなら大胆に置換する。N-bestや個人辞書に候補がなくても、発音と文脈の一致が強ければ修復してよい。",
            )
            RecognitionRepairMode.MAXIMUM -> appendLine(
                "- 最大修復。RAWをノイズの多いASR仮説として扱い、意味が破綻・不自然な箇所は文または節の単位で最もありそうな発話へ再構成してよい。複数箇所の誤認、脱落、余計な語、同音・類音置換、誤変換、助詞、語境界を同時に直し、RAWの文字列保存より聞き取りミスの除去を優先する。",
            )
        }
        appendLine("- 根拠のない推測はしない。補正強度が高くても、話者が言っていない新事実・主張・意見を作らない。")
        appendLine("- 周辺文脈は曖昧な候補の判定材料であり、文脈にだけ存在する事実・主張を出力へ追加してはいけない。")
        appendLine("- 話者のタメ語、荒い表現、俗語、断片文、口癖、敬語レベルを欠点として直さない。")
        appendLine("- 補正強度が高くても、要約、言い換えによる美文化、婉曲化、内容の追加、口調変更はしない。")
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
            LineBreakMode.SMART -> appendLine("- 複数文・話題・列挙の明確な境界で適宜改行する。文字数だけを理由に改行しない。短い単一文は分割しない。")
            LineBreakMode.SMART_SPACED -> appendLine("- 複数文・話題・列挙の明確な境界で段落を分け、段落間は1行空ける。文字数だけを理由に改行しない。短い単一文は分割しない。")
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

        if (request.preferences.recognitionRepairMode in setOf(RecognitionRepairMode.AGGRESSIVE, RecognitionRepairMode.MAXIMUM)) {
            appendLine("RAWの表面文字列を保存することより、音声認識の誤りを見つけて意図された発話を復元することを優先してください。")
        }
        append("上の証拠だけを使ってRAWを訂正・整形し、完成した発言本文だけを返してください。")
    }

    private const val MAX_ALTERNATIVES = 3
    private const val MAX_DICTIONARY_TERMS = 24
    private const val MAX_CONTEXT_CHARS = 600
}
