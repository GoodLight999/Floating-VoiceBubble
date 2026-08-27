package com.goodlight.floatingvoicebubble.speech

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/** Pure wire-format adapter for Gemini 3.5 Transcribe Live. */
internal object GeminiTranscribeProtocol {
    const val MODEL = "gemini-3.5-transcribe-live"
    const val WEBSOCKET_BASE_URL =
        "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

    data class ServerEvent(
        val setupComplete: Boolean = false,
        val interimTranscript: String? = null,
        val finalTranscript: String? = null,
        val turnComplete: Boolean = false,
        val resumptionHandle: String? = null,
        val resumable: Boolean? = null,
        val goAway: Boolean = false,
    )

    fun setupMessage(customVocabulary: List<String>, resumptionHandle: String? = null): String {
        val transcription = JSONObject()
            .put("languageCodes", JSONArray())
            // VERBATIM is deliberate: sentence polishing, filler deletion and false-start cleanup
            // belong to Floating VoiceBubble's independently switchable correction stage.
            .put("mode", "VERBATIM")
        if (customVocabulary.isNotEmpty()) {
            transcription.put("customVocabulary", JSONArray(customVocabulary.take(MAX_CUSTOM_VOCABULARY)))
        }

        val setup = JSONObject()
            .put("model", "models/$MODEL")
            .put(
                "generationConfig",
                JSONObject().put("responseModalities", JSONArray().put("TEXT")),
            )
            .put("inputAudioTranscription", transcription)
            .put(
                "sessionResumption",
                JSONObject().apply {
                    resumptionHandle?.takeIf(String::isNotBlank)?.let { put("handle", it) }
                },
            )
        return JSONObject().put("setup", setup).toString()
    }

    fun audioMessage(pcm16Le: ByteArray): String = JSONObject()
        .put(
            "realtimeInput",
            JSONObject().put(
                "audio",
                JSONObject()
                    .put("data", Base64.getEncoder().encodeToString(pcm16Le))
                    .put("mimeType", "audio/pcm;rate=16000"),
            ),
        )
        .toString()

    fun audioStreamEndMessage(): String = JSONObject()
        .put("realtimeInput", JSONObject().put("audioStreamEnd", true))
        .toString()

    fun parseServerMessage(raw: String): ServerEvent {
        val root = JSONObject(raw)
        val content = root.optJSONObject("serverContent")
        val interim = content?.optJSONObject("interimInputTranscription")
            ?.optString("text")
            ?.trim()
            ?.takeIf(String::isNotEmpty)
        val final = content?.optJSONObject("inputTranscription")
            ?.optString("text")
            ?.trim()
            ?.takeIf(String::isNotEmpty)
        val resumption = root.optJSONObject("sessionResumptionUpdate")
        return ServerEvent(
            setupComplete = root.has("setupComplete"),
            interimTranscript = interim,
            finalTranscript = final,
            turnComplete = content?.optBoolean("turnComplete", false) == true,
            resumptionHandle = resumption?.optString("newHandle")?.takeIf(String::isNotBlank),
            resumable = resumption?.takeIf { it.has("resumable") }?.optBoolean("resumable"),
            goAway = root.has("goAway"),
        )
    }

    const val MAX_CUSTOM_VOCABULARY = 100
}
