package com.goodlight.floatingvoicebubble.speech

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.goodlight.floatingvoicebubble.RecognitionMode
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

data class RecognitionOutcome(
    val sessionId: String,
    val rawTranscript: String,
    val alternatives: List<String>,
    val audioFile: File?,
    val startedAtMs: Long,
    val recognitionFinishedAtMs: Long,
    val recognizerKind: String,
)

class SpeechRecognitionSession(
    private val context: Context,
    private val mode: RecognitionMode,
    private val offlineRequired: Boolean,
    private val autoEndpoint: Boolean,
    private val biasTerms: List<String>,
    private val traceAudioDir: File,
    private val onPartial: (String) -> Unit,
    private val onState: (String) -> Unit,
    private val onComplete: (RecognitionOutcome) -> Unit,
    private val onFailure: (String) -> Unit,
) : AutoCloseable {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sessionId = UUID.randomUUID().toString()
    private val startedAtMs = System.currentTimeMillis()
    private val delivered = AtomicBoolean(false)
    private val recognizer: SpeechRecognizer
    private val recognizerKind: String
    private val capture: AudioCaptureSession
    private var latestAlternatives: List<String> = emptyList()
    private var latestPartial: String = ""
    private var inputClosed = false

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = onState("聴いています")
        override fun onBeginningOfSpeech() = onState("聴いています")
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() {
            if (!inputClosed) onState("認識を確定しています")
        }

        override fun onError(error: Int) {
            if (delivered.get()) return
            val fallback = latestAlternatives.ifEmpty {
                latestPartial.takeIf(String::isNotBlank)?.let(::listOf).orEmpty()
            }
            if (inputClosed && fallback.isNotEmpty()) {
                deliver(fallback)
            } else {
                capture.stop()
                fail(errorMessage(error))
            }
        }

        override fun onResults(results: Bundle) {
            val candidates = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.map(String::trim)
                ?.filter(String::isNotEmpty)
                .orEmpty()
            if (candidates.isNotEmpty()) latestAlternatives = candidates
            if (latestAlternatives.isNotEmpty()) {
                deliver(latestAlternatives)
            } else {
                fail("音声を文字として認識できませんでした。")
            }
        }

        override fun onPartialResults(partialResults: Bundle) {
            val candidates = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.map(String::trim)
                ?.filter(String::isNotEmpty)
                .orEmpty()
            if (candidates.isEmpty()) return
            latestPartial = candidates.first()
            latestAlternatives = candidates
            onPartial(latestPartial)
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    init {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "SpeechRecognitionSession must be created on main thread"
        }
        val onDeviceAvailable = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        val useOnDevice = when {
            offlineRequired -> true
            mode == RecognitionMode.ON_DEVICE -> true
            mode == RecognitionMode.SYSTEM -> false
            else -> onDeviceAvailable
        }
        if (useOnDevice && !onDeviceAvailable) {
            error("この端末ではAndroidのオンデバイス音声認識を利用できません。")
        }
        recognizer = if (useOnDevice) {
            recognizerKind = "android-on-device"
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            recognizerKind = "android-system"
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        capture = AudioCaptureSession(context, traceAudioDir, autoEndpoint) {
            mainHandler.post { finishInput() }
        }
        recognizer.setRecognitionListener(listener)
    }

    fun start() {
        val source = capture.detachRecognizerAudioSource()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.JAPAN.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 8)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, source)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, capture.channelCount)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, capture.sampleRate)
            if (biasTerms.isNotEmpty()) {
                putStringArrayListExtra(
                    RecognizerIntent.EXTRA_BIASING_STRINGS,
                    ArrayList(biasTerms.take(384)),
                )
            }
        }
        onState("準備しています")
        recognizer.startListening(intent)
        capture.start(sessionId)
    }

    fun finishInput() {
        if (inputClosed || delivered.get()) return
        inputClosed = true
        onState("認識を確定しています")
        capture.stop()
        mainHandler.postDelayed({
            if (!delivered.get()) {
                val fallback = latestAlternatives.ifEmpty {
                    latestPartial.takeIf(String::isNotBlank)?.let(::listOf).orEmpty()
                }
                if (fallback.isNotEmpty()) {
                    deliver(fallback)
                } else {
                    fail("音声認識の確定がタイムアウトしました。")
                }
            }
        }, FINAL_RESULT_TIMEOUT_MS)
    }

    override fun close() {
        mainHandler.removeCallbacksAndMessages(null)
        capture.close()
        recognizer.cancel()
        recognizer.destroy()
    }

    private fun deliver(candidates: List<String>) {
        if (!delivered.compareAndSet(false, true)) return
        mainHandler.removeCallbacksAndMessages(null)
        capture.stop()
        val normalized = candidates.map(String::trim).filter(String::isNotEmpty).distinct()
        onComplete(
            RecognitionOutcome(
                sessionId = sessionId,
                rawTranscript = normalized.firstOrNull().orEmpty(),
                alternatives = normalized,
                audioFile = capture.expectedWavFile(),
                startedAtMs = startedAtMs,
                recognitionFinishedAtMs = System.currentTimeMillis(),
                recognizerKind = recognizerKind,
            )
        )
    }

    private fun fail(message: String) {
        if (!delivered.compareAndSet(false, true)) return
        mainHandler.removeCallbacksAndMessages(null)
        onFailure(message)
    }

    private fun errorMessage(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "音声入力を開始できませんでした。"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "マイク権限がありません。"
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "音声認識サービスへ接続できませんでした。"
        SpeechRecognizer.ERROR_NO_MATCH -> "音声を文字として認識できませんでした。"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "音声認識サービスが使用中です。"
        SpeechRecognizer.ERROR_SERVER,
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "音声認識サービスでエラーが発生しました。"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "音声が検出されませんでした。"
        else -> "音声認識エラー ($code)"
    }

    companion object {
        private const val FINAL_RESULT_TIMEOUT_MS = 8_000L
    }
}
