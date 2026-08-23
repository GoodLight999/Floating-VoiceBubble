package com.goodlight.floatingvoicebubble.model

import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile

internal data class ResumableDownloadResponse(
    val status: Int,
    val contentRange: String?,
    val body: InputStream,
    private val closeAction: () -> Unit = {},
) : AutoCloseable {
    override fun close() {
        runCatching { body.close() }
        closeAction()
    }
}

/**
 * Provider-agnostic resumable downloader. Network opening/redirect/status policy stays outside;
 * this class owns Range continuation semantics, partial-file preservation and bounded retry.
 */
internal class ResumableFileDownloader(
    private val maxRetries: Int = 8,
    private val retryBaseDelayMs: Long = 750L,
    private val maxRetryDelayMs: Long = 12_000L,
    private val sleeper: (Long) -> Unit = { delayMs -> Thread.sleep(delayMs) },
) {
    fun download(
        destination: File,
        expectedBytes: Long,
        open: (rangeStart: Long?) -> ResumableDownloadResponse,
        onProgress: ((completedBytes: Long) -> Unit)? = null,
        onRetry: ((retryNumber: Int, delayMs: Long, completedBytes: Long, failure: Throwable) -> Unit)? = null,
    ) {
        require(expectedBytes > 0L) { "expected download size must be positive" }
        require(maxRetries >= 0) { "maxRetries must be non-negative" }
        destination.parentFile?.mkdirs()
        if (destination.length() > expectedBytes) destination.delete()
        onProgress?.invoke(destination.length())

        var retries = 0
        while (destination.length() < expectedBytes) {
            val requestedOffset = destination.length()
            try {
                open(requestedOffset.takeIf { it > 0L }).use { response ->
                    require(response.status == HTTP_OK || response.status == HTTP_PARTIAL) {
                        "unexpected successful download status ${response.status}"
                    }
                    if (response.status == HTTP_PARTIAL) {
                        validateContentRange(response.contentRange, requestedOffset, expectedBytes)
                    }
                    val append = requestedOffset > 0L && response.status == HTTP_PARTIAL

                    RandomAccessFile(destination, "rw").use { output ->
                        if (append) {
                            output.seek(requestedOffset)
                        } else {
                            // A server may ignore Range and send 200. Reuse that response as the
                            // restart rather than wasting another request.
                            output.setLength(0L)
                            output.seek(0L)
                        }
                        try {
                            val buffer = ByteArray(BUFFER_BYTES)
                            while (true) {
                                val read = response.body.read(buffer)
                                if (read <= 0) break
                                output.write(buffer, 0, read)
                                check(output.filePointer <= expectedBytes) {
                                    "download exceeded expected size $expectedBytes"
                                }
                                onProgress?.invoke(output.filePointer)
                            }
                        } finally {
                            // A mid-read IOException must still make every successfully written byte
                            // durable before the next Range request or a process restart.
                            output.fd.sync()
                        }
                    }
                }

                if (destination.length() < expectedBytes) {
                    throw EOFException(
                        "download ended early at ${destination.length()} / $expectedBytes bytes",
                    )
                }
            } catch (failure: Throwable) {
                if (!retryable(failure) || retries >= maxRetries) throw failure
                retries += 1
                val delayMs = retryDelayMs(retries)
                onRetry?.invoke(retries, delayMs, destination.length(), failure)
                sleeper(delayMs)
            }
        }
        check(destination.length() == expectedBytes) {
            "download size mismatch: expected $expectedBytes / actual ${destination.length()}"
        }
    }

    internal fun retryDelayMs(retryNumber: Int): Long {
        require(retryNumber >= 1)
        val shift = (retryNumber - 1).coerceAtMost(6)
        return (retryBaseDelayMs * (1L shl shift)).coerceAtMost(maxRetryDelayMs)
    }

    private fun retryable(failure: Throwable): Boolean = when (failure) {
        is IOException -> true
        else -> failure.cause?.let(::retryable) == true
    }

    private fun validateContentRange(value: String?, requestedOffset: Long, expectedBytes: Long) {
        val match = CONTENT_RANGE.matchEntire(value.orEmpty().trim())
            ?: throw IllegalArgumentException("invalid Content-Range: ${value.orEmpty()}")
        val start = match.groupValues[1].toLongOrNull()
            ?: throw IllegalArgumentException("invalid Content-Range start")
        val end = match.groupValues[2].toLongOrNull()
            ?: throw IllegalArgumentException("invalid Content-Range end")
        val total = match.groupValues[3].toLongOrNull()
            ?: throw IllegalArgumentException("invalid Content-Range total")
        require(start == requestedOffset) {
            "Content-Range starts at $start instead of requested $requestedOffset"
        }
        require(total == expectedBytes) {
            "Content-Range total $total does not match expected $expectedBytes"
        }
        require(end >= start && end < total) { "invalid Content-Range end $end" }
    }

    companion object {
        private const val HTTP_OK = 200
        private const val HTTP_PARTIAL = 206
        private const val BUFFER_BYTES = 1024 * 1024
        private val CONTENT_RANGE = Regex("bytes\\s+(\\d+)-(\\d+)/(\\d+)", RegexOption.IGNORE_CASE)
    }
}
