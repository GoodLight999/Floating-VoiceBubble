package com.goodlight.floatingvoicebubble.model

import java.io.File
import java.util.UUID

/** Crash-safe replacement for large model files in the same directory/filesystem. */
internal object AtomicFileInstaller {
    fun replace(temporary: File, destination: File, label: String) {
        require(temporary.isFile) { "$label temporary file is missing" }
        require(temporary.parentFile?.canonicalFile == destination.parentFile?.canonicalFile) {
            "$label temporary and destination files must share a parent"
        }

        val backup = File(
            destination.parentFile,
            ".${destination.name}.backup-${UUID.randomUUID()}",
        )
        var oldMovedAside = false

        try {
            if (destination.exists()) {
                check(destination.isFile) { "$label destination is not a file" }
                check(destination.renameTo(backup)) { "旧${label}を安全に退避できませんでした。" }
                oldMovedAside = true
            }

            check(temporary.renameTo(destination)) { "${label}を確定保存できませんでした。" }
            if (oldMovedAside && backup.exists()) runCatching { backup.delete() }
        } catch (failure: Throwable) {
            if (!destination.exists() && oldMovedAside && backup.isFile) {
                if (!backup.renameTo(destination)) {
                    throw IllegalStateException(
                        "$label の更新に失敗し、旧ファイルの自動復元にも失敗しました。退避先: ${backup.name}",
                        failure,
                    )
                }
            }
            throw failure
        }
    }

    fun recoverBackups(parent: File) {
        if (!parent.isDirectory) return
        val groups = parent.listFiles()
            .orEmpty()
            .filter(File::isFile)
            .mapNotNull { backup ->
                val match = BACKUP_NAME.matchEntire(backup.name) ?: return@mapNotNull null
                match.groupValues[1] to backup
            }
            .groupBy({ it.first }, { it.second })

        groups.forEach { (destinationName, backups) ->
            val destination = File(parent, destinationName)
            if (destination.isFile) {
                backups.forEach { backup -> runCatching { backup.delete() } }
                return@forEach
            }
            if (destination.exists()) return@forEach

            val ordered = backups.sortedByDescending(File::lastModified)
            val candidate = ordered.firstOrNull() ?: return@forEach
            if (candidate.renameTo(destination)) {
                ordered.drop(1).forEach { backup -> runCatching { backup.delete() } }
            }
        }
    }

    private val BACKUP_NAME = Regex("^\\.([^/\\\\]+)\\.backup-[0-9a-fA-F-]{36}$")
}
