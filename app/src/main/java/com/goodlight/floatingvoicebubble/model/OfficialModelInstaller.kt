package com.goodlight.floatingvoicebubble.model

import android.content.Context
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URI

data class ModelInstallProgress(
    val phase: String,
    val completedBytes: Long,
    val totalBytes: Long?,
)

sealed interface InstalledOfficialModel {
    data class Streaming(val model: StreamingAsrModel) : InstalledOfficialModel
    data class Final(val model: FinalAsrModel) : InstalledOfficialModel
    data class Gemma(val model: ImportedGemmaModel) : InstalledOfficialModel
}

class OfficialModelInstaller(context: Context) {
    private val appContext = context.applicationContext
    private val streamingStore = AsrModelStore(appContext)
    private val finalStore = FinalAsrModelStore(appContext)
    private val gemmaImporter = ModelImporter(appContext)

    fun install(
        entry: OfficialModelEntry,
        onProgress: ((ModelInstallProgress) -> Unit)? = null,
    ): InstalledOfficialModel = when (entry.kind) {
        CatalogModelKind.STREAMING_ASR -> installStreaming(entry, onProgress)
        CatalogModelKind.FINAL_ASR -> installFinal(entry, onProgress)
        CatalogModelKind.GEMMA -> installGemma(entry, onProgress)
    }

    private fun installStreaming(
        entry: OfficialModelEntry,
        onProgress: ((ModelInstallProgress) -> Unit)?,
    ): InstalledOfficialModel.Streaming {
        val chunkMs = requireNotNull(entry.chunkMs) { "Streaming ASR catalog entry has no chunk width" }
        streamingStore.ensureImportSpace(entry.estimatedInstalledBytes)
        val staging = streamingStore.createStagingDirectory(chunkMs)
        try {
            openCatalogStream(entry).use { source ->
                ModelArchiveExtractor.extractTarBz2(
                    source = source.stream,
                    destination = staging,
                    requiredNames = AsrModelStore.REQUIRED_FILES,
                ) {
                    onProgress?.invoke(
                        ModelInstallProgress("ダウンロード・展開", source.stream.count, source.totalBytes)
                    )
                }
            }
            onProgress?.invoke(ModelInstallProgress("検証・確定", 1L, 1L))
            return InstalledOfficialModel.Streaming(streamingStore.installPreparedDirectory(chunkMs, staging))
        } catch (failure: Throwable) {
            staging.deleteRecursively()
            throw failure
        }
    }

    private fun installFinal(
        entry: OfficialModelEntry,
        onProgress: ((ModelInstallProgress) -> Unit)?,
    ): InstalledOfficialModel.Final {
        finalStore.ensureImportSpace(entry.estimatedInstalledBytes)
        val staging = finalStore.createStagingDirectory()
        try {
            openCatalogStream(entry).use { source ->
                ModelArchiveExtractor.extractTarBz2(
                    source = source.stream,
                    destination = staging,
                    requiredNames = FinalAsrModelStore.REQUIRED_FILES,
                ) {
                    onProgress?.invoke(
                        ModelInstallProgress("ダウンロード・展開", source.stream.count, source.totalBytes)
                    )
                }
            }
            onProgress?.invoke(ModelInstallProgress("検証・確定", 1L, 1L))
            return InstalledOfficialModel.Final(finalStore.installPreparedDirectory(staging))
        } catch (failure: Throwable) {
            staging.deleteRecursively()
            throw failure
        }
    }

    private fun installGemma(
        entry: OfficialModelEntry,
        onProgress: ((ModelInstallProgress) -> Unit)?,
    ): InstalledOfficialModel.Gemma {
        val expectedBytes = requireNotNull(entry.expectedBytes) { "Gemma catalog entry has no exact size" }
        val expectedSha = requireNotNull(entry.expectedSha256) { "Gemma catalog entry has no SHA-256" }
        val displayName = requireNotNull(entry.displayName) { "Gemma catalog entry has no file name" }
        val staging = gemmaImporter.resumableDownloadFile(displayName, expectedSha, expectedBytes)

        downloadGemmaResumable(entry, staging, expectedBytes, onProgress)
        onProgress?.invoke(ModelInstallProgress("SHA-256を検証", 0L, expectedBytes))
        val installed = gemmaImporter.installVerifiedDownloadedFile(
            staging = staging,
            displayName = displayName,
            expectedBytes = expectedBytes,
            expectedSha256 = expectedSha,
        ) { hashed, total ->
            onProgress?.invoke(ModelInstallProgress("SHA-256を検証", hashed, total))
        }
        check(installed.fingerprint.knownOfficialArtifact) {
            "Gemmaモデルはハッシュ一致したにもかかわらず公式artifactとして認識できませんでした。"
        }
        return InstalledOfficialModel.Gemma(installed)
    }

    private fun downloadGemmaResumable(
        entry: OfficialModelEntry,
        staging: java.io.File,
        expectedBytes: Long,
        onProgress: ((ModelInstallProgress) -> Unit)?,
    ) {
        if (staging.length() > expectedBytes) staging.delete()
        onProgress?.invoke(ModelInstallProgress("ダウンロード", staging.length(), expectedBytes))

        var transientFailures = 0
        while (staging.length() < expectedBytes) {
            val requestedOffset = staging.length()
            try {
                openCatalogStream(entry, requestedOffset.takeIf { it > 0L }).use { source ->
                    val append = requestedOffset > 0L && source.status == HttpURLConnection.HTTP_PARTIAL
                    if (append) {
                        val contentRange = source.contentRange.orEmpty()
                        require(contentRange.startsWith("bytes $requestedOffset-")) {
                            "モデル取得先が不正なContent-Rangeを返しました: $contentRange"
                        }
                    }

                    RandomAccessFile(staging, "rw").use { output ->
                        if (append) {
                            output.seek(requestedOffset)
                        } else {
                            // Server ignored Range and returned 200. Reuse this response as a clean
                            // restart instead of issuing another multi-GB request.
                            output.setLength(0L)
                            output.seek(0L)
                        }
                        val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                        while (true) {
                            val read = source.stream.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            val completed = output.filePointer
                            check(completed <= expectedBytes) {
                                "モデル取得先が期待サイズを超えるデータを返しました。"
                            }
                            onProgress?.invoke(ModelInstallProgress("ダウンロード", completed, expectedBytes))
                        }
                        output.fd.sync()
                    }
                }
                if (staging.length() < expectedBytes) {
                    throw IOException(
                        "モデル取得が途中で終了しました。${staging.length()} / $expectedBytes bytes から再開します。",
                    )
                }
            } catch (failure: Throwable) {
                if (!isRetryableDownloadFailure(failure) || transientFailures >= MAX_TRANSIENT_RETRIES) {
                    throw failure
                }
                transientFailures += 1
                val exponential = RETRY_BASE_DELAY_MS * (1L shl (transientFailures - 1).coerceAtMost(6))
                val delayMs = exponential.coerceAtMost(MAX_RETRY_DELAY_MS)
                onProgress?.invoke(
                    ModelInstallProgress(
                        "通信が途切れました。${formatRetryDelay(delayMs)}後に途中から再開",
                        staging.length(),
                        expectedBytes,
                    ),
                )
                Thread.sleep(delayMs)
            }
        }
        check(staging.length() == expectedBytes) {
            "Gemmaモデルのダウンロードサイズが一致しません。期待 $expectedBytes bytes / 実際 ${staging.length()} bytes"
        }
    }

    private fun formatRetryDelay(delayMs: Long): String = if (delayMs % 1000L == 0L) {
        "${delayMs / 1000L}秒"
    } else {
        "%.2f秒".format(java.util.Locale.ROOT, delayMs / 1000.0)
    }

    private fun isRetryableDownloadFailure(failure: Throwable): Boolean = when (failure) {
        is RetryableHttpException -> true
        is IOException -> true
        else -> failure.cause?.let(::isRetryableDownloadFailure) == true
    }

    private fun openCatalogStream(entry: OfficialModelEntry, rangeStart: Long? = null): NetworkStream {
        require(OfficialModelCatalog.find(entry.id) == entry) {
            "未登録のモデル取得先は自動インストールできません。"
        }
        val initial = URI(entry.url)
        require(initial.scheme.equals("https", ignoreCase = true)) { "モデル取得先はHTTPS必須です。" }

        var current = initial.toURL()
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            val connection = (current.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("User-Agent", "FloatingVoiceBubble/${appContext.packageName}")
                rangeStart?.takeIf { it > 0L }?.let { setRequestProperty("Range", "bytes=$it-") }
            }
            val status = connection.responseCode
            if (status in REDIRECT_CODES) {
                val location = connection.getHeaderField("Location")
                    ?: run {
                        connection.disconnect()
                        error("モデル取得先のリダイレクトにLocationがありません。")
                    }
                connection.disconnect()
                require(redirectCount < MAX_REDIRECTS) { "モデル取得先のリダイレクトが多すぎます。" }
                val next = URI(current.toString()).resolve(location)
                require(next.scheme.equals("https", ignoreCase = true)) {
                    "モデル取得先がHTTPS以外へリダイレクトしました。"
                }
                current = next.toURL()
                return@repeat
            }
            if (status !in 200..299) {
                val errorText = connection.errorStream?.bufferedReader()?.use { it.readText().take(500) }.orEmpty()
                connection.disconnect()
                val message = "モデル取得に失敗しました: HTTP $status ${errorText.replace(Regex("\\s+"), " ")}"
                if (status == 408 || status == 429 || status in 500..599) {
                    throw RetryableHttpException(message)
                }
                error(message)
            }
            val total = connection.contentLengthLong.takeIf { it > 0L }
            return NetworkStream(
                connection = connection,
                stream = ProgressInputStream(connection.inputStream.buffered()),
                totalBytes = total,
                status = status,
                contentRange = connection.getHeaderField("Content-Range"),
            )
        }
        error("モデル取得先を解決できませんでした。")
    }

    private class ProgressInputStream(input: InputStream) : FilterInputStream(input) {
        var count: Long = 0L
            private set

        override fun read(): Int = super.read().also { if (it >= 0) count += 1L }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            super.read(buffer, offset, length).also { if (it > 0) count += it.toLong() }
    }

    private class NetworkStream(
        private val connection: HttpURLConnection,
        val stream: ProgressInputStream,
        val totalBytes: Long?,
        val status: Int,
        val contentRange: String?,
    ) : AutoCloseable {
        override fun close() {
            runCatching { stream.close() }
            connection.disconnect()
        }
    }

    private class RetryableHttpException(message: String) : IOException(message)

    companion object {
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        private const val MAX_REDIRECTS = 8
        private const val MAX_TRANSIENT_RETRIES = 8
        private const val CONNECT_TIMEOUT_MS = 30_000
        // Inactivity timeout, not a total-download deadline. Large Hugging Face blobs may legitimately
        // take many minutes; a brief CDN stall must not discard gigabytes of completed work.
        private const val READ_TIMEOUT_MS = 300_000
        private const val DOWNLOAD_BUFFER_BYTES = 1024 * 1024
        private const val RETRY_BASE_DELAY_MS = 750L
        private const val MAX_RETRY_DELAY_MS = 12_000L
    }
}
