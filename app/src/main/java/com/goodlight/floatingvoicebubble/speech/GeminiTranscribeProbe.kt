package com.goodlight.floatingvoicebubble.speech

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Connectivity/authentication probe only. It deliberately sends no microphone audio, so callers
 * must not present a successful probe as proof that production transcription works end-to-end.
 */
internal object GeminiTranscribeProbe {
    data class Result(val elapsedMs: Long)

    fun run(
        apiKey: String,
        endpoint: String = GeminiTranscribeProtocol.WEBSOCKET_BASE_URL,
        model: String = GeminiTranscribeProtocol.MODEL,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): Result {
        require(apiKey.isNotBlank()) { "クラウド音声認識のAPIキーを入力してください。" }
        require(model.isNotBlank()) { "クラウド音声認識のモデルIDを入力してください。" }
        require(timeoutMs in 1_000L..60_000L)

        val started = System.nanoTime()
        val latch = CountDownLatch(1)
        val failure = AtomicReference<String?>(null)
        val socketRef = AtomicReference<WebSocket?>(null)
        val url = GeminiTranscribeProtocol.httpTransportEndpoint(endpoint)
            .toHttpUrl()
            .newBuilder()
            .setQueryParameter("key", apiKey)
            .build()
        val request = Request.Builder().url(url).build()

        val socket = CLIENT.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (!webSocket.send(GeminiTranscribeProtocol.setupMessage(emptyList(), model = model))) {
                        failure.compareAndSet(null, "クラウド音声認識へ初期設定を送れませんでした。")
                        latch.countDown()
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val event = runCatching { GeminiTranscribeProtocol.parseServerMessage(text) }
                        .getOrElse {
                            failure.compareAndSet(null, "クラウド音声認識の応答形式を読み取れませんでした。")
                            latch.countDown()
                            return
                        }
                    if (event.setupComplete) latch.countDown()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val status = response?.code
                    failure.compareAndSet(
                        null,
                        when (status) {
                            400 -> "クラウド音声認識APIが設定を受け付けませんでした (HTTP 400)。"
                            401, 403 -> "クラウド音声認識のAPIキーが拒否されました (HTTP $status)。"
                            429 -> "クラウド音声認識APIの利用上限に達しています (HTTP 429)。"
                            null -> "クラウド音声認識APIへ接続できませんでした: ${networkReason(t)}"
                            else -> "クラウド音声認識APIへ接続できませんでした (HTTP $status)。"
                        },
                    )
                    latch.countDown()
                }
            },
        )
        socketRef.set(socket)

        try {
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                throw IOException("クラウド音声認識APIの接続テストが${timeoutMs / 1000}秒で時間切れになりました。")
            }
            failure.get()?.let { throw IOException(it) }
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            return Result(elapsedMs)
        } finally {
            socketRef.getAndSet(null)?.close(1000, "probe complete")
        }
    }

    private fun networkReason(failure: Throwable): String = when (failure) {
        is java.net.UnknownHostException -> "接続先を名前解決できません"
        is java.net.SocketTimeoutException -> "接続が時間切れになりました"
        is javax.net.ssl.SSLException -> "TLS接続に失敗しました"
        else -> failure.javaClass.simpleName
    }

    private const val DEFAULT_TIMEOUT_MS = 15_000L
    private val CLIENT = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
}
