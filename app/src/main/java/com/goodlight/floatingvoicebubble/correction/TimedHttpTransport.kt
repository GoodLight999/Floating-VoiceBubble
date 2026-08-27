package com.goodlight.floatingvoicebubble.correction

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
 */
internal object TimedHttpTransport {
    fun execute(
        connection: HttpURLConnection,
        requestBody: String,
        attempt: Int,
        deadlineMs: Long,
    ): TimedHttpResponse {
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
            HttpConnectionDeadline.run(connection, deadlineMs) {
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
                val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                bodyMs = elapsedMs(bodyStarted)

                TimedHttpResponse(status = status, text = text, timing = snapshot())
            }
        } catch (failure: Throwable) {
            throw TimedHttpTransportFailure(failure, snapshot())
        }
    }

    private fun elapsedMs(startedNs: Long): Long =
        ((System.nanoTime() - startedNs) / 1_000_000L).coerceAtLeast(0L)
}
