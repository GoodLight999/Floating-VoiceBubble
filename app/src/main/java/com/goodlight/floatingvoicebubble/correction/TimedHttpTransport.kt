package com.goodlight.floatingvoicebubble.correction

import java.io.IOException
import java.net.HttpURLConnection

internal data class TimedHttpResponse(
    val status: Int,
    val text: String,
    val timing: CorrectionAttemptTiming,
)

internal class TimedHttpTransportFailure(
    val transportCause: Throwable,
    val timing: CorrectionAttemptTiming,
) : RuntimeException(transportCause.message, transportCause)

/**
 * Executes one JSON HTTP attempt and records redacted phase timings only.
 * Request/response bodies are never retained in the timing object.
 *
 * The timeout is intentionally an idle timeout, not a wall-clock deadline. HttpURLConnection's
 * readTimeout applies to each blocking socket read, so a provider that keeps sending SSE/reasoning
 * chunks may run longer than the idle window without being killed. A provider that stops sending
 * any bytes is still terminated normally by the socket timeout.
 */
internal object TimedHttpTransport {
    fun execute(
        connection: HttpURLConnection,
        requestBody: String,
        attempt: Int,
        idleTimeoutMs: Long,
    ): TimedHttpResponse {
        require(idleTimeoutMs > 0) { "idleTimeoutMs must be positive" }
        val totalStarted = System.nanoTime()
        var connectMs: Long? = null
        var writeMs: Long? = null
        var headersMs: Long? = null
        var bodyMs: Long? = null

        fun snapshot(): CorrectionAttemptTiming = CorrectionAttemptTiming(
            attempt = attempt,
            connectMs = connectMs,
            requestWriteMs = writeMs,
            responseHeadersMs = headersMs,
            responseBodyMs = bodyMs,
            totalMs = elapsedMs(totalStarted),
        )

        return try {
            connection.readTimeout = idleTimeoutMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

            val connectStarted = System.nanoTime()
            connection.connect()
            connectMs = elapsedMs(connectStarted)

            val writeStarted = System.nanoTime()
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(requestBody) }
            writeMs = elapsedMs(writeStarted)

            val headersStarted = System.nanoTime()
            val status = connection.responseCode
            headersMs = elapsedMs(headersStarted)

            val bodyStarted = System.nanoTime()
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = if (stream == null) {
                ""
            } else {
                stream.bufferedReader(Charsets.UTF_8).use { reader ->
                    val buffer = CharArray(4096)
                    buildString {
                        while (true) {
                            val count = reader.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            if (length + count > MAX_RESPONSE_CHARS) {
                                throw IOException("HTTP response exceeded safe size limit")
                            }
                            append(buffer, 0, count)
                        }
                    }
                }
            }
            bodyMs = elapsedMs(bodyStarted)

            TimedHttpResponse(status = status, text = text, timing = snapshot())
        } catch (failure: Throwable) {
            throw TimedHttpTransportFailure(failure, snapshot())
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    private fun elapsedMs(startedNs: Long): Long =
        ((System.nanoTime() - startedNs) / 1_000_000L).coerceAtLeast(0L)

    private const val MAX_RESPONSE_CHARS = 4 * 1024 * 1024
}
