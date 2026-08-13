package com.goodlight.floatingvoicebubble.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.InputMethod
import android.content.Context
import android.view.inputmethod.EditorInfo
import com.goodlight.floatingvoicebubble.AppSettings
import com.goodlight.floatingvoicebubble.speech.InstalledStreamingAsrModel
import com.goodlight.floatingvoicebubble.speech.SpeechRecognitionSession
import java.io.File

data class VoiceEditorTarget(
    val generation: Long,
    val packageName: String,
    val fieldId: Int,
    val fieldName: String?,
)

class TrackingVoiceInputMethod(
    service: AccessibilityService,
    private val changed: (Boolean) -> Unit,
) : InputMethod(service) {
    var generation = 0L
        private set

    fun target(): VoiceEditorTarget? {
        if (!currentInputStarted || currentInputConnection == null) return null
        val editor = currentInputEditorInfo ?: return null
        return VoiceEditorTarget(
            generation,
            editor.packageName?.toString().orEmpty(),
            editor.fieldId,
            editor.fieldName,
        )
    }

    fun matches(target: VoiceEditorTarget?): Boolean {
        target ?: return false
        val current = target() ?: return false
        return target == current
    }

    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        if (!restarting) generation++
        super.onStartInput(attribute, restarting)
        changed(true)
    }

    override fun onFinishInput() {
        super.onFinishInput()
        changed(false)
    }
}

object VoiceSessionFactory {
    fun create(
        context: Context,
        settings: AppSettings,
        streamingModel: InstalledStreamingAsrModel?,
        biasTerms: List<String>,
        traceAudioDir: File,
        onPartial: (String) -> Unit,
        onState: (String) -> Unit,
        onComplete: (com.goodlight.floatingvoicebubble.speech.RecognitionOutcome) -> Unit,
        onFailure: (String) -> Unit,
    ) = SpeechRecognitionSession(
        context = context,
        mode = settings.recognitionMode,
        offlineRequired = settings.offlineMode,
        autoEndpoint = settings.autoStop,
        biasTerms = biasTerms,
        traceAudioDir = traceAudioDir,
        streamingModel = streamingModel,
        onPartial = onPartial,
        onState = onState,
        onComplete = onComplete,
        onFailure = onFailure,
    )
}