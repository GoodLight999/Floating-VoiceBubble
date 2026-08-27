package com.goodlight.floatingvoicebubble.model

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.security.MessageDigest

/**
 * Resolves Gemma model references for LiteRT-LM.
 *
 * LiteRT-LM's Android EngineConfig accepts a filesystem modelPath. Normal file references therefore
 * open directly. Legacy content:// references are recognized only so existing installs fail with an
 * actionable migration message instead of silently falling back or relying on /proc/self/fd hacks
 * that are denied on current Android releases.
 */
object GemmaModelSource {
    data class DirectSelection(
        val reference: String,
        val displayName: String,
        val fingerprint: GemmaModelFingerprint,
    )

    class Opened internal constructor(
        val enginePath: String,
        val key: String,
    ) : AutoCloseable {
        override fun close() = Unit
    }

    fun isExternal(reference: String): Boolean =
        reference.trim().startsWith("content://", ignoreCase = true)

    fun isAvailable(context: Context, reference: String): Boolean {
        if (reference.isBlank() || isExternal(reference)) return false
        return File(reference).isFile
    }

    fun displayName(context: Context, reference: String): String {
        if (reference.isBlank()) return ""
        if (!isExternal(reference)) return File(reference).name
        val uri = Uri.parse(reference)
        return queryMetadata(context.contentResolver, uri).first
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "legacy-content-model.litertlm"
    }

    fun openForEngine(context: Context, reference: String): Opened {
        require(reference.isNotBlank()) { "Gemma model is not configured" }
        if (isExternal(reference)) {
            error(
                "このGemma設定はAndroidのcontent URIです。現在のLiteRT-LMは通常のファイルパスを必要とするため直接利用できません。" +
                    "共有モデルフォルダ ${GemmaModelStorage.userVisiblePath(context)} に .litertlm を置いて再選択するか、" +
                    "モデル・API画面からコピーして取り込んでください。",
            )
        }
        val file = File(reference)
        require(file.isFile) { "Gemma model file is missing: ${file.absolutePath}" }
        return Opened(file.absolutePath, "file:${file.canonicalPath}")
    }

    /**
     * Hashes a normal filesystem model in place and returns the same absolute path. No model-sized
     * duplicate is created. Use this for files in GemmaModelStorage.sharedDirectory().
     */
    fun verifyDirectFile(
        file: File,
        onProgress: ((readBytes: Long, totalBytes: Long?) -> Unit)? = null,
    ): DirectSelection {
        require(file.isFile) { "Gemmaモデルファイルが見つかりません: ${file.absolutePath}" }
        require(file.name.endsWith(".litertlm", ignoreCase = true)) {
            "LiteRT-LM の .litertlm モデルを選択してください。"
        }
        val total = file.length()
        require(total >= MIN_MODEL_BYTES) { "選択したGemmaモデルが小さすぎます。" }

        val digest = MessageDigest.getInstance("SHA-256")
        var readBytes = 0L
        file.inputStream().buffered(COPY_BUFFER_BYTES).use { input ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                digest.update(buffer, 0, count)
                readBytes += count
                onProgress?.invoke(readBytes, total)
            }
        }
        require(readBytes == total) {
            "選択したモデルの読み取りサイズが一致しません。期待 $total bytes / 実際 $readBytes bytes"
        }
        val sha256 = digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return DirectSelection(
            reference = file.absolutePath,
            displayName = file.name,
            fingerprint = GemmaModelVerifier.identify(readBytes, sha256),
        )
    }

    /** Compatibility cleanup for installs that persisted a legacy SAF grant. */
    fun releaseReadPermission(context: Context, reference: String) {
        if (!isExternal(reference)) return
        val uri = Uri.parse(reference)
        runCatching {
            context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** Compatibility helper retained only for old migrations; new selections are copied instead. */
    fun persistReadPermission(context: Context, uri: Uri) {
        require(uri.scheme.equals(ContentResolver.SCHEME_CONTENT, ignoreCase = true)) {
            "Androidのファイル選択画面からモデルを選択してください。"
        }
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
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
