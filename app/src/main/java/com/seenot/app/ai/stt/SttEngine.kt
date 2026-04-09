package com.seenot.app.ai.stt

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import com.seenot.app.utils.Logger
import com.seenot.app.BuildConfig
import com.alibaba.dashscope.audio.asr.recognition.RecognitionParam
import com.alibaba.dashscope.utils.Constants
import com.seenot.app.config.ApiConfig
import com.seenot.app.config.AiProvider
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/**
 * Real-time Speech-to-Text engine for DashScope fun-asr-realtime.
 */
class SttEngine(private val context: Context) {

    companion object {
        private const val TAG = "SttEngine"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT_PCM = AudioFormat.ENCODING_PCM_16BIT
        private const val MAX_RECORDING_DURATION_MS = 30000L
        // Fixed buffer size for 20ms of audio: 16000 * 2 * 0.02 = 640 bytes
        private const val AUDIO_BUFFER_SIZE = 640
    }

    private var audioRecord: AudioRecord? = null
    private var recordingActive = false
    private var recordingStartTime: Long = 0

    private var recognitionHelper: DashScopeRecognitionHelper? = null
    private var recognitionExecutor: java.util.concurrent.ExecutorService? = null

    private var transcriptionCallback: TranscriptionCallback? = null

    /**
     * Callback interface for transcription results
     */
    interface TranscriptionCallback {
        fun onIntermediateResult(text: String)
        fun onFinalResult(text: String)
        fun onError(error: String)
        fun onComplete()
    }

    /**
     * Set callback for receiving transcription results
     */
    fun setCallback(callback: TranscriptionCallback?) {
        transcriptionCallback = callback
    }

    /**
     * Start recording audio using AudioRecord for real-time streaming
     */
    fun startRecording(): Boolean {
        if (recordingActive) {
            Logger.w(TAG, "Already recording")
            return false
        }

        try {
            // Use a small fixed buffer size for sending, but AudioRecord needs larger buffer
            val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT_PCM)
            // Use larger buffer for AudioRecord (at least 4x the send buffer)
            val audioRecordBufferSize = maxOf(minBufferSize, 4096)
            Logger.d(TAG, "AudioRecord buffer size: $audioRecordBufferSize, minBufferSize: $minBufferSize")

            audioRecord = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT_PCM,
                    audioRecordBufferSize
                )
            } else {
                @Suppress("DEPRECATION")
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT_PCM,
                    audioRecordBufferSize
                )
            }

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Logger.e(TAG, "AudioRecord initialization failed")
                return false
            }

            val apiKey = ApiConfig.getApiKey(AiProvider.DASHSCOPE)
                .ifBlank { BuildConfig.DASHSCOPE_API_KEY }
            if (apiKey.isBlank()) {
                Logger.e(TAG, "API key is empty")
                return false
            }

            Constants.baseWebsocketApiUrl = when (ApiConfig.getQwenRegion()) {
                com.seenot.app.config.QwenRegion.BEIJING -> "wss://dashscope.aliyuncs.com/api-ws/v1/inference"
                com.seenot.app.config.QwenRegion.SINGAPORE -> "wss://dashscope-intl.aliyuncs.com/api-ws/v1/inference"
                com.seenot.app.config.QwenRegion.VIRGINIA -> "wss://dashscope-us.aliyuncs.com/api-ws/v1/inference"
            }

            // Configure recognition parameters using builder
            val paramBuilder = RecognitionParam.builder()
                .model("fun-asr-realtime")
                .apiKey(apiKey)
                .format("pcm")
                .sampleRate(SAMPLE_RATE)
                .disfluencyRemovalEnabled(true)  // Filter filler words
            val param = paramBuilder.build()

            // Create recognition helper instance
            recognitionHelper = DashScopeRecognitionHelper()

            // Set up callback for recognition results
            recognitionHelper?.setCallback(object : RecognitionCallback {
                override fun onIntermediateResult(text: String) {
                    Logger.d(TAG, "Intermediate Result: $text")
                    if (text.isNotBlank()) {
                        transcriptionCallback?.onIntermediateResult(text)
                    }
                }

                override fun onFinalResult(text: String) {
                    Logger.d(TAG, "Final Result: $text")
                    transcriptionCallback?.onFinalResult(text)
                }

                override fun onError(error: String) {
                    Logger.e(TAG, "RecognitionCallback error: $error")
                    transcriptionCallback?.onError(error)
                }

                override fun onComplete() {
                    Logger.d(TAG, "Recognition complete")
                    transcriptionCallback?.onComplete()
                }
            })

            // Start recognition
            recognitionHelper?.startRecognition(param)

            // Start audio recording
            audioRecord?.startRecording()
            recordingActive = true
            recordingStartTime = System.currentTimeMillis()

            // Start sending audio data in background
            recognitionExecutor = Executors.newSingleThreadExecutor()
            recognitionExecutor?.execute {
                sendAudioData()
            }

            Logger.d(TAG, "Recording and recognition started")
            return true

        } catch (e: Exception) {
            Logger.e(TAG, "Failed to start recording", e)
            cleanup()
            return false
        }
    }

    private fun sendAudioData() {
        // Fixed buffer size for sending (like official Java example)
        val sendBufferSize = 1024
        // ByteBuffer for sending audio data
        val readBuffer = ByteArray(sendBufferSize)

        Logger.d(TAG, "Starting audio capture and streaming")

        try {
            while (recordingActive) {
                val read = audioRecord?.read(readBuffer, 0, sendBufferSize) ?: 0
                if (read > 0) {
                    // Use only the bytes that were read
                    val buffer = ByteBuffer.wrap(readBuffer, 0, read)
                    // Send audio data to streaming recognition service
                    recognitionHelper?.sendAudioFrame(buffer)
                    // Sleep to prevent CPU overuse (recording rate limitation)
                    Thread.sleep(20)
                } else if (read < 0) {
                    Logger.e(TAG, "AudioRecord error: $read")
                    break
                }

                // Check max duration
                if (getRecordingDuration() >= MAX_RECORDING_DURATION_MS) {
                    Logger.d(TAG, "Max recording duration exceeded")
                    break
                }
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Error sending audio data", e)
            transcriptionCallback?.onError(e.message ?: "Audio sending error")
        } finally {
            Logger.d(TAG, "Audio capture loop finished, stopping recognition")
            try {
                recognitionHelper?.stop()
            } catch (e: Exception) {
                Logger.e(TAG, "Error stopping recognition", e)
            }
        }
    }

    /**
     * Stop recording and return the final transcription result
     */
    fun stopRecording() {
        if (!recordingActive) {
            Logger.w(TAG, "Not recording")
            return
        }

        try {
            recordingActive = false

            audioRecord?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Error stopping recorder", e)
        }

        audioRecord = null

        // Stop recognition
        try {
            recognitionHelper?.stop()
        } catch (e: Exception) {
            Logger.e(TAG, "Error stopping recognition", e)
        }

        recognitionHelper = null

        // Shutdown executor
        recognitionExecutor?.shutdown()
        recognitionExecutor = null

        Logger.d(TAG, "Recording stopped")
    }

    /**
     * Cancel recording without getting result
     */
    fun cancelRecording() {
        recordingActive = false

        try {
            audioRecord?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            // Ignore
        }

        audioRecord = null

        try {
            recognitionHelper?.stop()
        } catch (e: Exception) {
            // Ignore
        }

        recognitionHelper = null
        recognitionExecutor?.shutdown()
        recognitionExecutor = null
    }

    /**
     * Check if currently recording
     */
    fun isRecording(): Boolean = recordingActive

    /**
     * Get recording duration in milliseconds
     */
    fun getRecordingDuration(): Long {
        return if (recordingActive) {
            System.currentTimeMillis() - recordingStartTime
        } else {
            0
        }
    }

    /**
     * Check if max duration exceeded
     */
    fun isMaxDurationExceeded(): Boolean {
        return getRecordingDuration() >= MAX_RECORDING_DURATION_MS
    }

    private fun cleanup() {
        try {
            audioRecord?.release()
        } catch (e: Exception) {
            // Ignore
        }
        audioRecord = null
        recordingActive = false

        try {
            recognitionHelper?.stop()
        } catch (e: Exception) {
            // Ignore
        }
        recognitionHelper = null

        recognitionExecutor?.shutdown()
        recognitionExecutor = null
    }

    /**
     * Release resources
     */
    fun release() {
        cleanup()
    }
    /**
     * Stop ongoing transcription
     */
    fun stopTranscription() {
        cancelRecording()
    }
}

/**
 * Result of speech-to-text transcription
 */
sealed class SttResult {
    data class Success(val text: String, val language: String) : SttResult()
    data class Error(val message: String) : SttResult()
}
