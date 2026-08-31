package com.goodlight.floatingvoicebubble.model

import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResumableFileDownloaderTest {
    @Test
    fun interruptedTransferResumesFromExactPersistedOffset() {
        val data = ByteArray(2 * 1024 * 1024 + 317) { index -> (index * 31).toByte() }
        val dir = Files.createTempDirectory("voicebubble-resume").toFile()
        val destination = dir.resolve("model.part")
        val requestedRanges = mutableListOf<Long?>()
        var attempts = 0

        try {
            ResumableFileDownloader(maxRetries = 2, sleeper = {}).download(
                destination = destination,
                expectedBytes = data.size.toLong(),
                open = { rangeStart ->
                    requestedRanges += rangeStart
                    attempts += 1
                    if (attempts == 1) {
                        ResumableDownloadResponse(
                            status = 200,
                            contentRange = null,
                            body = FailAfterInputStream(data, failAfter = 730_123),
                        )
                    } else {
                        val offset = requireNotNull(rangeStart).toInt()
                        ResumableDownloadResponse(
                            status = 206,
                            contentRange = "bytes $offset-${data.lastIndex}/${data.size}",
                            body = ByteArrayInputStream(data.copyOfRange(offset, data.size)),
                        )
                    }
                },
            )

            assertEquals(2, attempts)
            assertNull(requestedRanges.first())
            val resumedAt = requireNotNull(requestedRanges.last())
            assertEquals(730_123L, resumedAt)
            assertContentEquals(data, destination.readBytes())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun serverIgnoringRangeReusesIts200ResponseAsCleanRestart() {
        val data = ByteArray(256 * 1024 + 7) { index -> (index * 13).toByte() }
        val dir = Files.createTempDirectory("voicebubble-range-ignore").toFile()
        val destination = dir.resolve("model.part").apply {
            writeBytes(data.copyOfRange(0, 40_000))
        }
        var requested: Long? = null

        try {
            ResumableFileDownloader(maxRetries = 0, sleeper = {}).download(
                destination = destination,
                expectedBytes = data.size.toLong(),
                open = { rangeStart ->
                    requested = rangeStart
                    ResumableDownloadResponse(
                        status = 200,
                        contentRange = null,
                        body = ByteArrayInputStream(data),
                    )
                },
            )

            assertEquals(40_000L, requested)
            assertContentEquals(data, destination.readBytes())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun malformedContentRangeIsRejectedInsteadOfCorruptingPartial() {
        val data = ByteArray(64 * 1024) { it.toByte() }
        val dir = Files.createTempDirectory("voicebubble-bad-range").toFile()
        val destination = dir.resolve("model.part").apply {
            writeBytes(data.copyOfRange(0, 10_000))
        }

        try {
            assertFailsWith<IllegalArgumentException> {
                ResumableFileDownloader(maxRetries = 4, sleeper = {}).download(
                    destination = destination,
                    expectedBytes = data.size.toLong(),
                    open = { rangeStart ->
                        val offset = requireNotNull(rangeStart).toInt()
                        ResumableDownloadResponse(
                            status = 206,
                            contentRange = "bytes ${offset + 1}-${data.lastIndex}/${data.size}",
                            body = ByteArrayInputStream(data.copyOfRange(offset, data.size)),
                        )
                    },
                )
            }
            assertEquals(10_000L, destination.length())
            assertContentEquals(data.copyOfRange(0, 10_000), destination.readBytes())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun repeatedEarlyEofStopsAfterBoundedRetriesAndKeepsPartial() {
        val data = ByteArray(80_000) { (it * 7).toByte() }
        val dir = Files.createTempDirectory("voicebubble-eof").toFile()
        val destination = dir.resolve("model.part")
        var attempts = 0
        val retryNumbers = mutableListOf<Int>()

        try {
            assertFailsWith<EOFException> {
                ResumableFileDownloader(maxRetries = 2, sleeper = {}).download(
                    destination = destination,
                    expectedBytes = data.size.toLong(),
                    open = { rangeStart ->
                        attempts += 1
                        val offset = (rangeStart ?: 0L).toInt()
                        val endExclusive = min(offset + 8_000, data.size)
                        ResumableDownloadResponse(
                            status = if (offset == 0) 200 else 206,
                            contentRange = if (offset == 0) null else "bytes $offset-${endExclusive - 1}/${data.size}",
                            body = ByteArrayInputStream(data.copyOfRange(offset, endExclusive)),
                        )
                    },
                    onRetry = { retry, _, _, _ -> retryNumbers += retry },
                )
            }
            assertEquals(3, attempts)
            assertEquals(listOf(1, 2), retryNumbers)
            assertEquals(24_000L, destination.length())
            assertContentEquals(data.copyOfRange(0, 24_000), destination.readBytes())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun retryBackoffIsCapped() {
        val downloader = ResumableFileDownloader(
            retryBaseDelayMs = 750L,
            maxRetryDelayMs = 12_000L,
            sleeper = {},
        )
        assertEquals(750L, downloader.retryDelayMs(1))
        assertEquals(1_500L, downloader.retryDelayMs(2))
        assertEquals(12_000L, downloader.retryDelayMs(6))
        assertEquals(12_000L, downloader.retryDelayMs(20))
    }

    private class FailAfterInputStream(
        private val data: ByteArray,
        private val failAfter: Int,
    ) : InputStream() {
        private var index = 0

        override fun read(): Int {
            if (index >= failAfter) throw IOException("simulated network interruption")
            if (index >= data.size) return -1
            return data[index++].toInt() and 0xff
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (index >= failAfter) throw IOException("simulated network interruption")
            if (index >= data.size) return -1
            val count = min(length, min(failAfter - index, data.size - index))
            if (count <= 0) throw IOException("simulated network interruption")
            data.copyInto(buffer, offset, index, index + count)
            index += count
            return count
        }
    }
}
