package com.goodlight.floatingvoicebubble.speech

import com.goodlight.floatingvoicebubble.RecognitionMode

enum class RecognitionBackend {
    ANDROID_SYSTEM,
    ANDROID_ON_DEVICE,
    SHERPA_STREAMING,
    GEMINI_TRANSCRIBE,
}

object RecognitionBackendResolver {
    fun resolve(
        mode: RecognitionMode,
        offlineRequired: Boolean,
        androidOnDeviceAvailable: Boolean,
        sherpaModelAvailable: Boolean,
        geminiTranscribeConfigured: Boolean = false,
    ): RecognitionBackend {
        if (offlineRequired) {
            require(sherpaModelAvailable) {
                "完全オフラインには真のストリーミングASRモデルが必要です。"
            }
            return RecognitionBackend.SHERPA_STREAMING
        }

        return when (mode) {
            RecognitionMode.SYSTEM -> RecognitionBackend.ANDROID_SYSTEM
            RecognitionMode.ON_DEVICE -> {
                require(androidOnDeviceAvailable) {
                    "この端末ではAndroidのオンデバイス音声認識を利用できません。"
                }
                RecognitionBackend.ANDROID_ON_DEVICE
            }
            RecognitionMode.SHERPA_STREAMING -> {
                require(sherpaModelAvailable) {
                    "自前ストリーミングASRモデルが設定されていません。"
                }
                RecognitionBackend.SHERPA_STREAMING
            }
            RecognitionMode.GEMINI_TRANSCRIBE -> {
                require(geminiTranscribeConfigured) {
                    "Gemini 3.5 TranscribeのAPIキーが設定されていません。"
                }
                RecognitionBackend.GEMINI_TRANSCRIBE
            }
            // AUTO never silently starts a metered third-party cloud backend. Selecting Gemini is
            // explicit BYOK consent; AUTO keeps the existing zero-configuration Android behavior.
            RecognitionMode.AUTO -> {
                if (androidOnDeviceAvailable) RecognitionBackend.ANDROID_ON_DEVICE
                else RecognitionBackend.ANDROID_SYSTEM
            }
        }
    }
}
