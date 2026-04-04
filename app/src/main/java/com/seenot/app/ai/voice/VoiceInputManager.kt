package com.seenot.app.ai.voice

import android.content.Context
import com.seenot.app.utils.Logger
import com.seenot.app.ai.parser.IntentParser
import com.seenot.app.ai.parser.ParsedConstraint
import com.seenot.app.ai.parser.ParsedIntentResult
import com.seenot.app.ai.stt.AudioFileRecorder
import com.seenot.app.ai.stt.SttEngine
import com.seenot.app.ai.stt.SttResult
import com.seenot.app.ai.stt.UnifiedTranscriber
import com.seenot.app.config.AiProvider
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
    private var audioFileRecorder: AudioFileRecorder? = null
    private var intentParser: IntentParser? = null
    private var unifiedTranscriber: UnifiedTranscriber? = null

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
    private var recordingUsesRealtimeDashScope = false

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
        if (audioFileRecorder == null) {
            audioFileRecorder = AudioFileRecorder(context)
        }
        if (unifiedTranscriber == null) {
            unifiedTranscriber = UnifiedTranscriber()
        }
        if (intentParser == null) {
            intentParser = IntentParser()
        }
    }

    private fun shouldUseRealtimeDashScope(): Boolean {
        return ApiConfig.getSttProvider() == AiProvider.DASHSCOPE
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

        recordingUsesRealtimeDashScope = shouldUseRealtimeDashScope()
        val started = if (recordingUsesRealtimeDashScope) {
            sttEngine?.setCallback(object : SttEngine.TranscriptionCallback {
                override fun onIntermediateResult(text: String) {
                    Logger.d(TAG, "Intermediate: $text")
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
            sttEngine?.startRecording() ?: false
        } else {
            audioFileRecorder?.startRecording() ?: false
        }

        if (started) {
            isRecording = true
            recordingStartTime = System.currentTimeMillis()
            _recordingState.value = VoiceRecordingState.RECORDING
            _recognizedText.value = null
            _parsedIntent.value = null
            _error.value = null

            Logger.d(TAG, "Recording started")
        } else {
            _error.value = "启动录音失败"
            _recordingState.value = VoiceRecordingState.ERROR
            recordingUsesRealtimeDashScope = false
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

        if (recordingUsesRealtimeDashScope) {
            sttEngine?.stopRecording()

            val finalText = _recognizedText.value
            if (!finalText.isNullOrBlank()) {
                _recordingState.value = VoiceRecordingState.PROCESSING
                Logger.d(TAG, "Stop called, using current recognized text: $finalText")
                parseIntent(finalText, currentPackageName, currentAppName)
            } else {
                _error.value = "未能识别语音"
                _recordingState.value = VoiceRecordingState.ERROR
            }
        } else {
            val audioFile = audioFileRecorder?.stopRecording()
            if (audioFile == null) {
                _error.value = "录音失败"
                _recordingState.value = VoiceRecordingState.ERROR
                return
            }

            _recordingState.value = VoiceRecordingState.PROCESSING
            scope.launch {
                val result = try {
                    unifiedTranscriber?.transcribe(audioFile) ?: SttResult.Error("转写器未初始化")
                } finally {
                    audioFile.delete()
                }
                when (result) {
                    is SttResult.Success -> {
                        _recognizedText.value = result.text
                        parseIntent(result.text, currentPackageName, currentAppName)
                    }
                    is SttResult.Error -> {
                        _error.value = result.message
                        _recordingState.value = VoiceRecordingState.ERROR
                    }
                }
            }
        }
    }

    /**
     * Cancel recording without processing
     */
    fun cancelRecording() {
        isRecording = false

        if (recordingUsesRealtimeDashScope) {
            sttEngine?.cancelRecording()
        } else {
            audioFileRecorder?.cancelRecording()
        }

        recordingUsesRealtimeDashScope = false
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
        recordingUsesRealtimeDashScope = false
        scope.cancel()
        sttEngine?.release()
        audioFileRecorder?.release()
        sttEngine = null
        audioFileRecorder = null
        unifiedTranscriber = null
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
