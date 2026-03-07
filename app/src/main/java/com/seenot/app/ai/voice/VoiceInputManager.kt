package com.seenot.app.ai.voice

import android.content.Context
import com.seenot.app.utils.Logger
import com.seenot.app.ai.parser.IntentParser
import com.seenot.app.ai.parser.ParsedConstraint
import com.seenot.app.ai.parser.ParsedIntentResult
import com.seenot.app.ai.stt.SttEngine
import com.seenot.app.config.ApiConfig
import com.seenot.app.data.model.ConstraintType
import com.seenot.app.data.model.InterventionLevel
import com.seenot.app.data.model.TimeScope
import com.seenot.app.domain.SessionConstraint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Voice Input Manager - handles complete voice input flow
 * Using real-time streaming speech recognition
 */
class VoiceInputManager(private val context: Context) {

    companion object {
        private const val TAG = "VoiceInputManager"
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var sttEngine: SttEngine? = null
    private var intentParser: IntentParser? = null

    // Current app info
    private var currentPackageName: String = ""
    private var currentAppName: String = ""

    /**
     * Set the current app context before starting voice input
     */
    fun setCurrentApp(packageName: String, appName: String) {
        currentPackageName = packageName
        currentAppName = appName
        Logger.d(TAG, "Set current app: $appName ($packageName)")
    }

    /**
     * Clear current app info
     */
    fun clearCurrentApp() {
        currentPackageName = ""
        currentAppName = ""
    }

    // Recording state
    private var isRecording = false
    private var recordingStartTime = 0L

    // State flows for UI observation
    private val _recordingState = MutableStateFlow(VoiceRecordingState.IDLE)
    val recordingState: StateFlow<VoiceRecordingState> = _recordingState.asStateFlow()

    private val _recognizedText = MutableStateFlow<String?>(null)
    val recognizedText: StateFlow<String?> = _recognizedText.asStateFlow()

    private val _parsedIntent = MutableStateFlow<ParsedIntentState?>(null)
    val parsedIntent: StateFlow<ParsedIntentState?> = _parsedIntent.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        // Lazy initialization of engines
    }

    private fun ensureEngines() {
        if (sttEngine == null) {
            sttEngine = SttEngine(context)
        }
        if (intentParser == null) {
            intentParser = IntentParser()
        }
    }

    /**
     * Start voice recording using real-time streaming STT
     */
    fun startRecording(): Boolean {
        if (isRecording) {
            Logger.w(TAG, "Already recording")
            return false
        }

        ensureEngines()

        // Set up callback for real-time transcription results
        sttEngine?.setCallback(object : SttEngine.TranscriptionCallback {
            override fun onIntermediateResult(text: String) {
                Logger.d(TAG, "Intermediate: $text")
                // Update state on main thread for Compose to recompose
                scope.launch {
                    _recognizedText.value = text
                }
            }

            override fun onFinalResult(text: String) {
                Logger.d(TAG, "Final result callback: $text")
                scope.launch {
                    _recognizedText.value = text
                }
            }

            override fun onError(error: String) {
                Logger.e(TAG, "STT Error callback: $error")
                scope.launch {
                    _error.value = error
                }
            }

            override fun onComplete() {
                Logger.d(TAG, "STT Complete callback")
            }
        })

        // Start real-time streaming recognition
        val started = sttEngine?.startRecording() ?: false

        if (started) {
            isRecording = true
            recordingStartTime = System.currentTimeMillis()
            _recordingState.value = VoiceRecordingState.RECORDING
            _recognizedText.value = null
            _parsedIntent.value = null
            _error.value = null

            Logger.d(TAG, "Recording started with real-time STT")
        } else {
            _error.value = "启动录音失败"
            _recordingState.value = VoiceRecordingState.ERROR
        }

        return started
    }

    /**
     * Stop recording - use the last recognized text immediately without waiting for final result
     */
    fun stopRecording() {
        if (!isRecording) {
            Logger.w(TAG, "Not recording")
            return
        }

        isRecording = false

        // Stop recording immediately
        sttEngine?.stopRecording()

        // Use the current recognized text immediately (no waiting for final callback)
        val finalText = _recognizedText.value

        if (!finalText.isNullOrBlank()) {
            _recordingState.value = VoiceRecordingState.PROCESSING
            Logger.d(TAG, "Stop called, using current recognized text: $finalText")
            // Parse immediately without waiting for final callback
            parseIntent(finalText, currentPackageName, currentAppName)
        } else {
            _error.value = "未能识别语音"
            _recordingState.value = VoiceRecordingState.ERROR
        }
    }

    /**
     * Cancel recording without processing
     */
    fun cancelRecording() {
        isRecording = false

        sttEngine?.cancelRecording()

        _recordingState.value = VoiceRecordingState.IDLE
    }

    /**
     * Parse the recognized text into structured constraints
     */
    private fun parseIntent(text: String, packageName: String, appName: String) {
        scope.launch {
            try {
                val result = intentParser?.parseIntent(text, packageName, appName)

                when (result) {
                    is ParsedIntentResult.Success -> {
                        _parsedIntent.value = ParsedIntentState(
                            text = text,
                            constraints = result.constraints.map { it.toSessionConstraint() }
                        )
                        _recordingState.value = VoiceRecordingState.PARSED
                    }
                    is ParsedIntentResult.Error -> {
                        _error.value = result.message
                        _recordingState.value = VoiceRecordingState.ERROR
                    }
                    null -> {
                        _error.value = "解析器未初始化"
                        _recordingState.value = VoiceRecordingState.ERROR
                    }
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Intent parsing failed", e)
                _error.value = "解析失败: ${e.message}"
                _recordingState.value = VoiceRecordingState.ERROR
            }
        }
    }

    /**
     * Parse text input directly (for manual input mode)
     */
    fun parseTextInput(text: String, packageName: String, appName: String) {
        if (text.isBlank()) {
            _error.value = "请输入意图描述"
            return
        }

        _recordingState.value = VoiceRecordingState.PROCESSING
        parseIntent(text, packageName, appName)
    }

    private fun ParsedConstraint.toSessionConstraint(): SessionConstraint {
        return SessionConstraint(
            id = id,
            type = type,
            description = description,
            timeLimitMs = timeLimit?.let { it.durationMinutes * 60 * 1000L },
            timeScope = timeLimit?.scope ?: TimeScope.SESSION,
            interventionLevel = intervention,
            isActive = true
        )
    }

    /**
     * Release resources
     */
    fun release() {
        isRecording = false
        scope.cancel()
        sttEngine?.release()
        sttEngine = null
        intentParser = null
    }
}

/**
 * Recording state
 */
enum class VoiceRecordingState {
    IDLE,
    RECORDING,
    TRANSCRIBED,
    PROCESSING,
    PARSED,
    ERROR
}

/**
 * Parsed intent state
 */
data class ParsedIntentState(
    val text: String,
    val constraints: List<SessionConstraint>
)
