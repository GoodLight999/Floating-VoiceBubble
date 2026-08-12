package com.goodlight.floatingvoicebubble.model

import android.content.Context
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

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
                extractSelectedTarBz2(
                    source = source,
                    destination = staging,
                    requiredNames = AsrModelStore.REQUIRED_FILES,
                    onProgress = onProgress,
                )
            }
            onProgress?.invoke(ModelInstallProgress("検証・確定", 1L, 1L))
            return InstalledOfficialModel.Streaming(
                streamingStore.installPreparedDirectory(chunkMs, staging),
            )
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
                extractSelectedTarBz2(
                    source = source,
                    destination = staging,
                    requiredNames = FinalAsrModelStore.REQUIRED_FILES,
                    onProgress = onProgress,
                )
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
        openCatalogStream(entry).use { source ->
            val installed = gemmaImporter.installVerifiedStream(
                displayName = displayName,
                expectedBytes = expectedBytes,
                expectedSha256 = expectedSha,
                input = source.stream,
            ) { copied, total ->
                onProgress?.invoke(ModelInstallProgress("ダウンロード・検証", copied, total))
            }
            check(installed.fingerprint.knownOfficialArtifact) {
                "Gemmaモデルはハッシュ一致したにもかかわらず公式artifactとして認識できませんでした。"
            }
            return InstalledOfficialModel.Gemma(installed)
        }
    }

    private fun openCatalogStream(entry: OfficialModelEntry): NetworkStream {
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
                error("モデル取得に失敗しました: HTTP $status ${errorText.replace(Regex("\\s+"), " ")}")
            }
            val total = connection.contentLengthLong.takeIf { it > 0L }
            val counting = ProgressInputStream(connection.inputStream.buffered(), total)
            return NetworkStream(connection, counting, total)
        }
        error("モデル取得先を解決できませんでした。")
    }

    private fun extractSelectedTarBz2(
        source: NetworkStream,
        destination: File,
        requiredNames: Set<String>,
        onProgress: ((ModelInstallProgress) -> Unit)?,
    ) {
        val progressSource = source.stream
        BZip2CompressorInputStream(progressSource, true).use { bzip ->
            TarArchiveInputStream(bzip).use { tar ->
                val found = mutableSetOf<String>()
                while (true) {
                    val entry = tar.nextEntry ?: break
                    if (!entry.isFile) continue
                    val baseName = entry.name.substringAfterLast('/').substringAfterLast('\\')
                    if (baseName !in requiredNames) continue
                    check(found.add(baseName)) { "モデルアーカイブ内で $baseName が重複しています。" }
                    val outputFile = File(destination, baseName)
                    FileOutputStream(outputFile).use { output ->
                        val buffer = ByteArray(COPY_BUFFER_BYTES)
                        while (true) {
                            val read = tar.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            onProgress?.invoke(
                                ModelInstallProgress(
                                    phase = "ダウンロード・展開",
                                    completedBytes = progressSource.count,
                                    totalBytes = source.totalBytes,
                                ),
                            )
                        }
                        output.fd.sync()
                    }
                }
                val missing = requiredNames - found
                require(missing.isEmpty()) { "モデルアーカイブに必要ファイルがありません: ${missing.sorted().joinToString()}" }
            }
        }
    }

    private class ProgressInputStream(
        input: InputStream,
        val totalBytes: Long?,
    ) : FilterInputStream(input) {
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
    ) : AutoCloseable {
        override fun close() {
            runCatching { stream.close() }
            connection.disconnect()
        }
    }

    companion object {
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        private const val MAX_REDIRECTS = 8
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val COPY_BUFFER_BYTES = 1024 * 1024
    }
}
