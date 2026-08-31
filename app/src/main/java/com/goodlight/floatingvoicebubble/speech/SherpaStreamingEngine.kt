package com.goodlight.floatingvoicebubble.speech

import com.goodlight.floatingvoicebubble.model.StreamingAsrModel
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

data class StreamingReplayDecode(
    val text: String,
    val elapsedMs: Long,
    val audioDurationMs: Long,
    val realTimeFactor: Double,
    val engineId: String,
)

class SherpaStreamingEngine(
    private val model: StreamingAsrModel,
    private val sampleRate: Int = 16_000,
    private val onState: (String) -> Unit,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onFailure: (String) -> Unit,
) : AutoCloseable {
    private sealed interface Packet {
        data class Audio(val samples: FloatArray) : Packet
        data object Finish : Packet
        data object Stop : Packet
    }

    private val queue = ArrayBlockingQueue<Packet>(MAX_QUEUED_PACKETS)
    private val started = AtomicBoolean(false)
    private val terminal = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private var decoderThread: Thread? = null

    fun start() {
        check(started.compareAndSet(false, true)) { "Sherpa streaming engine already started" }
        decoderThread = Thread(::decoderLoop, "VoiceBubble-Sherpa").apply {
            priority = Thread.NORM_PRIORITY
            start()
        }
    }

    fun acceptPcm16(samples: ShortArray, count: Int) {
        if (!started.get() || terminal.get() || closed.get() || count <= 0) return
        val normalized = FloatArray(count)
        for (index in 0 until count) normalized[index] = samples[index] / 32768.0f
        if (!queue.offer(Packet.Audio(normalized))) {
            fail("端末内ASRがリアルタイム処理に追いつきませんでした。モデルのchunk幅または端末性能を見直してください。")
        }
    }

    fun finish() {
        if (!started.get() || terminal.get() || closed.get()) return
        if (!queue.offer(Packet.Finish)) {
            fail("端末内ASRの入力キューを確定できませんでした。")
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        queue.clear()
        queue.offer(Packet.Stop)
        decoderThread?.interrupt()
    }

    private fun decoderLoop() {
        var stream: OnlineStream? = null
        try {
            onState("端末内ASRモデルを準備しています")
            val recognizer = SherpaRecognizerCache.acquire(model)
            if (closed.get()) return
            stream = recognizer.createStream().also { it.setOption("language", "ja") }
            onState("端末内ASRで聴いています")
            var lastPartial = ""

            loop@ while (!closed.get()) {
                when (val packet = queue.take()) {
                    is Packet.Audio -> {
                        stream.acceptWaveform(packet.samples, sampleRate)
                        drain(recognizer, stream)
                        val text = recognizer.getResult(stream).text.trim()
                        if (text.isNotEmpty() && text != lastPartial) {
                            lastPartial = text
                            onPartial(text)
                        }
                    }
                    Packet.Finish -> {
                        stream.inputFinished()
                        drain(recognizer, stream)
                        val finalText = recognizer.getResult(stream).text.trim()
                        if (finalText.isBlank()) {
                            fail("音声を文字として認識できませんでした。")
                        } else if (terminal.compareAndSet(false, true)) {
                            onFinal(finalText)
                        }
                        break@loop
                    }
                    Packet.Stop -> break@loop
                }
            }
        } catch (_: InterruptedException) {
            if (!closed.get()) fail("端末内ASR処理が中断されました。")
        } catch (failure: Throwable) {
            if (!closed.get()) fail(failure.message ?: "端末内ASRでエラーが発生しました。")
        } finally {
            runCatching { stream?.release() }
        }
    }

    private fun drain(recognizer: OnlineRecognizer, stream: OnlineStream) = drainRecognizer(recognizer, stream)

    private fun fail(message: String) {
        if (!terminal.compareAndSet(false, true)) return
        queue.clear()
        queue.offer(Packet.Stop)
        onFailure(message)
    }

    private object SherpaRecognizerCache {
        private var cachedKey: String? = null
        private var cachedRecognizer: OnlineRecognizer? = null

        @Synchronized
        fun acquire(model: StreamingAsrModel): OnlineRecognizer {
            if (cachedKey == model.cacheKey) cachedRecognizer?.let { return it }
            cachedRecognizer?.let { runCatching { it.release() } }
            cachedRecognizer = null
            cachedKey = null

            val threads = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 4)
            val config = OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80, dither = 0.0f),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = model.encoder.absolutePath,
                        decoder = model.decoder.absolutePath,
                        joiner = model.joiner.absolutePath,
                    ),
                    tokens = model.tokens.absolutePath,
                    numThreads = threads,
                    debug = false,
                    provider = "cpu",
                ),
                enableEndpoint = false,
                decodingMethod = "greedy_search",
            )
            return OnlineRecognizer(config = config).also {
                cachedKey = model.cacheKey
                cachedRecognizer = it
            }
        }
    }

    companion object {
        private const val MAX_QUEUED_PACKETS = 256
        private const val MAX_DECODE_STEPS_PER_PACKET = 10_000
        private const val REPLAY_FRAME_SAMPLES = 320 // 20 ms at 16 kHz; only replay pacing, not fake live output.

        fun preload(model: StreamingAsrModel) {
            SherpaRecognizerCache.acquire(model)
        }

        /**
         * Replays a saved WAV through the exact online recognizer for fair same-audio model comparison.
         * This is an offline benchmark operation; it is never presented as a live-streaming UX measurement.
         */
        fun decodeReplay(model: StreamingAsrModel, wavFile: File): StreamingReplayDecode {
            val audio = Pcm16WavReader.read(wavFile)
            require(audio.sampleRate == 16_000) { "Nemotron replay requires 16 kHz WAV" }
            val recognizer = SherpaRecognizerCache.acquire(model)
            val stream = recognizer.createStream().also { it.setOption("language", "ja") }
            val startedNs = System.nanoTime()
            try {
                var offset = 0
                while (offset < audio.samples.size) {
                    val end = minOf(offset + REPLAY_FRAME_SAMPLES, audio.samples.size)
                    stream.acceptWaveform(audio.samples.copyOfRange(offset, end), audio.sampleRate)
                    drainRecognizer(recognizer, stream)
                    offset = end
                }
                stream.inputFinished()
                drainRecognizer(recognizer, stream)
                val text = recognizer.getResult(stream).text.trim()
                require(text.isNotBlank()) { "Nemotron replay produced no text" }
                val elapsedMs = ((System.nanoTime() - startedNs) / 1_000_000L).coerceAtLeast(1L)
                val durationMs = audio.durationMs.coerceAtLeast(1L)
                return StreamingReplayDecode(
                    text = text,
                    elapsedMs = elapsedMs,
                    audioDurationMs = durationMs,
                    realTimeFactor = elapsedMs.toDouble() / durationMs.toDouble(),
                    engineId = "sherpa-online:${model.id}",
                )
            } finally {
                runCatching { stream.release() }
            }
        }

        private fun drainRecognizer(recognizer: OnlineRecognizer, stream: OnlineStream) {
            var steps = 0
            while (recognizer.isReady(stream)) {
                recognizer.decode(stream)
                steps += 1
                check(steps <= MAX_DECODE_STEPS_PER_PACKET) { "Sherpa decoder did not quiesce" }
            }
        }
    }
}
