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
}
