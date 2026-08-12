package com.goodlight.floatingvoicebubble.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Pcm16WavReaderTest {
    @Test
    fun readsMonoPcm16Wav() {
        val file = File.createTempFile("voicebubble-wav", ".wav")
        try {
            val samples = shortArrayOf(0, 16384, -32768)
            file.outputStream().use { out ->
                out.write("RIFF".toByteArray(Charsets.US_ASCII))
                out.write(leInt(36 + samples.size * 2))
                out.write("WAVEfmt ".toByteArray(Charsets.US_ASCII))
                out.write(leInt(16))
                out.write(leShort(1))
                out.write(leShort(1))
                out.write(leInt(16_000))
                out.write(leInt(32_000))
                out.write(leShort(2))
                out.write(leShort(16))
                out.write("data".toByteArray(Charsets.US_ASCII))
                out.write(leInt(samples.size * 2))
                samples.forEach { out.write(leShort(it)) }
            }

            val audio = Pcm16WavReader.read(file)
            assertEquals(16_000, audio.sampleRate)
            assertEquals(3, audio.samples.size)
            assertEquals(0.0, audio.samples[0].toDouble(), 0.00001)
            assertEquals(0.5, audio.samples[1].toDouble(), 0.00001)
            assertEquals(-1.0, audio.samples[2].toDouble(), 0.00001)
            assertTrue(audio.durationMs >= 0L)
        } finally {
            file.delete()
        }
    }

    private fun leInt(value: Int): ByteArray = ByteBuffer.allocate(4)
        .order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    private fun leShort(value: Short): ByteArray = ByteBuffer.allocate(2)
        .order(ByteOrder.LITTLE_ENDIAN).putShort(value).array()

    private fun leShort(value: Int): ByteArray = leShort(value.toShort())
}
