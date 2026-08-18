package com.goodlight.floatingvoicebubble.model

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files

class ModelArchiveExtractorTest {
    @Test
    fun extractsOnlyRequiredBasenamesWithoutMaterializingArchivePaths() {
        val root = Files.createTempDirectory("voicebubble-archive").toFile()
        try {
            val bytes = archive(
                listOf(
                    "nested/../model/encoder.int8.onnx" to "encoder".toByteArray(),
                    "model/decoder.int8.onnx" to "decoder".toByteArray(),
                    "model/joiner.int8.onnx" to "joiner".toByteArray(),
                    "model/tokens.txt" to "tokens".toByteArray(),
                    "../../should-not-exist.txt" to "ignored".toByteArray(),
                )
            )
            ModelArchiveExtractor.extractTarBz2(
                ByteArrayInputStream(bytes),
                root,
                setOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt"),
            )
            assertEquals("encoder", root.resolve("encoder.int8.onnx").readText())
            assertEquals("decoder", root.resolve("decoder.int8.onnx").readText())
            assertTrue(root.resolve("tokens.txt").isFile)
            assertFalse(requireNotNull(root.parentFile).resolve("should-not-exist.txt").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsArchiveMissingRequiredFile() {
        val root = Files.createTempDirectory("voicebubble-missing").toFile()
        try {
            val bytes = archive(listOf("model/tokens.txt" to "tokens".toByteArray()))
            ModelArchiveExtractor.extractTarBz2(
                ByteArrayInputStream(bytes),
                root,
                setOf("encoder.int8.onnx", "tokens.txt"),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsDuplicateRequiredBasename() {
        val root = Files.createTempDirectory("voicebubble-duplicate").toFile()
        try {
            val bytes = archive(
                listOf(
                    "a/tokens.txt" to "one".toByteArray(),
                    "b/tokens.txt" to "two".toByteArray(),
                )
            )
            ModelArchiveExtractor.extractTarBz2(ByteArrayInputStream(bytes), root, setOf("tokens.txt"))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun archive(entries: List<Pair<String, ByteArray>>): ByteArray {
        val output = ByteArrayOutputStream()
        BZip2CompressorOutputStream(output).use { bzip ->
            TarArchiveOutputStream(bzip).use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                entries.forEach { (name, bytes) ->
                    val entry = TarArchiveEntry(name).apply { size = bytes.size.toLong() }
                    tar.putArchiveEntry(entry)
                    tar.write(bytes)
                    tar.closeArchiveEntry()
                }
                tar.finish()
            }
        }
        return output.toByteArray()
    }
}
