package com.goodlight.floatingvoicebubble.model

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AtomicDirectoryInstallerTest {
    @Test
    fun installsNewDirectory() {
        val root = Files.createTempDirectory("voicebubble-install").toFile()
        try {
            val temporary = root.resolve(".model.part").apply { mkdirs() }
            temporary.resolve("marker.txt").writeText("new")
            val destination = root.resolve("model")

            AtomicDirectoryInstaller.replace(temporary, destination, "test model")

            assertTrue(destination.isDirectory)
            assertEquals("new", destination.resolve("marker.txt").readText())
            assertFalse(temporary.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun replacesExistingDirectoryOnlyAfterNewCopyExists() {
        val root = Files.createTempDirectory("voicebubble-replace").toFile()
        try {
            val destination = root.resolve("model").apply { mkdirs() }
            destination.resolve("marker.txt").writeText("old")
            val temporary = root.resolve(".model.part").apply { mkdirs() }
            temporary.resolve("marker.txt").writeText("new")

            AtomicDirectoryInstaller.replace(temporary, destination, "test model")

            assertEquals("new", destination.resolve("marker.txt").readText())
            assertTrue(root.listFiles().orEmpty().none { it.name.contains(".backup-") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTemporaryDirectoryOnDifferentParent() {
        val rootA = Files.createTempDirectory("voicebubble-a").toFile()
        val rootB = Files.createTempDirectory("voicebubble-b").toFile()
        try {
            val temporary = rootA.resolve(".model.part").apply { mkdirs() }
            val destination = rootB.resolve("model")
            AtomicDirectoryInstaller.replace(temporary, destination, "test model")
        } finally {
            rootA.deleteRecursively()
            rootB.deleteRecursively()
        }
    }
}
