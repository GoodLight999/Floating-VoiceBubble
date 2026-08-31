package com.goodlight.floatingvoicebubble.model

import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AtomicFileInstallerTest {
    @Test
    fun replacesExistingFileAndRemovesBackup() {
        val root = Files.createTempDirectory("voicebubble-file-replace").toFile()
        try {
            val destination = root.resolve("model.litertlm").apply { writeText("old") }
            val temporary = root.resolve(".model.part").apply { writeText("new") }

            AtomicFileInstaller.replace(temporary, destination, "test model")

            assertEquals("new", destination.readText())
            assertFalse(temporary.exists())
            assertTrue(root.listFiles().orEmpty().none { it.name.contains(".backup-") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun recoversBackupAfterInterruptedReplacement() {
        val root = Files.createTempDirectory("voicebubble-file-recover").toFile()
        try {
            val backup = root.resolve(".model.litertlm.backup-${UUID.randomUUID()}").apply {
                writeText("last-known-good")
            }

            AtomicFileInstaller.recoverBackups(root)

            val destination = root.resolve("model.litertlm")
            assertTrue(destination.isFile)
            assertEquals("last-known-good", destination.readText())
            assertFalse(backup.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun healthyDestinationWinsOverStaleBackup() {
        val root = Files.createTempDirectory("voicebubble-file-clean").toFile()
        try {
            val destination = root.resolve("model.litertlm").apply { writeText("current") }
            val backup = root.resolve(".model.litertlm.backup-${UUID.randomUUID()}").apply { writeText("old") }

            AtomicFileInstaller.recoverBackups(root)

            assertEquals("current", destination.readText())
            assertFalse(backup.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun unexpectedDestinationDirectoryIsNeverDestroyed() {
        val root = Files.createTempDirectory("voicebubble-file-nondestructive").toFile()
        try {
            val destination = root.resolve("model.litertlm").apply { mkdirs() }
            val backup = root.resolve(".model.litertlm.backup-${UUID.randomUUID()}").apply { writeText("old") }

            AtomicFileInstaller.recoverBackups(root)

            assertTrue(destination.isDirectory)
            assertTrue(backup.isFile)
        } finally {
            root.deleteRecursively()
        }
    }
}
