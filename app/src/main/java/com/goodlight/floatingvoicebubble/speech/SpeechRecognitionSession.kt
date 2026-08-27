package com.goodlight.floatingvoicebubble.speech

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.goodlight.floatingvoicebubble.RecognitionMode
import com.goodlight.floatingvoicebubble.SettingsStore
import com.goodlight.floatingvoicebubble.model.StreamingAsrModel
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
    private val streamingModel: StreamingAsrModel?,
    private val geminiTranscribeApiKey: String? = null,
    private val onPartial: (String) -> Unit,
    private val onState: (String) -> Unit,
    private val onComplete: (RecognitionOutcome) -> Unit,
    private val onFailure: (String) -> Unit,
) : AutoCloseable {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sessionId = UUID.randomUUID().toString()
    private val startedAtMs = System.currentTimeMillis()
    private val delivered = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val completionPublished = AtomicBoolean(false)
    private val accumulator = TranscriptAccumulator()
    private val resolvedGeminiApiKey: String = if (mode == RecognitionMode.GEMINI_TRANSCRIBE) {
        geminiTranscribeApiKey?.trim()?.takeIf(String::isNotEmpty)
            ?: SettingsStore(context).geminiTranscribeApiKey().trim()
    } else {
        ""
    }
    private val backend: RecognitionBackend
    private val recognizer: SpeechRecognizer?
    private val sherpaEngine: SherpaStreamingEngine?
    private val geminiEngine: GeminiTranscribeStreamingEngine?
    private val recognizerKind: String
    private val capture: AudioCaptureSession
    private var latestAlternatives: List<String> = emptyList()
    private var latestPartial: String = ""
    private var inputClosed = false
    private var androidSource: ParcelFileDescriptor? = null
    private var recognizerListening = false
    private var consecutiveRestartErrors = 0

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            recognizerListening = true
            consecutiveRestartErrors = 0
            onState("聴いています")
        }

        override fun onBeginningOfSpeech() = onState("聴いています")
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            if (!inputClosed) onState("音声認識を継続しています")
        }

        override fun onSegmentResults(segmentResults: Bundle) {
            if (delivered.get() || closed.get()) return
            val candidates = candidatesFrom(segmentResults)
            if (candidates.isEmpty()) return
            accumulator.commit(candidates.first())
            latestPartial = ""
            latestAlternatives = emptyList()
            consecutiveRestartErrors = 0
            onPartial(accumulator.display())
            if (!inputClosed) onState("続けて話せます")
        }

        override fun onEndOfSegmentedSession() {
            recognizerListening = false
            if (delivered.get() || closed.get()) return
            if (inputClosed) {
                val finalCandidates = accumulator.finalCandidates(latestAlternatives, latestPartial)
                if (finalCandidates.isNotEmpty()) deliver(finalCandidates)
                else fail("音声を文字として認識できませんでした。")
            } else {
                if (latestPartial.isNotBlank()) accumulator.commit(latestPartial)
                latestPartial = ""
                latestAlternatives = emptyList()
                onPartial(accumulator.display())
                scheduleAndroidRestart(ANDROID_RESTART_BASE_DELAY_MS)
            }
        }

        override fun onError(error: Int) {
            recognizerListening = false
            if (delivered.get() || closed.get()) return

            val fallback = accumulator.finalCandidates(latestAlternatives, latestPartial)
            if (inputClosed) {
                if (fallback.isNotEmpty()) deliver(fallback) else fail(errorMessage(error))
                return
            }

            if (isRecoverableSegmentationError(error) && consecutiveRestartErrors < MAX_ANDROID_RESTART_ERRORS) {
                if (latestPartial.isNotBlank()) accumulator.commit(latestPartial)
                latestPartial = ""
                latestAlternatives = emptyList()
                onPartial(accumulator.display())
                consecutiveRestartErrors += 1
                onState("続けて話せます")
                scheduleAndroidRestart(ANDROID_RESTART_BASE_DELAY_MS * consecutiveRestartErrors)
            } else {
                fail(errorMessage(error))
            }
        }

        override fun onResults(results: Bundle) {
            recognizerListening = false
            if (delivered.get() || closed.get()) return
            val candidates = candidatesFrom(results)
            if (candidates.isNotEmpty()) latestAlternatives = candidates

            if (inputClosed) {
                val finalCandidates = accumulator.finalCandidates(latestAlternatives, latestPartial)
                if (finalCandidates.isNotEmpty()) deliver(finalCandidates)
                else fail("音声を文字として認識できませんでした。")
                return
            }

            if (candidates.isEmpty()) {
                scheduleAndroidRestart(ANDROID_RESTART_BASE_DELAY_MS)
                return
            }

            accumulator.commit(candidates.first())
            latestPartial = ""
            latestAlternatives = emptyList()
            consecutiveRestartErrors = 0
            onPartial(accumulator.display())
            onState("続けて話せます")
            scheduleAndroidRestart(ANDROID_RESTART_BASE_DELAY_MS)
        }

        override fun onPartialResults(partialResults: Bundle) {
            val candidates = candidatesFrom(partialResults)
            if (candidates.isEmpty()) return
            latestPartial = candidates.first()
            latestAlternatives = candidates
            onPartial(accumulator.display(latestPartial))
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    init {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "SpeechRecognitionSession must be created on main thread"
        }
        val onDeviceAvailable = runCatching {
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        }.getOrDefault(false)
        backend = RecognitionBackendResolver.resolve(
            mode = mode,
            offlineRequired = offlineRequired,
            androidOnDeviceAvailable = onDeviceAvailable,
            sherpaModelAvailable = streamingModel != null,
            geminiTranscribeConfigured = resolvedGeminiApiKey.isNotBlank(),
        )

        recognizer = when (backend) {
            RecognitionBackend.ANDROID_ON_DEVICE -> SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            RecognitionBackend.ANDROID_SYSTEM -> SpeechRecognizer.createSpeechRecognizer(context)
            RecognitionBackend.SHERPA_STREAMING,
            RecognitionBackend.GEMINI_TRANSCRIBE -> null
        }
        recognizerKind = when (backend) {
            RecognitionBackend.ANDROID_ON_DEVICE -> "android-on-device-segmented-audio-source"
            RecognitionBackend.ANDROID_SYSTEM -> "android-system-segmented-audio-source"
            RecognitionBackend.SHERPA_STREAMING -> "sherpa-nemotron35-${streamingModel!!.chunkMs}ms"
            RecognitionBackend.GEMINI_TRANSCRIBE -> "gemini-3.5-transcribe-live-verbatim"
        }

        sherpaEngine = if (backend == RecognitionBackend.SHERPA_STREAMING) {
            val model = requireNotNull(streamingModel)
            SherpaStreamingEngine(
                model = model,
                onState = { state -> mainHandler.post { if (!delivered.get() && !closed.get()) onState(state) } },
                onPartial = { partial ->
                    mainHandler.post {
                        if (delivered.get() || closed.get()) return@post
                        latestPartial = partial
                        latestAlternatives = listOf(partial)
                        onPartial(partial)
                    }
                },
                onFinal = { finalText ->
                    mainHandler.post {
                        if (closed.get()) return@post
                        latestPartial = finalText
                        latestAlternatives = listOf(finalText)
                        deliver(listOf(finalText))
                    }
                },
                onFailure = { message -> mainHandler.post { fail(message) } },
            )
        } else {
            null
        }

        geminiEngine = if (backend == RecognitionBackend.GEMINI_TRANSCRIBE) {
            GeminiTranscribeStreamingEngine(
                apiKey = resolvedGeminiApiKey,
                biasTerms = biasTerms,
                onState = { state -> mainHandler.post { if (!delivered.get() && !closed.get()) onState(state) } },
                onPartial = { partial ->
                    mainHandler.post {
                        if (delivered.get() || closed.get()) return@post
                        latestPartial = partial
                        latestAlternatives = listOf(partial)
                        onPartial(partial)
                    }
                },
                onFinal = { finalText ->
                    mainHandler.post {
                        if (closed.get()) return@post
                        latestPartial = finalText
                        latestAlternatives = listOf(finalText)
                        deliver(listOf(finalText))
                    }
                },
                onFailure = { message -> mainHandler.post { fail(message) } },
            )
        } else {
            null
        }

        val pcmConsumer: ((ShortArray, Int) -> Unit)? = when (backend) {
            RecognitionBackend.SHERPA_STREAMING -> sherpaEngine?.let { engine -> { samples, count -> engine.acceptPcm16(samples, count) } }
            RecognitionBackend.GEMINI_TRANSCRIBE -> geminiEngine?.let { engine -> { samples, count -> engine.acceptPcm16(samples, count) } }
            else -> null
        }
        val needsAndroidPipe = backend == RecognitionBackend.ANDROID_SYSTEM || backend == RecognitionBackend.ANDROID_ON_DEVICE
        capture = AudioCaptureSession(
            context = context,
            outputDir = traceAudioDir,
            autoEndpoint = autoEndpoint,
            mirrorToRecognizerPipe = needsAndroidPipe,
            onPcm16 = pcmConsumer,
            onCaptureFailure = { message -> mainHandler.post { fail(message) } },
        ) {
            mainHandler.post { finishInput() }
        }
        recognizer?.setRecognitionListener(listener)
    }

    fun start() {
        check(!closed.get()) { "SpeechRecognitionSession is closed" }
        onState("準備しています")
        when (backend) {
            RecognitionBackend.SHERPA_STREAMING -> {
                requireNotNull(sherpaEngine).start()
                capture.start(sessionId)
                return
            }
            RecognitionBackend.GEMINI_TRANSCRIBE -> {
                requireNotNull(geminiEngine).start()
                capture.start(sessionId)
                return
            }
            else -> Unit
        }

        val source = capture.detachRecognizerAudioSource().also { androidSource = it }
        startAndroidListening(source)
        capture.start(sessionId)
    }

    fun finishInput() {
        if (inputClosed || delivered.get() || closed.get()) return
        inputClosed = true
        mainHandler.removeCallbacks(restartRunnable)
        onState("認識を確定しています")
        capture.stop()
        when (backend) {
            RecognitionBackend.SHERPA_STREAMING -> sherpaEngine?.finish()
            RecognitionBackend.GEMINI_TRANSCRIBE -> geminiEngine?.finish()
            else -> {
                mainHandler.postDelayed({
                    if (!delivered.get() && !closed.get() && inputClosed) {
                        runCatching { recognizer?.stopListening() }
                    }
                }, ANDROID_STOP_LISTENING_FALLBACK_DELAY_MS)
            }
        }

        val timeout = when (backend) {
            RecognitionBackend.SHERPA_STREAMING,
            RecognitionBackend.GEMINI_TRANSCRIBE -> STREAMING_FINAL_RESULT_TIMEOUT_MS
            else -> ANDROID_FINAL_RESULT_TIMEOUT_MS
        }
        mainHandler.postDelayed({
            if (!delivered.get() && !closed.get()) {
                when (backend) {
                    RecognitionBackend.GEMINI_TRANSCRIBE -> {
                        // Gemini explicitly distinguishes speculative interim text from authoritative
                        // inputTranscription. Never silently promote the interim hypothesis to final.
                        fail("Gemini音声認識の確定結果が${timeout / 1000}秒以内に届きませんでした。途中表示は確定結果として入力していません。")
                    }
                    RecognitionBackend.SHERPA_STREAMING -> {
                        val fallback = latestAlternatives.ifEmpty {
                            latestPartial.takeIf(String::isNotBlank)?.let(::listOf).orEmpty()
                        }
                        if (fallback.isNotEmpty()) deliver(fallback)
                        else fail("音声認識の確定がタイムアウトしました。")
                    }
                    else -> {
                        val fallback = accumulator.finalCandidates(latestAlternatives, latestPartial)
                        if (fallback.isNotEmpty()) deliver(fallback)
                        else fail("音声認識の確定がタイムアウトしました。")
                    }
                }
            }
        }, timeout)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        mainHandler.removeCallbacksAndMessages(null)
        if (!completionPublished.get()) capture.discardAudio()
        capture.close()
        sherpaEngine?.close()
        geminiEngine?.close()
        androidSource?.let { source -> runCatching { source.close() } }
        androidSource = null
        recognizer?.let { speechRecognizer ->
            runCatching { speechRecognizer.cancel() }
            runCatching { speechRecognizer.destroy() }
        }
        recognizerListening = false
    }

    private val restartRunnable = Runnable {
        if (inputClosed || delivered.get() || closed.get()) return@Runnable
        val source = androidSource ?: return@Runnable
        startAndroidListening(source)
    }

    private fun scheduleAndroidRestart(delayMs: Long) {
        if (inputClosed || delivered.get() || closed.get()) return
        mainHandler.removeCallbacks(restartRunnable)
        mainHandler.postDelayed(restartRunnable, delayMs)
    }

    private fun startAndroidListening(source: ParcelFileDescriptor) {
        if (inputClosed || delivered.get() || closed.get()) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.JAPAN.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 8)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, source)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, capture.channelCount)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, capture.sampleRate)
            putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
            if (biasTerms.isNotEmpty()) {
                putStringArrayListExtra(
                    RecognizerIntent.EXTRA_BIASING_STRINGS,
                    ArrayList(biasTerms.take(384)),
                )
            }
        }
        runCatching {
            recognizerListening = true
            requireNotNull(recognizer).startListening(intent)
        }.onFailure { failure ->
            recognizerListening = false
            fail(failure.message ?: "音声認識を再開できませんでした。")
        }
    }

    private fun candidatesFrom(bundle: Bundle): List<String> =
        bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            .orEmpty()

    private fun deliver(candidates: List<String>) {
        if (!delivered.compareAndSet(false, true)) return
        mainHandler.removeCallbacksAndMessages(null)
        recognizerListening = false
        capture.stop()
        val normalized = candidates.map(String::trim).filter(String::isNotEmpty).distinct()
        if (normalized.isEmpty()) {
            capture.discardAudio()
            onFailure("音声を文字として認識できませんでした。")
            return
        }
        val recognitionFinishedAtMs = System.currentTimeMillis()
        Thread(
            {
                val finalizedWav = capture.awaitFinalizedWav()
                if (closed.get()) {
                    capture.discardAudio()
                    return@Thread
                }
                val outcome = RecognitionOutcome(
                    sessionId = sessionId,
                    rawTranscript = normalized.first(),
                    alternatives = normalized,
                    audioFile = finalizedWav,
                    startedAtMs = startedAtMs,
                    recognitionFinishedAtMs = recognitionFinishedAtMs,
                    recognizerKind = recognizerKind,
                )
                mainHandler.post {
                    if (!closed.get()) {
                        completionPublished.set(true)
                        onComplete(outcome)
                    } else {
                        capture.discardAudio()
                    }
                }
            },
            "VoiceBubble-WavFinalize",
        ).start()
    }

    private fun fail(message: String) {
        if (!delivered.compareAndSet(false, true)) return
        mainHandler.removeCallbacksAndMessages(null)
        recognizerListening = false
        capture.stop()
        capture.discardAudio()
        onFailure(message)
    }

    private fun isRecoverableSegmentationError(code: Int): Boolean = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> true
        else -> false
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
        private const val ANDROID_FINAL_RESULT_TIMEOUT_MS = 8_000L
        private const val STREAMING_FINAL_RESULT_TIMEOUT_MS = 20_000L
        private const val ANDROID_RESTART_BASE_DELAY_MS = 120L
        private const val ANDROID_STOP_LISTENING_FALLBACK_DELAY_MS = 1_000L
        private const val MAX_ANDROID_RESTART_ERRORS = 4
    }
}
