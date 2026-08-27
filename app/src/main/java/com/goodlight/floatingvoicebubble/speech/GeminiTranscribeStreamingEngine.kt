package com.goodlight.floatingvoicebubble.speech

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Real-time Gemini 3.5 Transcribe backend.
 *
 * Audio capture remains owned by [AudioCaptureSession]. This class only packetizes its 16 kHz
 * PCM16 stream, maintains a bounded queue while the WebSocket is being established/resumed, and
 * converts Gemini interim/final transcription events into the same callbacks as the local streamer.
 * It never starts a fresh session after already-sent audio unless there is a resumption handle;
 * silently losing the tail of a dictation is worse than an explicit recognition failure.
 */
class GeminiTranscribeStreamingEngine(
    private val apiKey: String,
    biasTerms: List<String>,
    private val onState: (String) -> Unit,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onFailure: (String) -> Unit,
) : AutoCloseable {
    private val lock = Any()
    private val customVocabulary = biasTerms
        .asSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .take(GeminiTranscribeProtocol.MAX_CUSTOM_VOCABULARY)
        .toList()
    private val chunker = Pcm16Chunker(FRAME_SAMPLES)
    private val accumulator = TranscriptAccumulator()
    private val queuedFrames = ArrayDeque<ByteArray>()
    private val closed = AtomicBoolean(false)
    private val terminal = AtomicBoolean(false)

    private var socket: WebSocket? = null
    private var socketGeneration = 0L
    private var setupReady = false
    private var finishRequested = false
    private var streamEndSent = false
    private var queuedBytes = 0
    private var latestInterim = ""
    private var resumptionHandle: String? = null
    private var reconnectAttempts = 0
    private var audioEverSent = false

    fun start() {
        require(apiKey.isNotBlank()) { "Gemini 3.5 TranscribeのAPIキーを設定してください。" }
        synchronized(lock) {
            check(!closed.get()) { "Gemini Transcribe session is closed" }
            check(socket == null) { "Gemini Transcribe session already started" }
            openSocketLocked(resume = false)
        }
    }

    fun acceptPcm16(samples: ShortArray, count: Int) {
        if (count <= 0 || closed.get() || terminal.get()) return
        var overflow = false
        synchronized(lock) {
            if (finishRequested || closed.get() || terminal.get()) return
            chunker.accept(samples, count).forEach { frame ->
                if (!sendOrQueueLocked(frame)) overflow = true
            }
        }
        if (overflow) failTerminal("Gemini音声認識の再接続待ちが長すぎるため、音声を欠落させず処理できませんでした。")
    }

    fun finish() {
        if (closed.get() || terminal.get()) return
        var overflow = false
        synchronized(lock) {
            if (finishRequested) return
            finishRequested = true
            chunker.flush()?.let { frame ->
                if (!sendOrQueueLocked(frame)) overflow = true
            }
            sendStreamEndIfReadyLocked()
        }
        if (overflow) {
            failTerminal("Gemini音声認識の再接続待ちが長すぎるため、音声を欠落させず確定できませんでした。")
        } else {
            onState("Geminiで認識を確定しています")
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(lock) {
            setupReady = false
            queuedFrames.clear()
            queuedBytes = 0
            socket?.cancel()
            socket = null
        }
    }

    private fun openSocketLocked(resume: Boolean) {
        if (closed.get() || terminal.get()) return
        setupReady = false
        streamEndSent = false
        val generation = ++socketGeneration
        val handle = if (resume) resumptionHandle else null
        val url = GeminiTranscribeProtocol.WEBSOCKET_BASE_URL
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("key", apiKey)
            .build()
        val request = Request.Builder().url(url).build()
        socket = CLIENT.newWebSocket(request, listener(generation, handle))
        onState(if (resume) "Gemini音声認識へ再接続しています" else "Gemini音声認識へ接続しています")
    }

    private fun listener(generation: Long, resumeHandleForSetup: String?) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val message = GeminiTranscribeProtocol.setupMessage(customVocabulary, resumeHandleForSetup)
            if (!webSocket.send(message)) {
                handleSocketFailure(generation, "Gemini音声認識の初期設定を送信できませんでした。")
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val event = runCatching { GeminiTranscribeProtocol.parseServerMessage(text) }
                .getOrElse {
                    handleSocketFailure(generation, "Gemini音声認識の応答形式を読み取れませんでした。")
                    return
                }

            var partialToPublish: String? = null
            var finalToPublish: String? = null
            var reconnect = false
            var reconnectImpossible = false
            synchronized(lock) {
                if (generation != socketGeneration || closed.get() || terminal.get()) return

                event.resumptionHandle?.let { handle ->
                    if (event.resumable != false) resumptionHandle = handle
                }
                if (event.setupComplete) {
                    setupReady = true
                    reconnectAttempts = 0
                    while (queuedFrames.isNotEmpty()) {
                        val frame = queuedFrames.removeFirst()
                        queuedBytes -= frame.size
                        if (!sendFrameLocked(frame)) {
                            queuedFrames.addFirst(frame)
                            queuedBytes += frame.size
                            setupReady = false
                            break
                        }
                    }
                    sendStreamEndIfReadyLocked()
                    if (setupReady) onState(if (finishRequested) "Geminiで認識を確定しています" else "聴いています")
                }

                event.interimTranscript?.let { interim ->
                    latestInterim = interim
                    partialToPublish = accumulator.display(interim)
                }
                event.finalTranscript?.let { final ->
                    accumulator.commit(final)
                    latestInterim = ""
                    partialToPublish = accumulator.display()
                    if (finishRequested) finalToPublish = accumulator.display()
                }
                if (finishRequested && event.turnComplete && finalToPublish == null && accumulator.hasContent(latestInterim)) {
                    // turnComplete can accompany a final transcription event, but an interim-only
                    // hypothesis is not promoted here. The authoritative inputTranscription event
                    // must arrive before completion.
                }
                if (event.goAway && !finishRequested) {
                    setupReady = false
                    if (canReconnectWithoutLossLocked()) reconnect = true else reconnectImpossible = true
                }
            }
            partialToPublish?.let(onPartial)
            finalToPublish?.takeIf(String::isNotBlank)?.let(::deliverFinal)
            when {
                reconnect -> reconnectNow(generation, graceful = true)
                reconnectImpossible -> failTerminal(
                    "Gemini音声認識から接続更新を求められましたが、再開用情報が得られていないため音声欠落を避けて停止しました。",
                )
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (closed.get() || terminal.get()) return
            var shouldReconnect = false
            var lossyReconnect = false
            synchronized(lock) {
                if (generation != socketGeneration) return
                setupReady = false
                if (!finishRequested && reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
                    if (canReconnectWithoutLossLocked()) shouldReconnect = true else lossyReconnect = true
                }
            }
            when {
                shouldReconnect -> reconnectNow(generation, graceful = false)
                lossyReconnect -> failTerminal(
                    "Gemini音声認識との接続が切れ、再開用情報が無いため音声欠落を避けて停止しました (code=$code)。",
                )
                !finishRequested -> failTerminal("Gemini音声認識との接続が終了しました (code=$code)。")
                else -> failTerminal("Gemini音声認識との接続が確定前に終了しました (code=$code)。")
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            handleSocketFailure(generation, "Gemini音声認識との通信に失敗しました: ${safeNetworkReason(t)}")
        }
    }

    private fun sendOrQueueLocked(frame: ByteArray): Boolean {
        if (setupReady && sendFrameLocked(frame)) return true
        setupReady = false
        if (queuedBytes + frame.size > MAX_QUEUED_AUDIO_BYTES) return false
        queuedFrames.addLast(frame)
        queuedBytes += frame.size
        return true
    }

    private fun sendFrameLocked(frame: ByteArray): Boolean {
        val sent = socket?.send(GeminiTranscribeProtocol.audioMessage(frame)) == true
        if (sent) audioEverSent = true
        return sent
    }

    private fun sendStreamEndIfReadyLocked() {
        if (!finishRequested || streamEndSent || !setupReady || queuedFrames.isNotEmpty()) return
        val sent = socket?.send(GeminiTranscribeProtocol.audioStreamEndMessage()) == true
        if (sent) streamEndSent = true else setupReady = false
    }

    private fun handleSocketFailure(generation: Long, message: String) {
        if (closed.get() || terminal.get()) return
        var reconnect = false
        var lossyReconnect = false
        synchronized(lock) {
            if (generation != socketGeneration) return
            setupReady = false
            if (!finishRequested && reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
                if (canReconnectWithoutLossLocked()) reconnect = true else lossyReconnect = true
            }
        }
        when {
            reconnect -> reconnectNow(generation, graceful = false)
            lossyReconnect -> failTerminal("$message 再開用情報が無いため、音声欠落を避けて再接続しません。")
            else -> failTerminal(message)
        }
    }

    private fun canReconnectWithoutLossLocked(): Boolean =
        !audioEverSent || !resumptionHandle.isNullOrBlank()

    private fun reconnectNow(generation: Long, graceful: Boolean) {
        if (closed.get() || terminal.get()) return
        synchronized(lock) {
            if (generation != socketGeneration || closed.get() || terminal.get()) return
            if (!canReconnectWithoutLossLocked()) return
            if (!graceful) reconnectAttempts += 1
            setupReady = false
            val old = socket
            socket = null
            if (graceful) old?.close(1000, "session rotation") else old?.cancel()
            openSocketLocked(resume = !resumptionHandle.isNullOrBlank())
        }
    }

    private fun deliverFinal(text: String) {
        if (!terminal.compareAndSet(false, true)) return
        onFinal(text)
    }

    private fun failTerminal(message: String) {
        if (!terminal.compareAndSet(false, true)) return
        synchronized(lock) {
            setupReady = false
            socket?.cancel()
            socket = null
        }
        onFailure(message)
    }

    private fun safeNetworkReason(failure: Throwable): String = when (failure) {
        is java.net.UnknownHostException -> "接続先を名前解決できません"
        is java.net.SocketTimeoutException -> "接続が時間切れになりました"
        is javax.net.ssl.SSLException -> "TLS接続に失敗しました"
        else -> failure.javaClass.simpleName
    }

    private class Pcm16Chunker(private val frameSamples: Int) {
        private val pending = ShortArray(frameSamples)
        private var count = 0

        fun accept(source: ShortArray, sourceCount: Int): List<ByteArray> {
            require(sourceCount in 0..source.size)
            val output = ArrayList<ByteArray>()
            var offset = 0
            while (offset < sourceCount) {
                val copy = minOf(frameSamples - count, sourceCount - offset)
                source.copyInto(pending, count, offset, offset + copy)
                count += copy
                offset += copy
                if (count == frameSamples) {
                    output += toLittleEndianBytes(pending, count)
                    count = 0
                }
            }
            return output
        }

        fun flush(): ByteArray? {
            if (count == 0) return null
            return toLittleEndianBytes(pending, count).also { count = 0 }
        }

        private fun toLittleEndianBytes(samples: ShortArray, length: Int): ByteArray {
            val bytes = ByteArray(length * 2)
            var j = 0
            for (i in 0 until length) {
                val value = samples[i].toInt()
                bytes[j++] = (value and 0xff).toByte()
                bytes[j++] = ((value ushr 8) and 0xff).toByte()
            }
            return bytes
        }
    }

    companion object {
        private const val FRAME_SAMPLES = 1_600 // 100 ms at 16 kHz.
        private const val MAX_RECONNECT_ATTEMPTS = 3
        private const val MAX_QUEUED_AUDIO_BYTES = 20 * 16_000 * 2 // 20 seconds, mono PCM16.

        private val CLIENT = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }
}
