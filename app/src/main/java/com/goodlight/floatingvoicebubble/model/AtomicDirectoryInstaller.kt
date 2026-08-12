package com.goodlight.floatingvoicebubble.model

import java.io.File
import java.util.UUID

/**
 * Replaces a validated model directory without deleting the last known-good copy first.
 * The temporary and destination directories must live on the same filesystem/root.
 */
internal object AtomicDirectoryInstaller {
    fun replace(temporary: File, destination: File, label: String) {
        require(temporary.isDirectory) { "$label temporary directory is missing" }
        require(temporary.parentFile?.canonicalFile == destination.parentFile?.canonicalFile) {
            "$label temporary and destination directories must share a parent"
        }

        val backup = File(
            destination.parentFile,
            ".${destination.name}.backup-${UUID.randomUUID()}",
        )
        var oldMovedAside = false

        try {
            if (destination.exists()) {
                check(destination.isDirectory) { "$label destination is not a directory" }
                check(destination.renameTo(backup)) { "旧${label}を安全に退避できませんでした。" }
                oldMovedAside = true
            }

            check(temporary.renameTo(destination)) { "${label}を確定保存できませんでした。" }

            if (oldMovedAside && backup.exists()) {
                // A stale backup is harmless compared with deleting the newly verified model.
                runCatching { backup.deleteRecursively() }
            }
        } catch (failure: Throwable) {
            if (!destination.exists() && oldMovedAside && backup.exists()) {
                val restored = backup.renameTo(destination)
                if (!restored) {
                    throw IllegalStateException(
                        "$label の更新に失敗し、旧モデルの自動復元にも失敗しました。退避先: ${backup.name}",
                        failure,
                    )
                }
            }
            throw failure
        }
    }

    /**
     * Repairs the only crash window in [replace]: process death after the old directory was
     * renamed to a backup but before the validated temporary directory became the destination.
     */
    fun recoverBackups(parent: File) {
        if (!parent.isDirectory) return
        val groups = parent.listFiles()
            .orEmpty()
            .filter(File::isDirectory)
            .mapNotNull { backup ->
                val match = BACKUP_NAME.matchEntire(backup.name) ?: return@mapNotNull null
                match.groupValues[1] to backup
            }
            .groupBy({ it.first }, { it.second })

        groups.forEach { (destinationName, backups) ->
            val destination = File(parent, destinationName)
            if (destination.isDirectory) {
                backups.forEach { backup -> runCatching { backup.deleteRecursively() } }
                return@forEach
            }
            if (destination.exists()) {
                // Unexpected non-directory data must not be destroyed automatically.
                return@forEach
            }

            val ordered = backups.sortedByDescending(File::lastModified)
            val candidate = ordered.firstOrNull() ?: return@forEach
            if (candidate.renameTo(destination)) {
                ordered.drop(1).forEach { backup -> runCatching { backup.deleteRecursively() } }
            }
        }
    }

    private val BACKUP_NAME = Regex("^\\.([^/\\\\]+)\\.backup-[0-9a-fA-F-]{36}$")
}
