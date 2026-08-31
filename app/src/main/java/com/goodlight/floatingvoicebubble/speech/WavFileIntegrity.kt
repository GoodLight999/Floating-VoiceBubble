package com.goodlight.floatingvoicebubble.speech

import java.io.File

/** Minimal structural validation before a captured WAV is exposed to final ASR. */
internal object WavFileIntegrity {
    private const val HEADER_BYTES = 44

    fun isCompletePcm16Mono(file: File, expectedDataBytes: Long, expectedSampleRate: Int = 16_000): Boolean {
        if (expectedDataBytes <= 0 || expectedDataBytes > Int.MAX_VALUE) return false
        if (!file.isFile || file.length() != HEADER_BYTES + expectedDataBytes) return false
        val header = ByteArray(HEADER_BYTES)
        file.inputStream().use { input ->
            var offset = 0
            while (offset < header.size) {
                val read = input.read(header, offset, header.size - offset)
                if (read <= 0) return false
                offset += read
            }
        }
        if (ascii(header, 0, 4) != "RIFF") return false
        if (ascii(header, 8, 4) != "WAVE") return false
        if (ascii(header, 12, 4) != "fmt ") return false
        if (ascii(header, 36, 4) != "data") return false
        if (leInt(header, 16) != 16) return false
        if (leShort(header, 20) != 1) return false // PCM
        if (leShort(header, 22) != 1) return false // mono
        if (leInt(header, 24) != expectedSampleRate) return false
        if (leShort(header, 34) != 16) return false
        if (leInt(header, 4).toLong() + 8L != file.length()) return false
        if (leInt(header, 40).toLong() != expectedDataBytes) return false
        return true
    }

    private fun ascii(bytes: ByteArray, offset: Int, length: Int): String =
        String(bytes, offset, length, Charsets.US_ASCII)

    private fun leInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)

    private fun leShort(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)
}
