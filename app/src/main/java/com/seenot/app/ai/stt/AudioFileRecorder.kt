package com.seenot.app.ai.stt

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import com.seenot.app.utils.Logger
import java.io.File
import java.io.RandomAccessFile

class AudioFileRecorder(private val context: Context) {
    companion object {
        private const val TAG = "AudioFileRecorder"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val CHANNEL_COUNT = 1
        private const val BITS_PER_SAMPLE = 16
    }

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private var outputFile: File? = null
    @Volatile private var recording = false

    fun startRecording(): Boolean {
        if (recording) {
            Logger.w(TAG, "Recorder already active")
            return false
        }

        return try {
            val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            val bufferSize = maxOf(minBuffer, 4096)
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
                )
            } else {
                @Suppress("DEPRECATION")
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
                )
            }

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                Logger.e(TAG, "AudioRecord initialization failed")
                recorder.release()
                return false
            }

            val file = File(context.cacheDir, "voice_input_${System.currentTimeMillis()}.wav")
            RandomAccessFile(file, "rw").use {
                it.setLength(0)
                repeat(44) { _ -> it.write(0) }
            }

            audioRecord = recorder
            outputFile = file
            recording = true

            recorder.startRecording()
            recordingThread = Thread {
                writePcmToWav(file, recorder, bufferSize)
            }.apply { start() }

            true
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to start wav recording", e)
            cleanup(deleteFile = true)
            false
        }
    }

    fun stopRecording(): File? {
        if (!recording) return outputFile

        recording = false
        val recorder = audioRecord
        runCatching { recorder?.stop() }
        recordingThread?.join(2000)
        recordingThread = null
        runCatching { recorder?.release() }
        audioRecord = null

        val file = outputFile
        outputFile = null
        return file
    }

    fun cancelRecording() {
        recording = false
        runCatching { audioRecord?.stop() }
        recordingThread?.join(1000)
        cleanup(deleteFile = true)
    }

    fun release() {
        cancelRecording()
    }

    private fun writePcmToWav(file: File, recorder: AudioRecord, bufferSize: Int) {
        var totalAudioLen = 0L
        val buffer = ByteArray(bufferSize)

        try {
            RandomAccessFile(file, "rw").use { wavFile ->
                wavFile.seek(44)
                while (recording) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        wavFile.write(buffer, 0, read)
                        totalAudioLen += read
                    } else if (read < 0) {
                        Logger.e(TAG, "AudioRecord read failed: $read")
                        break
                    }
                }
                writeWavHeader(wavFile, totalAudioLen)
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to write wav file", e)
        }
    }

    private fun writeWavHeader(file: RandomAccessFile, totalAudioLen: Long) {
        val totalDataLen = totalAudioLen + 36
        val byteRate = SAMPLE_RATE * CHANNEL_COUNT * BITS_PER_SAMPLE / 8

        file.seek(0)
        file.writeBytes("RIFF")
        file.writeIntLE(totalDataLen.toInt())
        file.writeBytes("WAVE")
        file.writeBytes("fmt ")
        file.writeIntLE(16)
        file.writeShortLE(1)
        file.writeShortLE(CHANNEL_COUNT.toShort())
        file.writeIntLE(SAMPLE_RATE)
        file.writeIntLE(byteRate)
        file.writeShortLE((CHANNEL_COUNT * BITS_PER_SAMPLE / 8).toShort())
        file.writeShortLE(BITS_PER_SAMPLE.toShort())
        file.writeBytes("data")
        file.writeIntLE(totalAudioLen.toInt())
    }

    private fun cleanup(deleteFile: Boolean) {
        runCatching { audioRecord?.release() }
        audioRecord = null
        recordingThread = null
        recording = false

        if (deleteFile) {
            outputFile?.delete()
        }
        outputFile = null
    }
}

private fun RandomAccessFile.writeIntLE(value: Int) {
    write(byteArrayOf(
        (value and 0xff).toByte(),
        ((value shr 8) and 0xff).toByte(),
        ((value shr 16) and 0xff).toByte(),
        ((value shr 24) and 0xff).toByte()
    ))
}

private fun RandomAccessFile.writeShortLE(value: Short) {
    write(byteArrayOf(
        (value.toInt() and 0xff).toByte(),
        ((value.toInt() shr 8) and 0xff).toByte()
    ))
}
