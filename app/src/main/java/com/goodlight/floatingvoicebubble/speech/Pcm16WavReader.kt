package com.goodlight.floatingvoicebubble.speech

import java.io.File
import java.io.RandomAccessFile

data class PcmAudio(
    val sampleRate: Int,
    val samples: FloatArray,
) {
    val durationMs: Long
        get() = if (sampleRate <= 0) 0L else samples.size * 1000L / sampleRate
}

object Pcm16WavReader {
    fun read(file: File): PcmAudio {
        RandomAccessFile(file, "r").use { input ->
            require(readAscii(input, 4) == "RIFF") { "Not a RIFF WAV file" }
            readUInt32Le(input)
            require(readAscii(input, 4) == "WAVE") { "Not a WAVE file" }

            var audioFormat = -1
            var channels = -1
            var sampleRate = -1
            var bitsPerSample = -1
            var dataOffset = -1L
            var dataSize = -1L

            while (input.filePointer + 8 <= input.length()) {
                val id = readAscii(input, 4)
                val size = readUInt32Le(input)
                val payloadStart = input.filePointer
                when (id) {
                    "fmt " -> {
                        require(size >= 16L) { "WAV fmt chunk is too small" }
                        audioFormat = readUInt16Le(input)
                        channels = readUInt16Le(input)
                        sampleRate = readUInt32Le(input).toInt()
                        readUInt32Le(input)
                        readUInt16Le(input)
                        bitsPerSample = readUInt16Le(input)
                    }
                    "data" -> {
                        dataOffset = payloadStart
                        dataSize = size
                    }
                }
                val next = payloadStart + size + (size and 1L)
                require(next <= input.length() + 1L) { "WAV chunk exceeds file length" }
                input.seek(next.coerceAtMost(input.length()))
            }

            require(audioFormat == 1) { "Only PCM WAV is supported" }
            require(channels == 1) { "Only mono WAV is supported" }
            require(sampleRate > 0) { "WAV sample rate is missing" }
            require(bitsPerSample == 16) { "Only PCM16 WAV is supported" }
            require(dataOffset >= 0L && dataSize > 0L) { "WAV data chunk is missing" }
            require(dataSize % 2L == 0L) { "PCM16 data has odd byte length" }
            require(dataSize / 2L <= Int.MAX_VALUE) { "WAV is too large" }

            input.seek(dataOffset)
            val count = (dataSize / 2L).toInt()
            val samples = FloatArray(count)
            for (index in 0 until count) {
                val low = input.readUnsignedByte()
                val high = input.readUnsignedByte()
                val value = ((high shl 8) or low).toShort()
                samples[index] = value / 32768.0f
            }
            return PcmAudio(sampleRate, samples)
        }
    }

    private fun readAscii(input: RandomAccessFile, length: Int): String {
        val bytes = ByteArray(length)
        input.readFully(bytes)
        return bytes.toString(Charsets.US_ASCII)
    }

    private fun readUInt16Le(input: RandomAccessFile): Int {
        val low = input.readUnsignedByte()
        val high = input.readUnsignedByte()
        return low or (high shl 8)
    }

    private fun readUInt32Le(input: RandomAccessFile): Long {
        val b0 = input.readUnsignedByte().toLong()
        val b1 = input.readUnsignedByte().toLong()
        val b2 = input.readUnsignedByte().toLong()
        val b3 = input.readUnsignedByte().toLong()
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }
}
