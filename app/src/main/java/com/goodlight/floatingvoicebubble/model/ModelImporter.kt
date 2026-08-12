package com.goodlight.floatingvoicebubble.model

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

data class ImportedGemmaModel(
    val file: File,
    val fingerprint: GemmaModelFingerprint,
)

class ModelImporter(private val context: Context) {
    private val modelDir = File(context.noBackupFilesDir, "models/correction").apply {
        mkdirs()
        AtomicFileInstaller.recoverBackups(this)
    }

    fun importGemma(uri: Uri): ImportedGemmaModel {
        val metadata = sourceMetadata(uri)
        require(metadata.displayName.endsWith(".litertlm", ignoreCase = true)) {
            "LiteRT-LM の .litertlm モデルを選択してください。"
        }
        ensureDiskSpace(metadata.sizeBytes)

        val safeName = metadata.displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val destination = File(modelDir, safeName)
        val temporary = File(modelDir, ".$safeName.part-${UUID.randomUUID()}")
        val digest = MessageDigest.getInstance("SHA-256")
        var copied = 0L

        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "モデルを開けませんでした。" }
                FileOutputStream(temporary).buffered().use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        copied += read
                    }
                    output.flush()
                }
            }
            check(copied >= MIN_MODEL_BYTES) { "モデルファイルが小さすぎるため拒否しました。" }
            if (metadata.sizeBytes > 0L) {
                check(copied == metadata.sizeBytes) {
                    "モデルのコピーサイズが一致しません。期待 ${metadata.sizeBytes} bytes / 実際 $copied bytes"
                }
            }
            FileOutputStream(temporary, true).use { it.fd.sync() }

            val hash = digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
            val fingerprint = GemmaModelVerifier.identify(copied, hash)
            AtomicFileInstaller.replace(temporary, destination, "Gemmaモデル")
            return ImportedGemmaModel(destination, fingerprint)
        } catch (failure: Throwable) {
            temporary.delete()
            throw failure
        }
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

    private data class SourceMetadata(val displayName: String, val sizeBytes: Long)

    companion object {
        private const val COPY_BUFFER_BYTES = 1024 * 1024
        private const val MIN_MODEL_BYTES = 1L * 1024 * 1024
        private const val DISK_HEADROOM_BYTES = 256L * 1024 * 1024
        private const val UNKNOWN_SIZE_REQUIRED_FREE_BYTES = 4L * 1024 * 1024 * 1024
    }
}
