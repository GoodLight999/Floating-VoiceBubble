package com.goodlight.floatingvoicebubble.speech

import com.goodlight.floatingvoicebubble.RecognitionMode

enum class RecognitionBackend {
    ANDROID_SYSTEM,
    ANDROID_ON_DEVICE,
    SHERPA_STREAMING,
}

object RecognitionBackendResolver {
    fun resolve(
        mode: RecognitionMode,
        offlineRequired: Boolean,
        androidOnDeviceAvailable: Boolean,
        sherpaModelAvailable: Boolean,
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
            RecognitionMode.AUTO -> {
                if (androidOnDeviceAvailable) RecognitionBackend.ANDROID_ON_DEVICE
                else RecognitionBackend.ANDROID_SYSTEM
            }
        }
    }
}
