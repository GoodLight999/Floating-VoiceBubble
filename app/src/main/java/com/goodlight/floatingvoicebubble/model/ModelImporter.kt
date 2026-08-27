package com.goodlight.floatingvoicebubble.model

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

data class ImportedGemmaModel(
    val file: File,
    val fingerprint: GemmaModelFingerprint,
)

internal class ModelHashMismatchException(message: String) : IllegalStateException(message)

class ModelImporter(private val context: Context) {
    private val modelDir = File(context.noBackupFilesDir, "models/correction").apply {
        mkdirs()
        AtomicFileInstaller.recoverBackups(this)
    }

    /** Backward-compatible entry point used by the legacy settings UI. */
    fun importGemma(uri: Uri): File = importGemmaVerified(uri).file

    /** Copies once while simultaneously calculating the exact fingerprint. */
    fun importGemmaVerified(uri: Uri): ImportedGemmaModel {
        val metadata = sourceMetadata(uri)
        require(metadata.displayName.endsWith(".litertlm", ignoreCase = true)) {
            "LiteRT-LM の .litertlm モデルを選択してください。"
        }
        ensureDiskSpace(metadata.sizeBytes)
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "モデルを開けませんでした。" }
            return installVerifiedStream(
                displayName = metadata.displayName,
                expectedBytes = metadata.sizeBytes.takeIf { it > 0L },
                expectedSha256 = null,
                input = input,
            )
        }
    }

    /**
     * Installs a model from a trusted streaming source. Kept for compatibility with callers that
     * cannot provide a seekable staging file.
     */
    fun installVerifiedStream(
        displayName: String,
        expectedBytes: Long?,
        expectedSha256: String?,
        input: InputStream,
        onProgress: ((copiedBytes: Long, totalBytes: Long?) -> Unit)? = null,
    ): ImportedGemmaModel {
        requireValidRequest(displayName, expectedBytes, expectedSha256)
        ensureDiskSpace(expectedBytes ?: -1L)

        val safeName = safeName(displayName)
        val destination = File(modelDir, safeName)
        val temporary = File(modelDir, ".$safeName.part-${UUID.randomUUID()}")
        val digest = MessageDigest.getInstance("SHA-256")
        var copied = 0L

        try {
            FileOutputStream(temporary).buffered().use { output ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    copied += read
                    onProgress?.invoke(copied, expectedBytes)
                }
                output.flush()
            }
            FileOutputStream(temporary, true).use { it.fd.sync() }
            val fingerprint = verifyFingerprint(
                bytes = copied,
                sha256 = digest.digest().toHex(),
                expectedBytes = expectedBytes,
                expectedSha256 = expectedSha256,
            )
            AtomicFileInstaller.replace(temporary, destination, "Gemmaモデル")
            return ImportedGemmaModel(destination, fingerprint)
        } catch (failure: Throwable) {
            temporary.delete()
            throw failure
        }
    }

    /**
     * Stable partial file for resumable official downloads. It lives beside the final model so a
     * verified completion can be promoted by rename instead of copying another multi-gigabyte file.
     */
    internal fun resumableDownloadFile(
        displayName: String,
        expectedSha256: String,
        expectedBytes: Long,
    ): File {
        requireValidRequest(displayName, expectedBytes, expectedSha256)
        val safeName = safeName(displayName)
        val target = File(modelDir, ".$safeName.${expectedSha256.lowercase().take(16)}.download.part")
        modelDir.listFiles().orEmpty()
            .filter { it.isFile && it.name.startsWith(".$safeName.") && it.name.endsWith(".download.part") && it != target }
            .forEach { runCatching { it.delete() } }
        if (target.length() > expectedBytes) target.delete()
        ensureResumeDiskSpace(expectedBytes, target.length())
        return target
    }

    /**
     * Verifies an already-downloaded staging file in place and atomically promotes it. No second
     * model-sized copy is created.
     */
    internal fun installVerifiedDownloadedFile(
        staging: File,
        displayName: String,
        expectedBytes: Long,
        expectedSha256: String,
        onProgress: ((hashedBytes: Long, totalBytes: Long) -> Unit)? = null,
    ): ImportedGemmaModel {
        requireValidRequest(displayName, expectedBytes, expectedSha256)
        require(staging.isFile) { "Gemmaダウンロード途中ファイルが見つかりません。" }
        require(staging.parentFile?.canonicalFile == modelDir.canonicalFile) {
            "Gemmaダウンロード途中ファイルの保存場所が不正です。"
        }
        require(staging.length() == expectedBytes) {
            "Gemmaモデルのダウンロードサイズが一致しません。期待 $expectedBytes bytes / 実際 ${staging.length()} bytes"
        }

        val digest = MessageDigest.getInstance("SHA-256")
        var hashed = 0L
        staging.inputStream().buffered(COPY_BUFFER_BYTES).use { input ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
                hashed += read
                onProgress?.invoke(hashed, expectedBytes)
            }
        }
        val fingerprint = verifyFingerprint(
            bytes = hashed,
            sha256 = digest.digest().toHex(),
            expectedBytes = expectedBytes,
            expectedSha256 = expectedSha256,
        )
        FileOutputStream(staging, true).use { it.fd.sync() }

        val destination = File(modelDir, safeName(displayName))
        AtomicFileInstaller.replace(staging, destination, "Gemmaモデル")
        return ImportedGemmaModel(destination, fingerprint)
    }

    private fun verifyFingerprint(
        bytes: Long,
        sha256: String,
        expectedBytes: Long?,
        expectedSha256: String?,
    ): GemmaModelFingerprint {
        check(bytes >= MIN_MODEL_BYTES) { "モデルファイルが小さすぎるため拒否しました。" }
        expectedBytes?.let { expected ->
            check(bytes == expected) {
                "モデルのサイズが一致しません。期待 $expected bytes / 実際 $bytes bytes"
            }
        }
        expectedSha256?.let { expected ->
            if (!sha256.equals(expected, ignoreCase = true)) {
                throw ModelHashMismatchException(
                    "GemmaモデルのSHA-256が一致しません。期待 ${expected.lowercase()} / 実際 $sha256",
                )
            }
        }
        return GemmaModelVerifier.identify(bytes, sha256)
    }

    private fun requireValidRequest(displayName: String, expectedBytes: Long?, expectedSha256: String?) {
        require(displayName.endsWith(".litertlm", ignoreCase = true)) {
            "LiteRT-LM の .litertlm モデルを指定してください。"
        }
        expectedBytes?.let { require(it >= MIN_MODEL_BYTES) { "Gemmaモデルの期待サイズが不正です。" } }
        expectedSha256?.let { require(SHA256.matches(it)) { "GemmaモデルのSHA-256が不正です。" } }
    }

    private fun sourceMetadata(uri: Uri): SourceMetadata {
        var displayName: String? = null
        var size = -1L
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) displayName = cursor.getString(nameIndex)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
            }
        }
        if (size <= 0L) {
            size = runCatching {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            }.getOrDefault(-1L)
        }
        return SourceMetadata(
            displayName = displayName?.takeIf(String::isNotBlank) ?: "gemma-model.litertlm",
            sizeBytes = size,
        )
    }

    private fun ensureDiskSpace(sourceBytes: Long) {
        val available = StatFs(modelDir.absolutePath).availableBytes
        val required = if (sourceBytes > 0L) {
            sourceBytes + DISK_HEADROOM_BYTES
        } else {
            UNKNOWN_SIZE_REQUIRED_FREE_BYTES
        }
        require(available >= required) {
            "Gemmaモデル用の空き容量が不足しています。必要約 ${required / (1024 * 1024)} MiB、空き ${available / (1024 * 1024)} MiB。"
        }
    }

    private fun ensureResumeDiskSpace(expectedBytes: Long, alreadyDownloaded: Long) {
        val available = StatFs(modelDir.absolutePath).availableBytes
        val remaining = (expectedBytes - alreadyDownloaded).coerceAtLeast(0L)
        val required = remaining + DISK_HEADROOM_BYTES
        require(available >= required) {
            "Gemmaモデルの続き用の空き容量が不足しています。残り約 ${remaining / (1024 * 1024)} MiB、余裕込み必要約 ${required / (1024 * 1024)} MiB、空き ${available / (1024 * 1024)} MiB。"
        }
    }

    private fun safeName(displayName: String): String = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private data class SourceMetadata(val displayName: String, val sizeBytes: Long)

    companion object {
        private val SHA256 = Regex("[0-9a-fA-F]{64}")
        private const val COPY_BUFFER_BYTES = 1024 * 1024
        private const val MIN_MODEL_BYTES = 1L * 1024 * 1024
        private const val DISK_HEADROOM_BYTES = 256L * 1024 * 1024
        private const val UNKNOWN_SIZE_REQUIRED_FREE_BYTES = 4L * 1024 * 1024 * 1024
    }
}
