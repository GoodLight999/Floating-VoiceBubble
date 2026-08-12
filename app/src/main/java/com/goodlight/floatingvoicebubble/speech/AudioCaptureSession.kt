package com.goodlight.floatingvoicebubble.speech

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
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
    context: Context,
    private val outputDir: File,
    private val autoEndpoint: Boolean,
    private val mirrorToRecognizerPipe: Boolean = true,
    private val onPcm16: ((ShortArray, Int) -> Unit)? = null,
    private val onCaptureFailure: (String) -> Unit = {},
    private val onEndpoint: () -> Unit,
) : AutoCloseable {
    val sampleRate = 16_000
    val channelCount = 1
    val encoding = AudioFormat.ENCODING_PCM_16BIT

    private val appContext = context.applicationContext
    private val running = AtomicBoolean(false)
    private val endpointSent = AtomicBoolean(false)
    private val pipe: Array<ParcelFileDescriptor>? = if (mirrorToRecognizerPipe) {
        ParcelFileDescriptor.createPipe()
    } else {
        null
    }
    private val endpointDetector = VoiceEndpointDetector(sampleRate = sampleRate)

    @Volatile
    private var audioRecord: AudioRecord? = null
    private var wavFile: File? = null

    fun detachRecognizerAudioSource(): ParcelFileDescriptor =
        pipe?.get(0) ?: error("Recognizer audio pipe is disabled for this session")

    fun start(sessionId: String) {
        if (appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Microphone permission is not granted")
        }
        check(running.compareAndSet(false, true)) { "Audio capture already running" }
        endpointDetector.reset()
        endpointSent.set(false)
        outputDir.mkdirs()
        val raw = File(outputDir, "$sessionId.pcm")
        val wav = File(outputDir, "$sessionId.wav")
        wavFile = wav

        val queriedMinBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            encoding,
        )
        if (queriedMinBuffer <= 0) {
            running.set(false)
            error("AudioRecord does not support 16 kHz mono PCM16 (code=$queriedMinBuffer)")
        }
        val minBuffer = queriedMinBuffer.coerceAtLeast(sampleRate / 5 * 2)
        val record = try {
            AudioRecord.Builder()
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
        } catch (failure: Throwable) {
            running.set(false)
            throw failure
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            running.set(false)
            error("AudioRecord initialization failed")
        }
        audioRecord = record
        try {
            record.startRecording()
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "AudioRecord did not enter RECORDSTATE_RECORDING"
            }
        } catch (failure: Throwable) {
            audioRecord = null
            running.set(false)
            runCatching { record.release() }
            throw failure
        }

        Thread(
            { captureLoop(record, raw, wav, minBuffer) },
            "VoiceBubble-Audio",
        ).start()
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        runCatching { audioRecord?.stop() }
        runCatching { pipe?.get(1)?.close() }
    }

    fun expectedWavFile(): File? = wavFile

    override fun close() {
        stop()
        pipe?.forEach { descriptor -> runCatching { descriptor.close() } }
    }

    private fun captureLoop(record: AudioRecord, raw: File, wav: File, minBufferBytes: Int) {
        var recognizerStream: OutputStream? = pipe?.get(1)?.let { descriptor ->
            runCatching { ParcelFileDescriptor.AutoCloseOutputStream(descriptor) }.getOrNull()
        }
        val shorts = ShortArray((minBufferBytes / 2).coerceAtLeast(1_024))
        val bytes = ByteArray(shorts.size * 2)
        var failureMessage: String? = null

        try {
            FileOutputStream(raw).buffered().use { rawOut ->
                while (running.get()) {
                    val count = record.read(shorts, 0, shorts.size, AudioRecord.READ_BLOCKING)
                    when {
                        count > 0 -> Unit
                        count == 0 -> continue
                        !running.get() -> break
                        else -> {
                            failureMessage = audioReadFailureMessage(count)
                            break
                        }
                    }

                    shortsToLeBytes(shorts, count, bytes)
                    val byteCount = count * 2
                    rawOut.write(bytes, 0, byteCount)
                    onPcm16?.invoke(shorts, count)
                    recognizerStream?.let { stream ->
                        runCatching { stream.write(bytes, 0, byteCount) }.onFailure {
                            runCatching { stream.close() }
                            recognizerStream = null
                        }
                    }
                    if (
                        autoEndpoint &&
                        endpointDetector.accept(shorts, count) &&
                        endpointSent.compareAndSet(false, true)
                    ) {
                        onEndpoint()
                    }
                }
            }
        } catch (failure: Throwable) {
            if (running.get()) {
                failureMessage = failure.message?.takeIf(String::isNotBlank)
                    ?: "Audio capture failed: ${failure.javaClass.simpleName}"
            }
        } finally {
            running.set(false)
            runCatching { recognizerStream?.close() }
            runCatching { record.stop() }
            runCatching { record.release() }
            audioRecord = null
            // WAV is diagnostic/final-ASR support data. Failure to persist it must not discard
            // a transcript that the live recognizer may still be able to finalize.
            runCatching { wrapPcmAsWav(raw, wav) }
            raw.delete()
            failureMessage?.let(onCaptureFailure)
        }
    }

    private fun audioReadFailureMessage(code: Int): String = when (code) {
        AudioRecord.ERROR_DEAD_OBJECT -> "マイクデバイスが切断されました。再度音声入力を開始してください。"
        AudioRecord.ERROR_INVALID_OPERATION -> "マイクの録音状態が無効になりました。"
        AudioRecord.ERROR_BAD_VALUE -> "マイクから不正な音声データが返されました。"
        AudioRecord.ERROR -> "マイクの読み取りでエラーが発生しました。"
        else -> "マイクの読み取りでエラーが発生しました (code=$code)"
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
            out.write(leInt(16))
            out.write(leShort(1))
            out.write(leShort(channelCount.toShort()))
            out.write(leInt(sampleRate))
            out.write(leInt(sampleRate * channelCount * 2))
            out.write(leShort((channelCount * 2).toShort()))
            out.write(leShort(16))
            out.write("data".toByteArray(Charsets.US_ASCII))
            out.write(leInt(dataSize.toInt()))
            raw.inputStream().buffered().use { it.copyTo(out) }
        }
    }

    private fun leInt(value: Int): ByteArray = ByteBuffer.allocate(4)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putInt(value)
        .array()

    private fun leShort(value: Short): ByteArray = ByteBuffer.allocate(2)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putShort(value)
        .array()
}
