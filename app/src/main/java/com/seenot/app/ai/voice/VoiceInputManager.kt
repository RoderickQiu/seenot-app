package com.seenot.app.ai.voice

import android.content.Context
import com.seenot.app.R
import com.seenot.app.utils.Logger
import com.seenot.app.ai.parser.IntentParser
import com.seenot.app.ai.parser.ParsedConstraint
import com.seenot.app.ai.parser.ParsedIntentResult
import com.seenot.app.config.InterventionLevelPrefs
import com.seenot.app.ai.stt.AudioFileRecorder
import com.seenot.app.ai.stt.RealtimeSttStopCoordinator
import com.seenot.app.ai.stt.SttEngine
import com.seenot.app.ai.stt.SttResult
import com.seenot.app.ai.stt.UnifiedTranscriber
import com.seenot.app.account.SeenotManagedAiCredentialProvider
import com.seenot.app.config.AiSource
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
        private const val REALTIME_STT_FINAL_WAIT_TIMEOUT_MS = 2500L
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
    private var realtimeStopCoordinator: RealtimeSttStopCoordinator? = null
    private var realtimeStopTimeoutJob: Job? = null

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
            intentParser = IntentParser { context }
        }
    }

    private fun shouldUseRealtimeDashScope(): Boolean {
        return ApiConfig.getSttProvider() == AiProvider.DASHSCOPE
    }

    private fun localizeSttError(message: String): String {
        return when {
            message.contains("Model.AccessDenied", ignoreCase = true) ||
                message.contains("Model access denied", ignoreCase = true) -> {
                context.getString(R.string.stt_dashscope_model_access_denied)
            }
            message.contains("Gemini") -> context.getString(R.string.stt_gemini_not_supported)
            message.contains("DashScope") -> context.getString(R.string.stt_dashscope_not_supported)
            message.contains("Anthropic") -> context.getString(R.string.stt_anthropic_not_supported)
            message.contains("配置不完整") -> context.getString(R.string.stt_config_incomplete)
            message.contains("语音转写失败") -> {
                val code = Regex("\\d+").find(message)?.value
                context.getString(R.string.stt_failed, code ?: "")
            }
            message.contains("结果为空") -> context.getString(R.string.stt_result_empty)
            else -> message
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

        recordingUsesRealtimeDashScope = shouldUseRealtimeDashScope()
        val started = if (recordingUsesRealtimeDashScope) {
            isRecording = true
            recordingStartTime = System.currentTimeMillis()
            _recordingState.value = VoiceRecordingState.RECORDING
            _recognizedText.value = null
            _parsedIntent.value = null
            _error.value = null
            scope.launch {
                startRealtimeDashScopeRecording()
            }
            true
        } else {
            audioFileRecorder?.startRecording() ?: false
        }

        if (started) {
            if (!recordingUsesRealtimeDashScope) {
                isRecording = true
                recordingStartTime = System.currentTimeMillis()
                _recordingState.value = VoiceRecordingState.RECORDING
                _recognizedText.value = null
                _parsedIntent.value = null
                _error.value = null
            }

            Logger.d(TAG, "Recording started")
        } else {
            _error.value = context.getString(R.string.voice_err_start_failed)
            _recordingState.value = VoiceRecordingState.ERROR
            recordingUsesRealtimeDashScope = false
        }

        return started
    }

    /**
     * Stop recording and wait briefly for the real-time STT final result.
     */
    fun stopRecording() {
        if (!isRecording) {
            Logger.w(TAG, "Not recording")
            return
        }

        isRecording = false

        if (recordingUsesRealtimeDashScope) {
            _recordingState.value = VoiceRecordingState.PROCESSING
            realtimeStopCoordinator?.onStopRequested()?.let(::handleRealtimeStopDecision)
            sttEngine?.stopRecording()
            scheduleRealtimeStopTimeout()
        } else {
            val audioFile = audioFileRecorder?.stopRecording()
            if (audioFile == null) {
                _error.value = context.getString(R.string.voice_err_record_failed)
                _recordingState.value = VoiceRecordingState.ERROR
                return
            }

            _recordingState.value = VoiceRecordingState.PROCESSING
            scope.launch {
                val result = try {
                    unifiedTranscriber?.transcribe(audioFile) ?: SttResult.Error(context.getString(R.string.voice_err_transcriber_not_init))
                } finally {
                    audioFile.delete()
                }
                when (result) {
                    is SttResult.Success -> {
                        _recognizedText.value = result.text
                        parseIntent(result.text, currentPackageName, currentAppName)
                    }
                    is SttResult.Error -> {
                        _error.value = localizeSttError(result.message)
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

        realtimeStopTimeoutJob?.cancel()
        realtimeStopCoordinator = null
        recordingUsesRealtimeDashScope = false
        _recordingState.value = VoiceRecordingState.IDLE
    }

    private suspend fun startRealtimeDashScopeRecording() {
        val settings = try {
            if (ApiConfig.getAiSource() == AiSource.SEENOT_AI) {
                SeenotManagedAiCredentialProvider(context).getSettingsWithFreshManagedCredential()
            } else {
                null
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to refresh managed AI credential for realtime STT", e)
            _error.value = e.message ?: context.getString(R.string.voice_err_start_failed)
            _recordingState.value = VoiceRecordingState.ERROR
            recordingUsesRealtimeDashScope = false
            return
        }

        sttEngine?.setSessionApiKeyOverride(
            settings
                ?.takeIf { it.provider == AiProvider.DASHSCOPE }
                ?.apiKey
        )
        sttEngine?.setSessionApiBaseUrlOverride(
            settings
                ?.takeIf { it.provider == AiProvider.DASHSCOPE }
                ?.baseUrl
        )
        realtimeStopCoordinator = RealtimeSttStopCoordinator()
        sttEngine?.setCallback(object : SttEngine.TranscriptionCallback {
            override fun onIntermediateResult(text: String) {
                Logger.d(TAG, "Intermediate: $text")
                scope.launch {
                    _recognizedText.value = text
                    realtimeStopCoordinator?.onIntermediateResult(text)?.let(::handleRealtimeStopDecision)
                }
            }

            override fun onFinalResult(text: String) {
                Logger.d(TAG, "Final result callback: $text")
                scope.launch {
                    _recognizedText.value = text
                    realtimeStopCoordinator?.onFinalResult(text)?.let(::handleRealtimeStopDecision)
                }
            }

            override fun onError(error: String) {
                Logger.e(TAG, "STT Error callback: $error")
                scope.launch {
                    realtimeStopTimeoutJob?.cancel()
                    realtimeStopCoordinator?.onError()
                    _error.value = localizeSttError(error)
                    _recordingState.value = VoiceRecordingState.ERROR
                    recordingUsesRealtimeDashScope = false
                    isRecording = false
                }
            }

            override fun onComplete() {
                Logger.d(TAG, "STT Complete callback")
                scope.launch {
                    realtimeStopCoordinator?.onComplete()?.let(::handleRealtimeStopDecision)
                }
            }
        })

        val started = sttEngine?.startRecording() ?: false
        if (started) {
            _recordingState.value = VoiceRecordingState.RECORDING
            Logger.d(TAG, "Realtime recording started")
        } else {
            isRecording = false
            _error.value = context.getString(R.string.voice_err_start_failed)
            _recordingState.value = VoiceRecordingState.ERROR
            recordingUsesRealtimeDashScope = false
            realtimeStopCoordinator = null
        }
    }

    private fun scheduleRealtimeStopTimeout() {
        realtimeStopTimeoutJob?.cancel()
        realtimeStopTimeoutJob = scope.launch {
            delay(REALTIME_STT_FINAL_WAIT_TIMEOUT_MS)
            realtimeStopCoordinator?.onTimeout()?.let(::handleRealtimeStopDecision)
        }
    }

    private fun handleRealtimeStopDecision(decision: RealtimeSttStopCoordinator.Decision) {
        realtimeStopTimeoutJob?.cancel()
        realtimeStopTimeoutJob = null
        realtimeStopCoordinator = null
        recordingUsesRealtimeDashScope = false
        when (decision) {
            is RealtimeSttStopCoordinator.Decision.UseText -> {
                _recognizedText.value = decision.text
                Logger.d(TAG, "Using realtime recognized text: ${decision.text}")
                parseIntent(decision.text, currentPackageName, currentAppName)
            }
            RealtimeSttStopCoordinator.Decision.EmptyRecognition -> {
                _error.value = context.getString(R.string.voice_err_not_recognized)
                _recordingState.value = VoiceRecordingState.ERROR
            }
        }
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
                        _error.value = context.getString(R.string.voice_err_parser_not_init)
                        _recordingState.value = VoiceRecordingState.ERROR
                    }
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Intent parsing failed", e)
                _error.value = context.getString(R.string.voice_err_parse_failed, e.message ?: "unknown")
                _recordingState.value = VoiceRecordingState.ERROR
            }
        }
    }

    /**
     * Parse text input directly (for manual input mode)
     */
    fun parseTextInput(text: String, packageName: String, appName: String) {
        if (text.isBlank()) {
            _error.value = context.getString(R.string.voice_err_no_intent_input)
            return
        }

        ensureEngines()
        _recordingState.value = VoiceRecordingState.PROCESSING
        parseIntent(text, packageName, appName)
    }

    private fun ParsedConstraint.toSessionConstraint(): SessionConstraint {
        return InterventionLevelPrefs.applyToConstraint(
            context = context,
            constraint = SessionConstraint(
                id = id,
                type = type,
                description = description,
                timeLimitMs = timeLimit?.let { it.durationMinutes * 60 * 1000L },
                timeScope = timeLimit?.scope ?: TimeScope.SESSION,
                interventionLevel = intervention,
                isActive = true,
                effectiveIntent = effectiveIntent
            )
        )
    }

    /**
     * Release resources
     */
    fun release() {
        isRecording = false
        recordingUsesRealtimeDashScope = false
        realtimeStopTimeoutJob?.cancel()
        realtimeStopCoordinator = null
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
