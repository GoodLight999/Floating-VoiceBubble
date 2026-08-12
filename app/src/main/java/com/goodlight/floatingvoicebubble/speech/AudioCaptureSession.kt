package com.goodlight.floatingvoicebubble.speech

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

class AudioCaptureSession(
    private val outputDir: File,
    private val autoEndpoint: Boolean,
    private val onEndpoint: () -> Unit,
) : AutoCloseable {
    val sampleRate = 16_000
    val channelCount = 1
    val encoding = AudioFormat.ENCODING_PCM_16BIT

    private val running = AtomicBoolean(false)
    private val endpointSent = AtomicBoolean(false)
    private val pipe = ParcelFileDescriptor.createPipe()
    private val endpointDetector = VoiceEndpointDetector(sampleRate = sampleRate)
    private var thread: Thread? = null
    private var audioRecord: AudioRecord? = null
    private var pcmFile: File? = null
    private var wavFile: File? = null

    fun detachRecognizerAudioSource(): ParcelFileDescriptor = pipe[0]

    fun start(sessionId: String) {
        check(running.compareAndSet(false, true)) { "Audio capture already running" }
        outputDir.mkdirs()
        val raw = File(outputDir, "$sessionId.pcm")
        val wav = File(outputDir, "$sessionId.wav")
        pcmFile = raw
        wavFile = wav

        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, encoding)
            .coerceAtLeast(sampleRate / 5 * 2)
        val record = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(encoding)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBuffer * 2)
            .build()
        check(record.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord initialization failed" }
        audioRecord = record
        record.startRecording()
        thread = Thread({ captureLoop(record, raw, wav, minBuffer) }, "VoiceBubble-Audio").also { it.start() }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        runCatching { audioRecord?.stop() }
        runCatching { pipe[1].close() }
    }

    fun expectedWavFile(): File? = wavFile

    override fun close() {
        stop()
        runCatching { pipe[0].close() }
        runCatching { pipe[1].close() }
    }

    private fun captureLoop(record: AudioRecord, raw: File, wav: File, minBufferBytes: Int) {
        var recognizerStream: OutputStream? = runCatching { ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]) }.getOrNull()
        val shorts = ShortArray((minBufferBytes / 2).coerceAtLeast(1_024))
        val bytes = ByteArray(shorts.size * 2)
        try {
            FileOutputStream(raw).buffered().use { rawOut ->
                while (running.get()) {
                    val count = record.read(shorts, 0, shorts.size, AudioRecord.READ_BLOCKING)
                    if (count <= 0) continue
                    shortsToLeBytes(shorts, count, bytes)
                    val byteCount = count * 2
                    rawOut.write(bytes, 0, byteCount)
                    recognizerStream?.let { stream ->
                        runCatching { stream.write(bytes, 0, byteCount) }.onFailure {
                            runCatching { stream.close() }
                            recognizerStream = null
                        }
                    }
                    if (autoEndpoint && endpointDetector.accept(shorts, count) && endpointSent.compareAndSet(false, true)) {
                        onEndpoint()
                    }
                }
            }
        } finally {
            running.set(false)
            runCatching { recognizerStream?.close() }
            runCatching { record.stop() }
            record.release()
            audioRecord = null
            runCatching { wrapPcmAsWav(raw, wav) }
            raw.delete()
        }
    }

    private fun shortsToLeBytes(input: ShortArray, count: Int, output: ByteArray) {
        var j = 0
        for (i in 0 until count) {
            val value = input[i].toInt()
            output[j++] = (value and 0xff).toByte()
            output[j++] = ((value ushr 8) and 0xff).toByte()
        }
    }

    private fun wrapPcmAsWav(raw: File, destination: File) {
        val dataSize = raw.length()
        FileOutputStream(destination).buffered().use { out ->
            out.write("RIFF".toByteArray(Charsets.US_ASCII))
            out.write(leInt((36L + dataSize).toInt()))
            out.write("WAVEfmt ".toByteArray(Charsets.US_ASCII))
            out.write(leInt(16)); out.write(leShort(1)); out.write(leShort(channelCount.toShort()))
            out.write(leInt(sampleRate)); out.write(leInt(sampleRate * channelCount * 2))
            out.write(leShort((channelCount * 2).toShort())); out.write(leShort(16))
            out.write("data".toByteArray(Charsets.US_ASCII)); out.write(leInt(dataSize.toInt()))
            raw.inputStream().buffered().use { it.copyTo(out) }
        }
    }

    private fun leInt(value: Int): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    private fun leShort(value: Short): ByteArray = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value).array()
}
