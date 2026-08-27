package com.goodlight.floatingvoicebubble

import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.goodlight.floatingvoicebubble.model.GemmaModelSource
import com.goodlight.floatingvoicebubble.model.GemmaModelStorage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream

@RunWith(AndroidJUnit4::class)
class GemmaExternalModelReferenceTest {
    @Test
    fun directSharedModelIsOpenedByRealPathWithoutDuplicate() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = GemmaModelStorage.installDirectory(context)
        val file = File(dir, "fvb-direct-${System.nanoTime()}.litertlm")
        val bytes = ByteArray(1 * 1024 * 1024 + 257) { index -> (index * 31 + 7).toByte() }
        val privateDir = GemmaModelStorage.legacyPrivateDirectory(context)
        val beforePrivate = privateDir.listFiles().orEmpty().map(File::name).sorted()

        try {
            file.outputStream().use { it.write(bytes) }
            val selection = GemmaModelSource.verifyDirectFile(file)
            assertEquals(file.absolutePath, selection.reference)
            assertEquals(file.name, selection.displayName)
            assertTrue(GemmaModelSource.isAvailable(context, selection.reference))

            GemmaModelSource.openForEngine(context, selection.reference).use { opened ->
                assertEquals(file.absolutePath, opened.enginePath)
                val reread = FileInputStream(opened.enginePath).use { input -> input.readBytes() }
                assertArrayEquals(bytes, reread)
            }

            if (GemmaModelStorage.isInSharedDirectory(context, file)) {
                val afterPrivate = privateDir.listFiles().orEmpty().map(File::name).sorted()
                assertEquals("direct shared model must not create a private duplicate", beforePrivate, afterPrivate)
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun contentUriIsNotPretendedToBeDirectlyRunnableAndPickerImportCreatesRealFile() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val token = "fvb-import-${System.nanoTime()}"
        val displayName = "$token.litertlm"
        val bytes = ByteArray(1 * 1024 * 1024 + 257) { index -> (index * 17 + 11).toByte() }
        val uri = resolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/FloatingVoiceBubbleTests")
                put(MediaStore.Downloads.IS_PENDING, 1)
            },
        ) ?: error("Could not create test document")

        var importedFile: File? = null
        try {
            resolver.openOutputStream(uri, "w")!!.use { output -> output.write(bytes) }
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                null,
                null,
            )

            assertFalse("content URI must never be reported as LiteRT-LM runnable", GemmaModelSource.isAvailable(context, uri.toString()))
            try {
                GemmaModelSource.openForEngine(context, uri.toString())
                fail("legacy content URI must require migration/import")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message.orEmpty().contains("通常のファイルパス"))
            }

            val selection = GemmaModelSource.verifyExternal(context, uri)
            importedFile = File(selection.reference)
            assertNotEquals(uri.toString(), selection.reference)
            assertTrue(importedFile.isFile)
            assertTrue(GemmaModelSource.isAvailable(context, selection.reference))

            GemmaModelSource.openForEngine(context, selection.reference).use { opened ->
                assertEquals(importedFile.absolutePath, opened.enginePath)
                val reread = FileInputStream(opened.enginePath).use { input -> input.readBytes() }
                assertArrayEquals(bytes, reread)
            }
        } finally {
            importedFile?.delete()
            resolver.delete(uri, null, null)
        }
    }
}
