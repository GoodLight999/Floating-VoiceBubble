package com.goodlight.floatingvoicebubble.correction

import com.goodlight.floatingvoicebubble.AppSettings
import com.goodlight.floatingvoicebubble.CorrectionMode

enum class CorrectionBackend { NONE, BYOK, GEMMA }

object CorrectionBackendResolver {
    fun resolve(settings: AppSettings, gemmaAvailable: Boolean): CorrectionBackend {
        if (settings.offlineMode) {
            return if (gemmaAvailable) CorrectionBackend.GEMMA else CorrectionBackend.NONE
        }
        return when (settings.correctionMode) {
            CorrectionMode.NONE -> CorrectionBackend.NONE
            CorrectionMode.BYOK -> CorrectionBackend.BYOK
            CorrectionMode.GEMMA -> if (gemmaAvailable) CorrectionBackend.GEMMA else CorrectionBackend.NONE
            CorrectionMode.AUTO -> when {
                settings.byokModel.isNotBlank() -> CorrectionBackend.BYOK
                gemmaAvailable -> CorrectionBackend.GEMMA
                else -> CorrectionBackend.NONE
            }
        }
    }
}
