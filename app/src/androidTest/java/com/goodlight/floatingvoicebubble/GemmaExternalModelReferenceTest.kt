package com.goodlight.floatingvoicebubble

import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.goodlight.floatingvoicebubble.model.GemmaModelSource
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream

@RunWith(AndroidJUnit4::class)
class GemmaExternalModelReferenceTest {
    @Test
    fun seekableContentUriIsVerifiedAndOpenedWithoutPrivateCopy() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val token = "fvb-external-${System.nanoTime()}"
        val displayName = "$token.litertlm"
        val bytes = ByteArray(1 * 1024 * 1024 + 257) { index -> (index * 31 + 7).toByte() }
        val privateModelDir = File(context.noBackupFilesDir, "models/correction")
        val before = privateModelDir.listFiles().orEmpty().map { it.name }.sorted()

        val uri = resolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/FloatingVoiceBubbleTests")
                put(MediaStore.Downloads.IS_PENDING, 1)
            },
        ) ?: error("Could not create test document")

        try {
            resolver.openOutputStream(uri, "w")!!.use { output -> output.write(bytes) }
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                null,
                null,
            )

            val selection = GemmaModelSource.verifyExternal(context, uri)
            assertEquals(uri.toString(), selection.reference)
            assertEquals(displayName, selection.displayName)
            assertTrue(GemmaModelSource.isAvailable(context, selection.reference))

            GemmaModelSource.openForEngine(context, selection.reference).use { opened ->
                assertTrue(opened.enginePath.startsWith("/proc/self/fd/"))
                val reread = FileInputStream(opened.enginePath).use { input -> input.readBytes() }
                assertArrayEquals(bytes, reread)
            }

            val after = privateModelDir.listFiles().orEmpty().map { it.name }.sorted()
            assertEquals("external model verification must not create a private model copy", before, after)
        } finally {
            resolver.delete(uri, null, null)
        }
    }
}
