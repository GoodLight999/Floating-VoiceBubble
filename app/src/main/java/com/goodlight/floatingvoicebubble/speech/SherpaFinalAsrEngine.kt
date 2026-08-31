package com.goodlight.floatingvoicebubble.speech

import com.goodlight.floatingvoicebubble.model.FinalAsrModel
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import java.io.File

data class FinalAsrDecodeResult(
    val text: String,
    val engineId: String,
    val elapsedMs: Long,
    val audioDurationMs: Long,
) {
    val realTimeFactor: Double
        get() = if (audioDurationMs <= 0L) Double.NaN else elapsedMs.toDouble() / audioDurationMs
}

object SherpaFinalAsrEngine {
    fun preload(model: FinalAsrModel) {
        Cache.acquire(model)
    }

    fun decode(model: FinalAsrModel, wavFile: File): FinalAsrDecodeResult {
        awaitFinalizedWav(wavFile)
        val audio = Pcm16WavReader.read(wavFile)
        val recognizer = Cache.acquire(model)
        val stream = recognizer.createStream()
        val started = System.nanoTime()
        return try {
            stream.acceptWaveform(audio.samples, audio.sampleRate)
            recognizer.decode(stream)
            val result = recognizer.getResult(stream).text.trim()
            require(result.isNotBlank()) { "ReazonSpeech returned empty transcript" }
            val elapsedMs = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(1L)
            FinalAsrDecodeResult(
                text = result,
                engineId = "reazonspeech-v2-int8",
                elapsedMs = elapsedMs,
                audioDurationMs = audio.durationMs,
            )
        } finally {
            stream.release()
        }
    }

    private fun awaitFinalizedWav(file: File) {
        val deadline = System.nanoTime() + 2_000_000_000L
        var previousSize = -1L
        var stable = 0
        while (System.nanoTime() < deadline) {
            val size = if (file.isFile) file.length() else -1L
            if (size > 44L && size == previousSize) {
                stable += 1
                if (stable >= 2) return
            } else {
                stable = 0
            }
            previousSize = size
            Thread.sleep(25)
        }
        require(file.isFile && file.length() > 44L) { "Recorded WAV was not finalized" }
    }

    private object Cache {
        private var key: String? = null
        private var recognizer: OfflineRecognizer? = null

        @Synchronized
        fun acquire(model: FinalAsrModel): OfflineRecognizer {
            if (key == model.cacheKey) recognizer?.let { return it }
            recognizer?.let { runCatching { it.release() } }
            recognizer = null
            key = null
            val threads = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 4)
            val config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80, dither = 0.0f),
                modelConfig = OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(
                        encoder = model.encoder.absolutePath,
                        decoder = model.decoder.absolutePath,
                        joiner = model.joiner.absolutePath,
                    ),
                    tokens = model.tokens.absolutePath,
                    numThreads = threads,
                    debug = false,
                    provider = "cpu",
                    modelingUnit = "cjkchar",
                ),
                decodingMethod = "greedy_search",
            )
            return OfflineRecognizer(config = config).also {
                key = model.cacheKey
                recognizer = it
            }
        }
    }
}
