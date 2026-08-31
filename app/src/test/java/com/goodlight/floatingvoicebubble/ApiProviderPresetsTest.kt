package com.goodlight.floatingvoicebubble

import com.goodlight.floatingvoicebubble.correction.ByokEndpointResolver
import com.goodlight.floatingvoicebubble.speech.GeminiTranscribeProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiProviderPresetsTest {
    @Test
    fun correctionPresetsAreOnlyValidEditableInputs() {
        assertTrue(ApiProviderPresets.correction.size >= 10)
        assertEquals(
            ApiProviderPresets.correction.size,
            ApiProviderPresets.correction.map { it.id }.distinct().size,
        )
        ApiProviderPresets.correction.forEach { preset ->
            val resolved = ByokEndpointResolver.resolve(preset.endpoint)
            assertTrue("${preset.label}: ${resolved.generationUrl}", resolved.generationUrl.startsWith("https://"))
            assertTrue("${preset.label}: ${resolved.modelsUrl}", resolved.modelsUrl.startsWith("https://"))
        }
    }

    @Test
    fun recognitionPresetUsesSameManualFieldsAsCustomEndpoint() {
        val preset = ApiProviderPresets.recognition.first()
        assertTrue(preset.endpoint.startsWith("wss://") || preset.endpoint.startsWith("https://"))
        assertTrue(preset.model.isNotBlank())
        assertTrue(GeminiTranscribeProtocol.httpTransportEndpoint(preset.endpoint).startsWith("https://"))

        // A value absent from the preset catalog remains valid input; presets are not an allow-list.
        assertEquals(
            "https://custom.example.test/live",
            GeminiTranscribeProtocol.httpTransportEndpoint("wss://custom.example.test/live"),
        )
    }
}
