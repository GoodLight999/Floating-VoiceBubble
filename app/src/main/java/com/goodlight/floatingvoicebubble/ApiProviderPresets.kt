package com.goodlight.floatingvoicebubble

/**
 * Presets are input helpers only. They are never an allow-list: every field remains editable and
 * unknown/custom endpoints continue through the same manual BYOK path.
 */
data class CorrectionApiPreset(
    val id: String,
    val label: String,
    val endpoint: String,
)

data class RecognitionApiPreset(
    val id: String,
    val label: String,
    val endpoint: String,
    val model: String,
)

object ApiProviderPresets {
    /**
     * Built-in text-correction shortcuts. Endpoint values are deliberately full generation/base
     * URLs accepted by ByokEndpointResolver; selecting one does not lock or hide the URL field.
     */
    val correction: List<CorrectionApiPreset> = listOf(
        CorrectionApiPreset("openai", "OpenAI", "https://api.openai.com/v1/chat/completions"),
        CorrectionApiPreset("openrouter", "OpenRouter", "https://openrouter.ai/api/v1/chat/completions"),
        CorrectionApiPreset("anthropic", "Anthropic", "https://api.anthropic.com/v1/messages"),
        CorrectionApiPreset("gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta"),
        CorrectionApiPreset("deepseek", "DeepSeek", "https://api.deepseek.com/chat/completions"),
        CorrectionApiPreset("zai", "Z.AI", "https://api.z.ai/api/paas/v4/chat/completions"),
        CorrectionApiPreset("zai-coding", "Z.AI Coding Plan", "https://api.z.ai/api/coding/paas/v4/chat/completions"),
        CorrectionApiPreset("groq", "Groq", "https://api.groq.com/openai/v1/chat/completions"),
        CorrectionApiPreset("mistral", "Mistral", "https://api.mistral.ai/v1/chat/completions"),
        CorrectionApiPreset("xai", "xAI", "https://api.x.ai/v1/chat/completions"),
        CorrectionApiPreset("nvidia", "NVIDIA NIM", "https://integrate.api.nvidia.com/v1/chat/completions"),
        CorrectionApiPreset("huggingface", "Hugging Face Router", "https://router.huggingface.co/v1/chat/completions"),
    )

    /**
     * Streaming speech adapters have provider-specific wire formats. The screen is still manual;
     * this first preset simply fills the endpoint/model for the implemented Gemini Live adapter.
     * More protocol adapters can append presets without creating provider-specific settings pages.
     */
    val recognition: List<RecognitionApiPreset> = listOf(
        RecognitionApiPreset(
            id = "gemini-35-transcribe-live",
            label = "Google Gemini 3.5 Transcribe Live",
            endpoint = DEFAULT_CLOUD_RECOGNITION_ENDPOINT,
            model = DEFAULT_CLOUD_RECOGNITION_MODEL,
        ),
    )
}
