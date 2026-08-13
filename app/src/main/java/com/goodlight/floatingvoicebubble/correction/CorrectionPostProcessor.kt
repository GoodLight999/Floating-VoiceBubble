package com.goodlight.floatingvoicebubble.correction

import com.goodlight.floatingvoicebubble.RecognitionRepairMode

/**
 * Deterministic last-mile rules for user-selected formatting.
 *
 * The LLM remains responsible for semantic ASR repair and natural comma placement. A few explicit
 * formatting choices, however, should not silently become no-ops just because a provider is overly
 * conservative. These rules only apply transformations the user explicitly enabled.
 */
object CorrectionPostProcessor {
    fun apply(raw: String, modelOutput: String, preferences: CorrectionPreferences): String {
        var text = CorrectionGuard.sanitize(modelOutput)
        if (preferences.removeFillers) text = removeObviousLeadingFillers(text)
        if (preferences.addPeriods) text = ensureTerminalPunctuation(text)
        return text.ifBlank { raw }
    }

    /**
     * STRONG repair gets one additional provider attempt when the model simply copied RAW even
     * though SpeechRecognizer supplied a genuinely different N-best candidate.
     */
    fun shouldRetryStrongNoOp(request: CorrectionRequest, modelOutput: String): Boolean {
        if (request.preferences.recognitionRepairMode != RecognitionRepairMode.STRONG) return false
        val sanitized = CorrectionGuard.sanitize(modelOutput)
        if (sanitized != request.rawTranscript.trim()) return false
        return request.alternatives.any { alternative ->
            alternative.isNotBlank() && alternative.trim() != request.rawTranscript.trim()
        }
    }

    fun probeFailure(output: String): String? {
        val request = correctionProbeRequest()
        val processed = apply(request.rawTranscript, output, request.preferences)
        val decision = CorrectionGuard.choose(request.rawTranscript, processed, request.preferences)
        if (!decision.accepted) return "モデル出力が安全ガードに拒否されました: ${decision.reason ?: "unknown"}"
        val text = decision.text
        if (text == request.rawTranscript) return "APIは応答しましたが、補正結果がRAWと完全に同一です。"
        if (LEADING_FILLER.containsMatchIn(text)) return "フィラー除去の契約を実行していません。"
        if (!text.contains("ガンダム")) return "N-bestにある明白な聞き取り候補を反映していません。"
        if (!hasTerminalPunctuation(text)) return "句点追加の契約を実行していません。"
        return null
    }

    fun correctionProbeRequest(): CorrectionRequest = CorrectionRequest(
        rawTranscript = "えー今日はがんだむを見に行く",
        alternatives = listOf(
            "えー今日はがんだむを見に行く",
            "えー今日はガンダムを見に行く",
        ),
        surroundingContext = "アニメ作品のガンダムについて話している",
        dictionaryTerms = emptyList(),
        preferences = CorrectionPreferences(
            addCommas = true,
            addPeriods = true,
            removeFillers = true,
            recognitionRepairMode = RecognitionRepairMode.STRONG,
        ),
        forceCorrection = true,
    )

    private fun removeObviousLeadingFillers(value: String): String {
        var text = value.trim()
        repeat(4) {
            val next = text.replaceFirst(LEADING_FILLER, "").trimStart()
            if (next == text) return text
            text = next
        }
        return text
    }

    private fun ensureTerminalPunctuation(value: String): String {
        val trimmed = value.trimEnd()
        if (trimmed.isBlank() || hasTerminalPunctuation(trimmed)) return trimmed
        return "$trimmed。"
    }

    private fun hasTerminalPunctuation(value: String): Boolean {
        val last = value.trimEnd().lastOrNull() ?: return false
        return last in TERMINAL_PUNCTUATION
    }

    private val LEADING_FILLER = Regex(
        "^(?:(?:えー+|ええと|えっと|えーと|あー+|あのー+|そのー+|うーん+|んー+)\\s*[、,]?\\s*)+",
    )
    private const val TERMINAL_PUNCTUATION = "。．.!！?？…」』）)]}"
}