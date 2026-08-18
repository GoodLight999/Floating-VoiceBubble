package com.goodlight.floatingvoicebubble.speech

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

class WavFileIntegrityTest {
    @Test
    fun acceptsStructurallyCompletePcm16MonoWav() {
        val file = tempWav(payloadBytes = 320)
        try {
            assertTrue(WavFileIntegrity.isCompletePcm16Mono(file, 320, 16_000))
        } finally {
            file.delete()
        }
    }

    @Test
    fun rejectsPartialFileEvenWhenItIsLargerThanHeader() {
        val file = tempWav(payloadBytes = 320)
        try {
            file.setLength(file.length() - 9)
            assertFalse(WavFileIntegrity.isCompletePcm16Mono(file, 320, 16_000))
        } finally {
            file.delete()
        }
    }

    @Test
    fun rejectsHeaderWhoseDeclaredDataLengthDoesNotMatchPayload() {
        val file = tempWav(payloadBytes = 320, declaredPayloadBytes = 640)
        try {
            assertFalse(WavFileIntegrity.isCompletePcm16Mono(file, 320, 16_000))
        } finally {
            file.delete()
        }
    }

    private fun tempWav(payloadBytes: Int, declaredPayloadBytes: Int = payloadBytes): File {
        val file = Files.createTempFile("voicebubble-wav-integrity", ".wav").toFile()
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + declaredPayloadBytes)
            put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1)
            putShort(1)
            putInt(16_000)
            putInt(32_000)
            putShort(2)
            putShort(16)
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(declaredPayloadBytes)
        }.array()
        file.outputStream().use { out ->
            out.write(header)
            out.write(ByteArray(payloadBytes) { (it and 0x7f).toByte() })
        }
        return file
    }
}
