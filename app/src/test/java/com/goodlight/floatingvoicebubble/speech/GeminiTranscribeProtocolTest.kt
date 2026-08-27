package com.goodlight.floatingvoicebubble.speech

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class GeminiTranscribeProtocolTest {
    @Test
    fun setupUsesVerbatimTextAndBoundedVocabulary() {
        val vocabulary = (1..140).map { "term-$it" }
        val root = JSONObject(GeminiTranscribeProtocol.setupMessage(vocabulary, "resume-123"))
        val setup = root.getJSONObject("setup")
        val transcription = setup.getJSONObject("inputAudioTranscription")

        assertEquals("models/gemini-3.5-transcribe-live", setup.getString("model"))
        assertEquals("TEXT", setup.getJSONObject("generationConfig").getJSONArray("responseModalities").getString(0))
        assertEquals("VERBATIM", transcription.getString("mode"))
        assertEquals(0, transcription.getJSONArray("languageCodes").length())
        assertEquals(GeminiTranscribeProtocol.MAX_CUSTOM_VOCABULARY, transcription.getJSONArray("customVocabulary").length())
        assertEquals("resume-123", setup.getJSONObject("sessionResumption").getString("handle"))
    }

    @Test
    fun manualModelIsUsedWithoutRequiringAPreset() {
        val root = JSONObject(
            GeminiTranscribeProtocol.setupMessage(
                customVocabulary = emptyList(),
                model = "models/custom-transcribe-model",
            ),
        )
        assertEquals("models/custom-transcribe-model", root.getJSONObject("setup").getString("model"))
    }

    @Test
    fun secureWebSocketEndpointIsNormalizedOnlyAtTransportBoundary() {
        assertEquals(
            "https://example.com/ws/live?mode=test",
            GeminiTranscribeProtocol.httpTransportEndpoint("wss://example.com/ws/live?mode=test"),
        )
        assertEquals(
            "https://example.com/ws/live",
            GeminiTranscribeProtocol.httpTransportEndpoint("https://example.com/ws/live"),
        )
        assertThrows(IllegalArgumentException::class.java) {
            GeminiTranscribeProtocol.httpTransportEndpoint("ws://example.com/ws/live")
        }
        assertThrows(IllegalArgumentException::class.java) {
            GeminiTranscribeProtocol.httpTransportEndpoint("https://user:secret@example.com/ws/live")
        }
    }

    @Test
    fun audioPayloadIsExactPcmBytesAndDeclares16Khz() {
        val pcm = byteArrayOf(0, 1, -1, 42, 100, -100)
        val root = JSONObject(GeminiTranscribeProtocol.audioMessage(pcm))
        val audio = root.getJSONObject("realtimeInput").getJSONObject("audio")

        assertEquals("audio/pcm;rate=16000", audio.getString("mimeType"))
        assertTrue(pcm.contentEquals(Base64.getDecoder().decode(audio.getString("data"))))
    }

    @Test
    fun parsesInterimFinalResumptionAndGoAwayWithoutConfusingThem() {
        val interim = GeminiTranscribeProtocol.parseServerMessage(
            """{"serverContent":{"interimInputTranscription":{"text":"途中"}}}""",
        )
        assertEquals("途中", interim.interimTranscript)
        assertEquals(null, interim.finalTranscript)

        val final = GeminiTranscribeProtocol.parseServerMessage(
            """{"serverContent":{"inputTranscription":{"text":"確定"},"turnComplete":true},"sessionResumptionUpdate":{"newHandle":"h2","resumable":true}}""",
        )
        assertEquals("確定", final.finalTranscript)
        assertTrue(final.turnComplete)
        assertEquals("h2", final.resumptionHandle)
        assertEquals(true, final.resumable)

        val setup = GeminiTranscribeProtocol.parseServerMessage("""{"setupComplete":{}}""")
        assertTrue(setup.setupComplete)
        assertFalse(setup.goAway)

        val goAway = GeminiTranscribeProtocol.parseServerMessage("""{"goAway":{"timeLeft":"10s"}}""")
        assertTrue(goAway.goAway)
    }

    @Test
    fun streamEndIsExplicitAndContainsNoAudio() {
        val root = JSONObject(GeminiTranscribeProtocol.audioStreamEndMessage())
        val input = root.getJSONObject("realtimeInput")
        assertTrue(input.getBoolean("audioStreamEnd"))
        assertFalse(input.has("audio"))
    }
}
