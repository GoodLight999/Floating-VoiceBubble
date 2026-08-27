package com.goodlight.floatingvoicebubble.model

import android.content.Context
import java.io.File

/**
 * Storage locations for LiteRT-LM model files.
 *
 * LiteRT-LM's Android API currently accepts a filesystem path, not a ContentProvider file
 * descriptor. New Gemma artifacts therefore live in the app-owned shared media directory whenever
 * external storage is available. Files placed there can be opened directly by LiteRT-LM without a
 * second model-sized copy, while remaining reachable to the user through normal device storage.
 */
object GemmaModelStorage {
    fun installDirectory(context: Context): File =
        sharedDirectory(context) ?: legacyPrivateDirectory(context)

    fun sharedDirectory(context: Context): File? {
        val root = context.externalMediaDirs.firstOrNull { it != null } ?: return null
        return File(root, "models/correction").apply { mkdirs() }
            .takeIf { it.isDirectory && it.canRead() && it.canWrite() }
    }

    fun legacyPrivateDirectory(context: Context): File =
        File(context.noBackupFilesDir, "models/correction").apply { mkdirs() }

    fun visibleModelFiles(context: Context): List<File> = buildList {
        sharedDirectory(context)?.listFiles().orEmpty()
            .filterTo(this) { it.isFile && it.name.endsWith(".litertlm", ignoreCase = true) }
        legacyPrivateDirectory(context).listFiles().orEmpty()
            .filterTo(this) { it.isFile && it.name.endsWith(".litertlm", ignoreCase = true) }
    }.distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }
        .sortedBy { it.name.lowercase() }

    fun isInSharedDirectory(context: Context, file: File): Boolean {
        val shared = sharedDirectory(context)?.canonicalFile ?: return false
        val candidate = runCatching { file.canonicalFile }.getOrNull() ?: return false
        return candidate.parentFile == shared
    }

    fun userVisiblePath(context: Context): String =
        sharedDirectory(context)?.absolutePath
            ?: "共有ストレージを利用できません（アプリ内ストレージを使用します）"
}
