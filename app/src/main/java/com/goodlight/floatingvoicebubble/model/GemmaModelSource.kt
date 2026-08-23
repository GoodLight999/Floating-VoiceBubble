package com.goodlight.floatingvoicebubble.model

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.security.MessageDigest

/**
 * Resolves Gemma model references without forcing a second multi-gigabyte copy.
 *
 * Internal downloads are ordinary file paths. User-selected models are persisted content:// URIs;
 * when LiteRT-LM needs a path, a seekable ParcelFileDescriptor is held open and exposed through
 * /proc/self/fd/<n>. The descriptor lease lives for as long as the cached LiteRT-LM engine.
 */
object GemmaModelSource {
    data class ExternalSelection(
        val reference: String,
        val displayName: String,
        val fingerprint: GemmaModelFingerprint,
    )

    class Opened internal constructor(
        val enginePath: String,
        val key: String,
        private val lease: ParcelFileDescriptor? = null,
    ) : AutoCloseable {
        override fun close() {
            runCatching { lease?.close() }
        }
    }

    fun isExternal(reference: String): Boolean =
        reference.trim().startsWith("content://", ignoreCase = true)

    fun isAvailable(context: Context, reference: String): Boolean {
        if (reference.isBlank()) return false
        if (!isExternal(reference)) return File(reference).isFile
        return runCatching {
            context.contentResolver.openFileDescriptor(Uri.parse(reference), "r")?.use { descriptor ->
                ensureSeekable(descriptor)
                true
            } ?: false
        }.getOrDefault(false)
    }

    fun displayName(context: Context, reference: String): String {
        if (reference.isBlank()) return ""
        if (!isExternal(reference)) return File(reference).name
        val uri = Uri.parse(reference)
        return queryMetadata(context.contentResolver, uri).first
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "external-model.litertlm"
    }

    fun openForEngine(context: Context, reference: String): Opened {
        require(reference.isNotBlank()) { "Gemma model is not configured" }
        if (!isExternal(reference)) {
            val file = File(reference)
            require(file.isFile) { "Gemma model file is missing: ${file.absolutePath}" }
            return Opened(file.absolutePath, "file:${file.canonicalPath}")
        }

        val uri = Uri.parse(reference)
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: error("選択したGemmaモデルを開けませんでした。ファイルへの権限を確認してください。")
        try {
            ensureSeekable(descriptor)
            return Opened(
                enginePath = "/proc/self/fd/${descriptor.fd}",
                key = "content:$reference",
                lease = descriptor,
            )
        } catch (failure: Throwable) {
            descriptor.close()
            throw failure
        }
    }

    fun persistReadPermission(context: Context, uri: Uri) {
        require(uri.scheme.equals(ContentResolver.SCHEME_CONTENT, ignoreCase = true)) {
            "外部GemmaモデルはAndroidのファイル選択画面から選択してください。"
        }
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun releaseReadPermission(context: Context, reference: String) {
        if (!isExternal(reference)) return
        val uri = Uri.parse(reference)
        runCatching {
            context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * Verifies a user-selected model in place. No application-private model copy is created.
     * The full read is deliberate: it proves the selected document remains readable and identifies
     * exact official artifacts by SHA-256 before the reference is persisted as the active model.
     */
    fun verifyExternal(
        context: Context,
        uri: Uri,
        onProgress: ((readBytes: Long, totalBytes: Long?) -> Unit)? = null,
    ): ExternalSelection {
        require(uri.scheme.equals(ContentResolver.SCHEME_CONTENT, ignoreCase = true)) {
            "Androidのファイル選択画面から .litertlm を選択してください。"
        }
        val (displayNameOrNull, sizeOrNull) = queryMetadata(context.contentResolver, uri)
        val displayName = displayNameOrNull ?: "external-model.litertlm"
        require(displayName.endsWith(".litertlm", ignoreCase = true)) {
            "LiteRT-LM の .litertlm モデルを選択してください。"
        }

        // Prove that the provider exposes a real random-access descriptor. LiteRT-LM receives a
        // filesystem path and cannot consume pipe-only document providers without materialization.
        context.contentResolver.openFileDescriptor(uri, "r")?.use(::ensureSeekable)
            ?: error("選択したGemmaモデルを開けませんでした。")

        val digest = MessageDigest.getInstance("SHA-256")
        var readBytes = 0L
        context.contentResolver.openInputStream(uri)?.buffered(COPY_BUFFER_BYTES).use { input ->
            requireNotNull(input) { "選択したGemmaモデルを読み取れませんでした。" }
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                digest.update(buffer, 0, count)
                readBytes += count
                onProgress?.invoke(readBytes, sizeOrNull)
            }
        }
        require(readBytes >= MIN_MODEL_BYTES) { "選択したGemmaモデルが小さすぎます。" }
        sizeOrNull?.let { expected ->
            require(expected <= 0L || expected == readBytes) {
                "選択したモデルの読み取りサイズが一致しません。期待 $expected bytes / 実際 $readBytes bytes"
            }
        }
        val sha256 = digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return ExternalSelection(
            reference = uri.toString(),
            displayName = displayName,
            fingerprint = GemmaModelVerifier.identify(readBytes, sha256),
        )
    }

    private fun ensureSeekable(descriptor: ParcelFileDescriptor) {
        try {
            Os.lseek(descriptor.fileDescriptor, 0L, OsConstants.SEEK_CUR)
        } catch (failure: Throwable) {
            throw IllegalArgumentException(
                "この保存先はモデルを直接参照できません。端末内ストレージのDocuments/Download等に置いた .litertlm を選択してください。",
                failure,
            )
        }
    }

    private fun queryMetadata(resolver: ContentResolver, uri: Uri): Pair<String?, Long?> {
        var name: String? = null
        var size: Long? = null
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    cursor.getLong(sizeIndex).takeIf { it > 0L }?.let { size = it }
                }
            }
        }
        return name to size
    }

    private const val COPY_BUFFER_BYTES = 1024 * 1024
    private const val MIN_MODEL_BYTES = 1L * 1024 * 1024
}
