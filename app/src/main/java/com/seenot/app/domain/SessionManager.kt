package com.seenot.app.domain

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.seenot.app.R
import com.seenot.app.ai.feedback.FalsePositiveRuleGenerator
import com.seenot.app.ai.feedback.GeneratedFalsePositiveRuleResult
import com.seenot.app.ai.feedback.FalsePositiveRulePreview
import com.seenot.app.ai.screen.ScreenAnalyzer
import com.seenot.app.config.ApiConfig
import com.seenot.app.data.local.SeenotDatabase
import com.seenot.app.data.local.entity.IntentConstraintEntity
import com.seenot.app.data.local.entity.SessionEntity
import com.seenot.app.data.model.AppHintScopeType
import com.seenot.app.data.model.ConstraintType
import com.seenot.app.data.model.InterventionLevel
import com.seenot.app.data.model.RuleRecord
import com.seenot.app.data.model.TimeScope
import com.seenot.app.data.model.buildIntentScopedHintId
import com.seenot.app.data.repository.RuleRecordRepository
import com.seenot.app.data.repository.AppHintRepository
import com.seenot.app.data.repository.SessionRepository
import com.seenot.app.domain.action.ActionExecutor
import com.seenot.app.observability.RuntimeEventLogger
import com.seenot.app.observability.RuntimeEventType
import com.seenot.app.service.SeenotAccessibilityService
import com.seenot.app.ui.overlay.InterventionFeedbackDialogOverlay
import com.seenot.app.ui.overlay.FalsePositiveRuleReviewOverlay
import com.seenot.app.ui.overlay.ToastOverlay
import com.seenot.app.utils.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Session Manager - Core component that manages the session lifecycle
 *
 * Responsibilities:
 * - Create/resume/end sessions when user enters/exits controlled apps
 * - Manage timers and countdown
 * - Coordinate ScreenAnalyzer for AI vision
 * - Handle intent stacking and modification
 */
class SessionManager(private val context: Context) {

    companion object {
        private const val TAG = "SessionManager"
        private const val PREFS_NAME = "seenot_prefs"
        private const val KEY_CONTROLLED_APPS = "controlled_apps"
        private const val KEY_LAST_INTENT_PREFIX = "last_intent_"
        private const val KEY_INTENT_HISTORY_PREFIX = "intent_history_"
        private const val KEY_PRESET_RULES_PREFIX = "preset_rules_"
        private const val KEY_DEFAULT_RULE_PREFIX = "default_rule_"
        private const val KEY_AUTO_START = "auto_start"
        private const val MAX_HISTORY_PER_APP = 10

        // Short vs long session pause threshold (ms)
        private const val SHORT_PAUSE_THRESHOLD = 30_000L // 30 seconds
        private const val FALSE_POSITIVE_DIALOG_COOLDOWN_MS = 30_000L
        private const val DIALOG_REENTRY_COOLDOWN_MS = 5_000L

        // SeeNot's own package - should be ignored in app detection
        const val SEENOT_PACKAGE_NAME = "com.seenot.app"

        @Volatile
        private var INSTANCE: SessionManager? = null

        fun getInstance(context: Context): SessionManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SessionManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val database = SeenotDatabase.getInstance(context)
    private val repository = SessionRepository(
        database.sessionDao(),
        database.sessionIntentDao(),
        database.intentConstraintDao(),
        database.screenAnalysisResultDao()
    )
    private val ruleRecordRepository = RuleRecordRepository(context)
    private val appHintRepository = AppHintRepository(context)
    private val falsePositiveRuleGenerator = FalsePositiveRuleGenerator(context)
    private val runtimeEventLogger = RuntimeEventLogger.getInstance(context)

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val gson = Gson()

    private val dailyTimeTracker = DailyTimeTracker(context)

    // Current active session
    private val _activeSession = MutableStateFlow<ActiveSession?>(null)
    val activeSession: StateFlow<ActiveSession?> = _activeSession.asStateFlow()

    // Controlled apps set (from settings)
    private val _controlledApps = MutableStateFlow<Set<String>>(loadControlledApps())
    val controlledApps: StateFlow<Set<String>> = _controlledApps.asStateFlow()

    // Session events
    private val _sessionEvents = MutableSharedFlow<SessionEvent>()
    val sessionEvents: SharedFlow<SessionEvent> = _sessionEvents.asSharedFlow()

    // Timer job
    private var timerJob: Job? = null
    private var pauseTimeoutJob: Job? = null

    // Screen analyzer for AI vision
    private var screenAnalyzer: ScreenAnalyzer? = null

    // Action executor for interventions
    private var actionExecutor: ActionExecutor? = null

    // Session pause tracking for short recovery window (used when leaving to non-controlled apps / home)
    private var sessionPausedAt: Long? = null

    // Suspended sessions from controlled-app-to-controlled-app switches
    // packageName -> (session snapshot, pausedAt timestamp)
    private val suspendedSessions = mutableMapOf<String, Pair<ActiveSession, Long>>()

    // Content match state for conditional timing
    private val constraintMatchStates = mutableMapOf<String, Boolean>()
    private val _constraintMatchStateFlow = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val constraintMatchStateFlow: StateFlow<Map<String, Boolean>> = _constraintMatchStateFlow.asStateFlow()

    // Minimal in-session suppression after user marks a violation as false positive.
    private var falsePositiveDialogCooldownUntil = 0L
    private var dialogReentryCooldownUntil = 0L
    private var pendingSessionStartContext: PendingSessionStartContext? = null

    private fun logRuntimeEvent(
        eventType: RuntimeEventType,
        session: ActiveSession? = _activeSession.value,
        payload: Map<String, Any?> = emptyMap()
    ) {
        runtimeEventLogger.log(
            eventType = eventType,
            sessionId = session?.sessionId,
            appPackage = session?.appPackageName,
            appDisplayName = session?.appDisplayName,
            payload = payload
        )
    }

    @Synchronized
    private fun setPendingSessionStartContext(
        triggerSource: String,
        switchFromPackage: String? = null,
        switchToPackage: String? = null
    ) {
        pendingSessionStartContext = PendingSessionStartContext(
            triggerSource = triggerSource,
            switchFromPackage = switchFromPackage,
            switchToPackage = switchToPackage
        )
    }

    @Synchronized
    private fun consumePendingSessionStartContext(): PendingSessionStartContext? {
        val context = pendingSessionStartContext
        pendingSessionStartContext = null
        return context
    }
    private val falsePositiveLearningMutex = Mutex()

    @Volatile
    private var isFalsePositiveLearningInProgress = false

    init {
        // Start listening for app changes
        Logger.d(TAG, "SessionManager initializing, observing app changes")
        Logger.i(TAG, "SessionManager initializing, observing app changes")
        observeAppChanges()
        observeDeviceStateChanges()
    }

    /**
     * Observe app changes from AccessibilityService
     */
    private fun observeAppChanges() {
        scope.launch {
            SeenotAccessibilityService.currentPackage.collect { packageName ->
                if (packageName != null) {
                    handleAppChange(packageName)
                }
            }
        }
    }

    private fun observeDeviceStateChanges() {
        scope.launch {
            SeenotAccessibilityService.deviceState
                .distinctUntilChangedBy { state ->
                    listOf(
                        state.shouldAnalyze,
                        state.isInteractive,
                        state.isDeviceLocked,
                        state.isKeyguardLocked,
                        state.displayState
                    )
                }
                .collect { state ->
                    handleDeviceStateChange(state)
                }
        }
    }

    private fun schedulePauseTimeout(session: ActiveSession, pauseReason: String) {
        cancelPauseTimeout()

        val pausedAt = sessionPausedAt ?: return
        pauseTimeoutJob = scope.launch {
            delay(SHORT_PAUSE_THRESHOLD)

            val currentSession = _activeSession.value
            if (
                currentSession?.sessionId != session.sessionId ||
                currentSession.appPackageName != session.appPackageName ||
                !currentSession.isPaused
            ) {
                return@launch
            }

            val pausedFor = System.currentTimeMillis() - pausedAt
            if (pausedFor < SHORT_PAUSE_THRESHOLD) {
                delay(SHORT_PAUSE_THRESHOLD - pausedFor)
            }

            val stillPausedSession = _activeSession.value
            if (
                stillPausedSession?.sessionId != session.sessionId ||
                stillPausedSession.appPackageName != session.appPackageName ||
                !stillPausedSession.isPaused
            ) {
                return@launch
            }

            Logger.d(
                TAG,
                "Pause timeout reached for ${session.appPackageName} after $pauseReason; ending stale paused session"
            )
            endSession(SessionEndReason.USER_LEFT)
            sessionPausedAt = null
            pauseTimeoutJob = null
        }
    }

    private fun cancelPauseTimeout() {
        pauseTimeoutJob?.cancel()
        pauseTimeoutJob = null
    }

    private suspend fun handleDeviceStateChange(state: com.seenot.app.service.AccessibilityDeviceState) {
        val currentSession = _activeSession.value ?: return

        if (!state.shouldAnalyze) {
            if (!currentSession.isPaused) {
                Logger.d(
                    TAG,
                    "Device became unsuitable for analysis, pausing session " +
                        "(interactive=${state.isInteractive}, " +
                        "deviceLocked=${state.isDeviceLocked}, " +
                        "keyguardLocked=${state.isKeyguardLocked}, " +
                        "displayState=${state.displayState})"
                )
                pauseSession()
                sessionPausedAt = System.currentTimeMillis()
                schedulePauseTimeout(currentSession, "device_state_change")
            }
            return
        }

        if (!currentSession.isPaused) {
            return
        }

        val pausedAt = sessionPausedAt
        val timeDiff = if (pausedAt != null) System.currentTimeMillis() - pausedAt else -1
        if (pausedAt != null && timeDiff > SHORT_PAUSE_THRESHOLD) {
            Logger.d(
                TAG,
                "Paused session for ${currentSession.appPackageName} exceeded timeout (${timeDiff}ms), ending session"
            )
            endSession(SessionEndReason.USER_LEFT)
            sessionPausedAt = null

            val currentPackage = SeenotAccessibilityService.currentPackage.value
            if (currentPackage == currentSession.appPackageName) {
                setPendingSessionStartContext(
                    triggerSource = "device_recovery_after_pause_timeout",
                    switchToPackage = currentSession.appPackageName
                )
                SeenotAccessibilityService.instance?.forceRestartOverlayForCurrentApp(currentSession.appPackageName)
                    ?: requestNewSession(
                        packageName = currentSession.appPackageName,
                        triggerSource = "device_recovery_after_pause_timeout"
                    )
            }
            return
        }

        val currentPackage = SeenotAccessibilityService.currentPackage.value
        if (currentPackage != currentSession.appPackageName) {
            Logger.d(
                TAG,
                "Device became analyzable again, but foreground package is $currentPackage " +
                    "instead of ${currentSession.appPackageName}; keeping session paused"
            )
            return
        }

        if (pausedAt != null && timeDiff <= SHORT_PAUSE_THRESHOLD) {
            Logger.d(TAG, "Resuming session after device state recovery for ${currentSession.appPackageName}")
            resumeSession()
            sessionPausedAt = null
        } else {
            Logger.d(
                TAG,
                "Device recovered after pause timeout for ${currentSession.appPackageName}, ending session"
            )
            endSession(SessionEndReason.USER_LEFT)
            sessionPausedAt = null
            setPendingSessionStartContext(
                triggerSource = "device_recovery_after_pause_timeout",
                switchToPackage = currentSession.appPackageName
            )
            SeenotAccessibilityService.instance?.forceRestartOverlayForCurrentApp(currentSession.appPackageName)
                ?: requestNewSession(
                    packageName = currentSession.appPackageName,
                    triggerSource = "device_recovery_after_pause_timeout"
                )
        }
    }

    /**
     * Handle app switch
     */
    private suspend fun handleAppChange(packageName: String) {
        val controlled = _controlledApps.value
        val currentSession = _activeSession.value

        // Ignore SeeNot's own overlays (ToastOverlay, FloatingIndicator, etc.)
        if (packageName == SEENOT_PACKAGE_NAME) {
            Logger.d(TAG, "Ignoring SeeNot's own package: $packageName")
            return
        }

        Logger.d(TAG, "App changed to: $packageName, controlled apps: $controlled")

        if (packageName in controlled) {
            // User entered a controlled app
            Logger.d(TAG, "Entered controlled app: $packageName")
            onControlledAppEntered(packageName)
        } else {
            Logger.d(TAG, "Left to non-controlled app: $packageName")
            onControlledAppExited(packageName)
        }
    }

    private suspend fun onControlledAppEntered(packageName: String) {
        val currentSession = _activeSession.value

        Logger.d(TAG, ">>> onControlledAppEntered: pkg=$packageName, session=${currentSession?.appPackageName}, isPaused=${currentSession?.isPaused}, pausedAt=$sessionPausedAt")

        if (currentSession != null && currentSession.appPackageName == packageName) {
            if (currentSession.isPaused) {
                val pausedAt = sessionPausedAt
                val timeDiff = if (pausedAt != null) System.currentTimeMillis() - pausedAt else -1
                Logger.d(TAG, ">>> Session was paused, time diff: ${timeDiff}ms, threshold: ${SHORT_PAUSE_THRESHOLD}ms")

                if (pausedAt != null && timeDiff <= SHORT_PAUSE_THRESHOLD) {
                    Logger.d(TAG, ">>> Resuming session within short threshold for: $packageName")
                    resumeSession()
                    sessionPausedAt = null
                } else {
                    Logger.d(TAG, ">>> Session expired (beyond short threshold or no pausedAt), creating new session for: $packageName")
                    endSession(SessionEndReason.USER_LEFT)
                    sessionPausedAt = null
                    setPendingSessionStartContext(
                        triggerSource = "recreate_after_pause_expired",
                        switchToPackage = packageName
                    )
                    SeenotAccessibilityService.instance?.forceRestartOverlayForCurrentApp(packageName)
                        ?: requestNewSession(
                            packageName = packageName,
                            triggerSource = "recreate_after_pause_expired"
                        )
                }
            } else {
                Logger.d(TAG, ">>> Session already active for: $packageName, ignoring")
            }
        } else if (currentSession != null && currentSession.appPackageName != packageName) {
            Logger.d(TAG, ">>> Switching from ${currentSession.appPackageName} to $packageName, suspending old session")
            suspendCurrentSession(currentSession, packageName)

            tryResumeSuspendedSession(packageName) || run {
                requestNewSession(
                    packageName = packageName,
                    triggerSource = "switch_from_other_controlled_app",
                    switchFromPackage = currentSession.appPackageName
                )
                false
            }
        } else if (currentSession == null) {
            if (!tryResumeSuspendedSession(packageName)) {
                Logger.d(TAG, ">>> No active or suspended session, creating new session for: $packageName")
                requestNewSession(
                    packageName = packageName,
                    triggerSource = "enter_controlled_app"
                )
            }
        }
    }

    private fun suspendCurrentSession(session: ActiveSession, switchToPackage: String? = null) {
        timerJob?.cancel()
        cancelPauseTimeout()
        stopScreenAnalysis()
        _activeSession.value = session.copy(isPaused = true)
        suspendedSessions[session.appPackageName] = Pair(session.copy(isPaused = true), System.currentTimeMillis())
        _activeSession.value = null
        sessionPausedAt = null
        runtimeEventLogger.log(
            eventType = RuntimeEventType.SESSION_PAUSED,
            sessionId = session.sessionId,
            appPackage = session.appPackageName,
            appDisplayName = session.appDisplayName,
            payload = buildMap {
                put("pause_reason", "switch_to_other_controlled_app")
                put("switch_to_package", switchToPackage)
            }
        )
        
        scope.launch {
            _sessionEvents.emit(SessionEvent.SessionPaused(session))
        }
    }

    private suspend fun tryResumeSuspendedSession(packageName: String): Boolean {
        val (suspendedSession, pausedAt) = suspendedSessions[packageName] ?: return false
        val timeDiff = System.currentTimeMillis() - pausedAt

        return if (timeDiff <= SHORT_PAUSE_THRESHOLD) {
            Logger.d(TAG, ">>> Resuming suspended session for $packageName (paused ${timeDiff}ms ago)")
            suspendedSessions.remove(packageName)
            _activeSession.value = suspendedSession
            resumeSession()
            true
        } else {
            Logger.d(TAG, ">>> Suspended session for $packageName expired (${timeDiff}ms > threshold), discarding")
            suspendedSessions.remove(packageName)
            false
        }
    }

    fun hasResumableSession(packageName: String): Boolean {
        val active = _activeSession.value
        if (active?.appPackageName == packageName && active.isPaused) {
            val pausedAt = sessionPausedAt ?: return false
            return System.currentTimeMillis() - pausedAt <= SHORT_PAUSE_THRESHOLD
        }

        val (_, pausedAt) = suspendedSessions[packageName] ?: return false
        return System.currentTimeMillis() - pausedAt <= SHORT_PAUSE_THRESHOLD
    }

    private suspend fun onControlledAppExited(@Suppress("UNUSED_PARAMETER") packageName: String) {
        stopScreenAnalysis()
        
        if (_activeSession.value == null) {
            cancelPauseTimeout()
            Logger.d(TAG, "onControlledAppExited: no active session, emitting sessionCleared")
            scope.launch {
                _sessionEvents.emit(SessionEvent.SessionCleared)
            }
            return
        }

        val session = _activeSession.value ?: return
        pauseSession()
        sessionPausedAt = System.currentTimeMillis()
        Logger.d(TAG, ">>> Session paused at ${sessionPausedAt}, will auto-end if not resumed within ${SHORT_PAUSE_THRESHOLD}ms")
        schedulePauseTimeout(session, "left_controlled_app")
    }

    private suspend fun requestNewSession(
        packageName: String,
        triggerSource: String = "request_new_session",
        switchFromPackage: String? = null
    ) {
        setPendingSessionStartContext(
            triggerSource = triggerSource,
            switchFromPackage = switchFromPackage,
            switchToPackage = packageName
        )
        runtimeEventLogger.log(
            eventType = RuntimeEventType.SESSION_NEW_REQUEST_SHOWN,
            sessionId = null,
            appPackage = packageName,
            appDisplayName = null,
            payload = buildMap {
                put("trigger_source", triggerSource)
                put("switch_from_package", switchFromPackage)
                put("switch_to_package", packageName)
            }
        )
        _sessionEvents.emit(SessionEvent.ShowVoiceInput(packageName))
    }

    suspend fun createSession(
        packageName: String,
        displayName: String,
        constraints: List<SessionConstraint>
    ): Long? {
        val currentPackage = SeenotAccessibilityService.currentPackage.value
        if (currentPackage != packageName) {
            Logger.w(
                TAG,
                "Skip createSession for $packageName because foreground changed to $currentPackage"
            )
            return null
        }

        cancelPauseTimeout()
        val startContext = consumePendingSessionStartContext()
        val sessionId = repository.createSession(
            appPackageName = packageName,
            appDisplayName = displayName,
            totalTimeLimitMs = null
        )

        val activeSession = ActiveSession(
            sessionId = sessionId,
            appPackageName = packageName,
            appDisplayName = displayName,
            constraints = constraints,
            startTime = System.currentTimeMillis(),
            isPaused = false,
            constraintTimeRemaining = constraints.associate { constraint ->
                val remaining = if (constraint.timeScope == TimeScope.DAILY_TOTAL) {
                    val accumulated = dailyTimeTracker.getAccumulatedTime(constraint.id)
                    val limit = constraint.timeLimitMs ?: 0L
                    (limit - accumulated).coerceAtLeast(0L)
                } else {
                    constraint.timeLimitMs
                }
                constraint.id to remaining
            }
        )

        _activeSession.value = activeSession

        resetConstraintMatchStates(constraints)
        persistSessionConstraints(sessionId, constraints)

        saveLastIntent(packageName, constraints)
        startTimer()
        scope.launch(Dispatchers.IO) {
            autoApplyCarryOverHints(packageName, displayName, constraints)
        }

        if (ApiConfig.isConfigured()) {
            startScreenAnalysis(packageName, displayName, constraints)
        }

        logRuntimeEvent(
            eventType = RuntimeEventType.SESSION_STARTED,
            session = activeSession,
            payload = buildMap {
                put("active_constraint_ids", constraints.map { it.id })
                put("time_limit_ms", constraints.mapNotNull { it.timeLimitMs }.maxOrNull())
                put("trigger_source", startContext?.triggerSource ?: "create_session")
                put("switch_from_package", startContext?.switchFromPackage)
                put("switch_to_package", startContext?.switchToPackage ?: packageName)
            }
        )

        _sessionEvents.emit(SessionEvent.SessionStarted(activeSession))

        return sessionId
    }

    /**
     * Start screen analysis for the current session
     */
    @Suppress("UNUSED_PARAMETER")
    private fun startScreenAnalysis(packageName: String, displayName: String, constraints: List<SessionConstraint>) {
        if (screenAnalyzer == null) {
            screenAnalyzer = ScreenAnalyzer(context)
        }
        if (actionExecutor == null) {
            actionExecutor = ActionExecutor(context)
        }

        // Start periodic analysis
        screenAnalyzer?.startAnalysis(
            packageName = packageName,
            displayName = displayName,
            constraints = constraints,
            onViolation = { constraint, confidence, analysisId ->
                handleViolation(constraint, confidence, analysisId)
            }
        )

        Logger.d(TAG, "Started screen analysis for session")
    }

    /**
     * Handle violation detected by screen analyzer
     */
    private fun handleViolation(constraint: SessionConstraint, confidence: Double, analysisId: String?) {
        if (isFalsePositiveLearningInProgress) {
            Logger.d(TAG, "False-positive learning in progress, skipping violation handling")
            return
        }

        Logger.d(TAG, "Violation detected: ${constraint.description}, confidence: $confidence")
        Logger.w(TAG, "Violation detected: ${constraint.description}, confidence: $confidence")

        // Update session state with violation
        val currentSession = _activeSession.value ?: return
        _activeSession.value = currentSession.copy(
            violationCount = currentSession.violationCount + 1
        )

        val executor = actionExecutor
        if (executor == null) {
            Logger.w(TAG, "ActionExecutor unavailable, skipping violation handling")
            return
        }

        if (InterventionFeedbackDialogOverlay.isShowing()) {
            logRuntimeEvent(
                eventType = RuntimeEventType.INTERVENTION_BLOCKED_COOLDOWN,
                session = currentSession,
                payload = mapOf(
                    "analysis_id" to analysisId,
                    "constraint_id" to constraint.id,
                    "blocked_reason" to "dialog_visible"
                )
            )
            Logger.d(TAG, "Feedback dialog already visible, ignoring new violation")
            return
        }

        if (System.currentTimeMillis() < dialogReentryCooldownUntil &&
            constraint.type != ConstraintType.TIME_CAP
        ) {
            logRuntimeEvent(
                eventType = RuntimeEventType.INTERVENTION_BLOCKED_COOLDOWN,
                session = currentSession,
                payload = mapOf(
                    "analysis_id" to analysisId,
                    "constraint_id" to constraint.id,
                    "blocked_reason" to "dialog_reentry_cooldown",
                    "cooldown_remaining_ms" to (dialogReentryCooldownUntil - System.currentTimeMillis())
                )
            )
            Logger.d(TAG, "Dialog reentry cooldown active, ignoring violation")
            return
        }

        if (System.currentTimeMillis() < falsePositiveDialogCooldownUntil &&
            constraint.type != ConstraintType.TIME_CAP
        ) {
            logRuntimeEvent(
                eventType = RuntimeEventType.INTERVENTION_BLOCKED_COOLDOWN,
                session = currentSession,
                payload = mapOf(
                    "analysis_id" to analysisId,
                    "constraint_id" to constraint.id,
                    "blocked_reason" to "false_positive_cooldown",
                    "cooldown_remaining_ms" to (falsePositiveDialogCooldownUntil - System.currentTimeMillis())
                )
            )
            Logger.d(TAG, "False-positive cooldown active, ignoring forced action candidate")
            return
        }

        if (shouldInterceptViolationWithDialog(constraint)) {
            Logger.d(TAG, "Intercepting violation with feedback dialog")
            logRuntimeEvent(
                eventType = RuntimeEventType.INTERVENTION_DIALOG_SHOWN,
                session = currentSession,
                payload = mapOf(
                    "analysis_id" to analysisId,
                    "constraint_id" to constraint.id,
                    "confidence" to confidence,
                    "dialog_intercepted" to true
                )
            )
            showInterventionFeedbackDialog(currentSession, constraint, confidence, analysisId)
        } else {
            // Execute intervention based on confidence
            executor.executeIntervention(
                constraint,
                confidence,
                "violation",
                currentSession.appDisplayName,
                currentSession.appPackageName,
                analysisId
            )
        }

        // Emit violation event
        scope.launch {
            _sessionEvents.emit(SessionEvent.ViolationDetected(constraint, confidence))
        }
    }

    private fun shouldInterceptViolationWithDialog(
        constraint: SessionConstraint
    ): Boolean {
        return constraint.type != ConstraintType.TIME_CAP
    }

    private fun showInterventionFeedbackDialog(
        session: ActiveSession,
        constraint: SessionConstraint,
        confidence: Double,
        analysisId: String?
    ) {
        val isGentle = constraint.interventionLevel == InterventionLevel.GENTLE
        scope.launch {
            InterventionFeedbackDialogOverlay.show(
                context = context,
                appName = session.appDisplayName,
                constraintDescription = constraint.description,
                titleText = if (isGentle) context.getString(R.string.dialog_title_pause) else context.getString(R.string.dialog_title_pause_confirm),
                subtitleText = if (isGentle) {
                    context.getString(R.string.dialog_subtitle_gentle_deviation)
                } else {
                    context.getString(R.string.dialog_subtitle_moderate_deviation)
                },
                primaryButtonText = if (isGentle) context.getString(R.string.dialog_btn_back_to_task) else context.getString(R.string.dialog_btn_exit),
                secondaryButtonText = if (isGentle) context.getString(R.string.dialog_btn_continue) else null,
                onFalsePositive = {
                    logRuntimeEvent(
                        eventType = RuntimeEventType.USER_MARKED_MISUNDERSTAND,
                        session = session,
                        payload = mapOf(
                            "analysis_id" to analysisId,
                            "constraint_id" to constraint.id,
                            "source_surface" to "intervention_dialog"
                        )
                    )
                    dialogReentryCooldownUntil =
                        System.currentTimeMillis() + DIALOG_REENTRY_COOLDOWN_MS
                    falsePositiveDialogCooldownUntil =
                        System.currentTimeMillis() + FALSE_POSITIVE_DIALOG_COOLDOWN_MS
                    showFalsePositiveReviewOverlay(
                        title = context.getString(R.string.false_positive_review_title),
                        subtitle = context.getString(R.string.false_positive_review_subtitle),
                        onGenerate = { callback ->
                            previewFalsePositiveForLatestViolation(
                                session = session,
                                constraint = constraint,
                                callback = callback
                            )
                        },
                        onSave = { ruleText, scopeType, callback ->
                            saveFalsePositiveForLatestViolation(
                                session = session,
                                constraint = constraint,
                                confirmedRule = ruleText,
                                scopeType = scopeType,
                                source = "intervention_dialog",
                                onComplete = callback
                            )
                        }
                    )
                },
                onPrimaryAction = {
                    dialogReentryCooldownUntil =
                        System.currentTimeMillis() + DIALOG_REENTRY_COOLDOWN_MS
                    if (!isGentle) {
                        logRuntimeEvent(
                            eventType = RuntimeEventType.USER_CHOSE_EXIT,
                            session = session,
                            payload = mapOf(
                                "analysis_id" to analysisId,
                                "constraint_id" to constraint.id,
                                "source_surface" to "intervention_dialog"
                            )
                        )
                    }
                    if (isGentle) {
                        actionExecutor?.executeUserConfirmedReturn(
                            constraint = constraint,
                            appName = session.appDisplayName,
                            packageName = session.appPackageName,
                            analysisId = analysisId
                        )
                    } else {
                        actionExecutor?.executeIntervention(
                            constraint,
                            confidence,
                            "violation",
                            session.appDisplayName,
                            session.appPackageName,
                            analysisId
                        )
                    }
                },
                onSecondaryAction = if (isGentle) {
                    {
                        dialogReentryCooldownUntil =
                            System.currentTimeMillis() + DIALOG_REENTRY_COOLDOWN_MS
                        actionExecutor?.allowOnce(
                            constraint = constraint,
                            appName = session.appDisplayName,
                            packageName = session.appPackageName,
                            analysisId = analysisId
                        )
                    }
                } else {
                    null
                }
            )
        }
    }

    private fun submitFalsePositiveForLatestViolation(
        session: ActiveSession,
        constraint: SessionConstraint,
        source: String,
        userNote: String? = null,
        onComplete: ((FalsePositiveFeedbackResult) -> Unit)? = null
    ) {
        scope.launch {
            try {
                val latestRecord = withContext(Dispatchers.IO) {
                    ruleRecordRepository.getLatestViolationAnalysisRecord(
                        sessionId = session.sessionId,
                        packageName = session.appPackageName,
                        constraintContent = constraint.description
                    )
                }

                if (latestRecord == null) {
                    Logger.w(TAG, "No violation history record found to mark: ${constraint.description}")
                    onComplete?.invoke(
                        FalsePositiveFeedbackResult(
                            success = false,
                            userMessage = context.getString(R.string.error_no_fp_record)
                        )
                    )
                    return@launch
                }

                val result = processFalsePositiveRecord(
                    record = latestRecord,
                    fallbackSession = session,
                    userNote = userNote,
                    confirmedRule = null,
                    source = source
                )
                onComplete?.invoke(result)
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to mark violation history record", e)
                onComplete?.invoke(
                    FalsePositiveFeedbackResult(
                        success = false,
                        userMessage = context.getString(R.string.error_record_fp_failed)
                    )
                )
            }
        }
    }

    private fun previewFalsePositiveForLatestViolation(
        session: ActiveSession,
        constraint: SessionConstraint,
        callback: (FalsePositiveRulePreviewResult) -> Unit
    ) {
        scope.launch {
            try {
                val latestRecord = withContext(Dispatchers.IO) {
                    ruleRecordRepository.getLatestViolationAnalysisRecord(
                        sessionId = session.sessionId,
                        packageName = session.appPackageName,
                        constraintContent = constraint.description
                    )
                }

                if (latestRecord == null) {
                    callback(
                        FalsePositiveRulePreviewResult(
                            success = false,
                            userMessage = context.getString(R.string.error_no_fp_record)
                        )
                    )
                    return@launch
                }

                previewFalsePositiveRule(latestRecord, onComplete = callback)
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to preview violation false positive", e)
                callback(
                    FalsePositiveRulePreviewResult(
                        success = false,
                        userMessage = context.getString(R.string.error_generate_rule_failed)
                    )
                )
            }
        }
    }

    private fun saveFalsePositiveForLatestViolation(
        session: ActiveSession,
        constraint: SessionConstraint,
        confirmedRule: String,
        scopeType: AppHintScopeType,
        source: String,
        onComplete: (FalsePositiveFeedbackResult) -> Unit
    ) {
        scope.launch {
            try {
                val latestRecord = withContext(Dispatchers.IO) {
                    ruleRecordRepository.getLatestViolationAnalysisRecord(
                        sessionId = session.sessionId,
                        packageName = session.appPackageName,
                        constraintContent = constraint.description
                    )
                }

                if (latestRecord == null) {
                    onComplete(
                        FalsePositiveFeedbackResult(
                            success = false,
                            userMessage = context.getString(R.string.error_no_fp_record)
                        )
                    )
                    return@launch
                }

                val result = processFalsePositiveRecord(
                    record = latestRecord,
                    fallbackSession = session,
                    userNote = null,
                    confirmedRule = confirmedRule,
                    confirmedScopeType = scopeType,
                    source = source
                )
                onComplete(result)
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to save violation false positive", e)
                onComplete(
                    FalsePositiveFeedbackResult(
                        success = false,
                        userMessage = context.getString(R.string.error_record_fp_failed)
                    )
                )
            }
        }
    }

    /**
     * Update content match state from screen analyzer
     * Used for conditional timing (PER_CONTENT constraints)
     */
    fun updateContentMatchState(constraintId: String, isMatching: Boolean) {
        constraintMatchStates[constraintId] = isMatching
        _constraintMatchStateFlow.value = constraintMatchStates.toMap()
        Logger.d(TAG, "Content match state updated: $constraintId = $isMatching")
    }

    fun markCurrentJudgmentAsWrong(
        constraintType: ConstraintType,
        isConditionMatched: Boolean,
        userNote: String? = null,
        source: String = "judgment_overlay",
        onComplete: ((FalsePositiveFeedbackResult) -> Unit)? = null
    ) {
        val session = _activeSession.value ?: return
        scope.launch {
            try {
                val latestRecord = withContext(Dispatchers.IO) {
                    ruleRecordRepository.getLatestAnalysisRecordForType(
                        sessionId = session.sessionId,
                        packageName = session.appPackageName,
                        constraintType = constraintType,
                        isConditionMatched = isConditionMatched
                    )
                }

                if (latestRecord == null) {
                    Logger.w(
                        TAG,
                        "No analysis record found to mark: type=$constraintType matched=$isConditionMatched"
                    )
                    onComplete?.invoke(
                        FalsePositiveFeedbackResult(
                            success = false,
                            userMessage = context.getString(R.string.error_no_judgment_record)
                        )
                    )
                    return@launch
                }

                val result = processFalsePositiveRecord(
                    record = latestRecord,
                    fallbackSession = session,
                    userNote = userNote,
                    confirmedRule = null,
                    source = source
                )
                onComplete?.invoke(result)
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to mark judgment record", e)
                onComplete?.invoke(
                    FalsePositiveFeedbackResult(
                        success = false,
                        userMessage = context.getString(R.string.error_record_fp_failed)
                    )
                )
            }
        }
    }

    fun previewCurrentJudgmentFalsePositiveRule(
        constraintType: ConstraintType,
        isConditionMatched: Boolean,
        onComplete: (FalsePositiveRulePreviewResult) -> Unit
    ) {
        val session = _activeSession.value ?: return
        scope.launch {
            try {
                val latestRecord = withContext(Dispatchers.IO) {
                    ruleRecordRepository.getLatestAnalysisRecordForType(
                        sessionId = session.sessionId,
                        packageName = session.appPackageName,
                        constraintType = constraintType,
                        isConditionMatched = isConditionMatched
                    )
                }

                if (latestRecord == null) {
                    onComplete(
                        FalsePositiveRulePreviewResult(
                            success = false,
                            userMessage = context.getString(R.string.error_no_judgment_record)
                        )
                    )
                    return@launch
                }

                previewFalsePositiveRule(latestRecord, onComplete = onComplete)
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to preview judgment false positive", e)
                onComplete(
                    FalsePositiveRulePreviewResult(
                        success = false,
                        userMessage = context.getString(R.string.error_generate_rule_failed)
                    )
                )
            }
        }
    }

    fun saveCurrentJudgmentFalsePositiveRule(
        constraintType: ConstraintType,
        isConditionMatched: Boolean,
        confirmedRule: String,
        scopeType: AppHintScopeType,
        source: String = "floating_overlay",
        onComplete: (FalsePositiveFeedbackResult) -> Unit
    ) {
        val session = _activeSession.value ?: return
        scope.launch {
            try {
                val latestRecord = withContext(Dispatchers.IO) {
                    ruleRecordRepository.getLatestAnalysisRecordForType(
                        sessionId = session.sessionId,
                        packageName = session.appPackageName,
                        constraintType = constraintType,
                        isConditionMatched = isConditionMatched
                    )
                }

                if (latestRecord == null) {
                    onComplete(
                        FalsePositiveFeedbackResult(
                            success = false,
                            userMessage = context.getString(R.string.error_no_judgment_record)
                        )
                    )
                    return@launch
                }

                val result = processFalsePositiveRecord(
                    record = latestRecord,
                    fallbackSession = session,
                    userNote = null,
                    confirmedRule = confirmedRule,
                    confirmedScopeType = scopeType,
                    source = source
                )
                onComplete(result)
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to save judgment false positive", e)
                onComplete(
                    FalsePositiveFeedbackResult(
                        success = false,
                        userMessage = context.getString(R.string.error_record_fp_failed)
                    )
                )
            }
        }
    }

    fun markRecordAsWrong(
        record: RuleRecord,
        userNote: String? = null,
        confirmedRule: String? = null,
        confirmedScopeType: AppHintScopeType? = null,
        source: String = "record_detail",
        onComplete: ((FalsePositiveFeedbackResult) -> Unit)? = null
    ) {
        scope.launch {
            val result = processFalsePositiveRecord(
                record = record,
                fallbackSession = _activeSession.value,
                userNote = userNote,
                confirmedRule = confirmedRule,
                confirmedScopeType = confirmedScopeType,
                source = source
            )
            onComplete?.invoke(result)
        }
    }

    fun previewFalsePositiveRule(
        record: RuleRecord,
        userNote: String? = null,
        onComplete: (FalsePositiveRulePreviewResult) -> Unit
    ) {
        scope.launch {
            try {
                val packageName = record.packageName
                    ?.takeIf { it.isNotBlank() }
                    ?: record.appName
                val contextInfo = resolveFalsePositiveContext(
                    record = record,
                    packageName = packageName,
                    fallbackSession = _activeSession.value
                )
                val preview = falsePositiveRuleGenerator.generateRulePreview(
                    packageName = packageName,
                    appName = contextInfo.appName,
                    record = record,
                    constraints = contextInfo.constraints,
                    userNote = userNote
                )
                onComplete(
                    FalsePositiveRulePreviewResult(
                        success = true,
                        generatedRule = preview.ruleText,
                        generatedScopeType = preview.scopeType,
                        generatedScopeLabel = when (preview.scopeType) {
                            AppHintScopeType.APP_GENERAL -> context.getString(R.string.scope_app_general)
                            AppHintScopeType.INTENT_SPECIFIC -> context.getString(R.string.scope_intent_specific)
                        },
                        userMessage = if (preview.ruleText.isNullOrBlank())
                            context.getString(R.string.fp_draft_generated_poor)
                        else
                            context.getString(R.string.fp_rule_generated)
                    )
                )
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to preview false positive rule", e)
                onComplete(
                    FalsePositiveRulePreviewResult(
                        success = false,
                        generatedScopeType = AppHintScopeType.INTENT_SPECIFIC,
                        generatedScopeLabel = context.getString(R.string.scope_intent_specific),
                        userMessage = context.getString(R.string.error_generate_rule_failed)
                    )
                )
            }
        }
    }

    private fun showFalsePositiveReviewOverlay(
        title: String,
        subtitle: String,
        onGenerate: ((FalsePositiveRulePreviewResult) -> Unit) -> Unit,
        onSave: (String, AppHintScopeType, (FalsePositiveFeedbackResult) -> Unit) -> Unit
    ) {
        FalsePositiveRuleReviewOverlay.show(
            context = context,
            titleText = title,
            subtitleText = subtitle,
            onGenerate = onGenerate,
            onSave = onSave
        )
    }

    private suspend fun processFalsePositiveRecord(
        record: RuleRecord,
        fallbackSession: ActiveSession?,
        userNote: String?,
        confirmedRule: String?,
        confirmedScopeType: AppHintScopeType? = null,
        source: String
    ): FalsePositiveFeedbackResult {
        return falsePositiveLearningMutex.withLock {
            val packageName = record.packageName
                ?.takeIf { it.isNotBlank() }
                ?: record.appName
            val matchedActiveSession = _activeSession.value?.takeIf {
                !it.isPaused && (it.sessionId == record.sessionId || it.appPackageName == packageName)
            }
            val shouldPauseJudgment = shouldPauseJudgmentDuringFalsePositiveLearning(source, matchedActiveSession)

            if (shouldPauseJudgment) {
                isFalsePositiveLearningInProgress = true
                withContext(Dispatchers.Main) {
                    ToastOverlay.show(context, context.getString(R.string.fp_generating_pause))
                }
                screenAnalyzer?.pauseAnalysis()
            }

            try {
                withContext(Dispatchers.IO) {
                    ruleRecordRepository.markRecord(record.id, true)
                }

                runtimeEventLogger.log(
                    eventType = RuntimeEventType.USER_SUBMITTED_REPAIR_NOTE,
                    sessionId = record.sessionId,
                    appPackage = packageName,
                    appDisplayName = fallbackSession?.appDisplayName,
                    payload = mapOf(
                        "record_id" to record.id,
                        "constraint_id" to record.constraintId,
                        "source_surface" to source,
                        "user_note_length" to (userNote?.trim()?.length ?: 0)
                    )
                )

                val contextInfo = resolveFalsePositiveContext(record, packageName, fallbackSession)
                val generated = confirmedRule
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { finalRule ->
                        falsePositiveRuleGenerator.saveConfirmedRule(
                            packageName = packageName,
                            record = record,
                            constraints = contextInfo.constraints,
                            scopeType = confirmedScopeType ?: AppHintScopeType.INTENT_SPECIFIC,
                            ruleText = finalRule
                        )
                    }
                    ?: falsePositiveRuleGenerator.generateAndSaveRule(
                        packageName = packageName,
                        appName = contextInfo.appName,
                        record = record,
                        constraints = contextInfo.constraints,
                        userNote = userNote
                    )

                Logger.d(
                    TAG,
                    "Processed false positive from $source for record ${record.id}, " +
                        "generatedRule=${generated.ruleText != null}, reused=${generated.reusedExistingHint}"
                )

                runtimeEventLogger.log(
                    eventType = if (generated.reusedExistingHint) {
                        RuntimeEventType.REPAIR_REUSED_EXISTING
                    } else {
                        RuntimeEventType.REPAIR_SAVED
                    },
                    sessionId = record.sessionId,
                    appPackage = packageName,
                    appDisplayName = contextInfo.appName,
                    payload = mapOf(
                        "record_id" to record.id,
                        "constraint_id" to record.constraintId,
                        "source_surface" to source,
                        "generated_rule" to generated.ruleText,
                        "reused_existing_rule" to generated.reusedExistingHint,
                        "rule_id" to generated.savedHint?.id
                    )
                )

                if (!shouldPauseJudgment) {
                    refreshActiveSessionAnalysisAfterFalsePositive(source)
                }

                buildFalsePositiveFeedbackResult(record, generated, shouldPauseJudgment)
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to process false positive record", e)
                runtimeEventLogger.log(
                    eventType = RuntimeEventType.REPAIR_FAILED,
                    sessionId = record.sessionId,
                    appPackage = packageName,
                    appDisplayName = fallbackSession?.appDisplayName,
                    payload = mapOf(
                        "record_id" to record.id,
                        "constraint_id" to record.constraintId,
                        "source_surface" to source,
                        "error_message" to e.message
                    )
                )
                FalsePositiveFeedbackResult(
                    success = false,
                    recordId = record.id,
                    userMessage = context.getString(R.string.error_record_fp_failed)
                )
            } finally {
                if (shouldPauseJudgment) {
                    isFalsePositiveLearningInProgress = false
                    val active = _activeSession.value
                    if (active != null && !active.isPaused && ApiConfig.isConfigured()) {
                        startScreenAnalysis(active.appPackageName, active.appDisplayName, active.constraints)
                    }
                }
            }
        }
    }

    private fun shouldPauseJudgmentDuringFalsePositiveLearning(
        source: String,
        activeSession: ActiveSession?
    ): Boolean {
        if (activeSession == null) return false
        return source == "floating_overlay" || source == "intervention_dialog"
    }

    private fun refreshActiveSessionAnalysisAfterFalsePositive(source: String) {
        val active = _activeSession.value ?: return
        if (active.isPaused || !ApiConfig.isConfigured()) return

        Logger.d(TAG, "Refreshing analysis after false positive from $source")
        screenAnalyzer?.pauseAnalysis()
        startScreenAnalysis(active.appPackageName, active.appDisplayName, active.constraints)
    }

    private suspend fun autoApplyCarryOverHints(
        packageName: String,
        displayName: String,
        constraints: List<SessionConstraint>
    ) {
        if (constraints.isEmpty()) return

        val existingPackageHints = appHintRepository.getHintsForPackage(packageName)
        if (existingPackageHints.isEmpty()) return

        var appliedAny = false
        constraints.forEach { constraint ->
            val intentId = buildIntentScopedHintId(constraint)
            if (appHintRepository.getHintsForIntent(packageName, intentId).isNotEmpty()) {
                return@forEach
            }
            val created = falsePositiveRuleGenerator.autoCarryOverHintsForIntent(
                packageName = packageName,
                appName = displayName,
                targetConstraint = constraint,
                existingPackageHints = existingPackageHints
            )
            if (created.isNotEmpty()) {
                appliedAny = true
                Logger.d(TAG, "Auto carried ${created.size} hints for ${constraint.description}")
            }
        }

        if (appliedAny) {
            withContext(Dispatchers.Main) {
                refreshActiveSessionAnalysisAfterFalsePositive("intent_carry_over")
            }
        }
    }

    private suspend fun resolveFalsePositiveContext(
        record: RuleRecord,
        packageName: String,
        fallbackSession: ActiveSession?
    ): FalsePositiveContext {
        val activeMatch = fallbackSession?.takeIf {
            it.sessionId == record.sessionId || it.appPackageName == packageName
        }
        if (activeMatch != null) {
            return FalsePositiveContext(
                appName = activeMatch.appDisplayName,
                constraints = activeMatch.constraints.ifEmpty {
                    fallbackConstraintsForRecord(record)
                }
            )
        }

        val sessionEntity = withContext(Dispatchers.IO) {
            repository.getSession(record.sessionId)
        }
        val storedConstraints = withContext(Dispatchers.IO) {
            repository.getConstraintsForSession(record.sessionId)
        }
        val appName = sessionEntity?.appDisplayName ?: packageName

        return FalsePositiveContext(
            appName = appName,
            constraints = storedConstraints
                .map { it.toSessionConstraint() }
                .ifEmpty { fallbackConstraintsForRecord(record) }
        )
    }

    private fun fallbackConstraintsForRecord(record: RuleRecord): List<SessionConstraint> {
        val packageName = record.packageName
            ?.takeIf { it.isNotBlank() }
            ?: record.appName

        val fromLastIntent = loadLastIntent(packageName).orEmpty()
        if (fromLastIntent.isNotEmpty()) {
            return fromLastIntent
        }

        val type = record.constraintType ?: return emptyList()
        val description = record.constraintContent?.takeIf { it.isNotBlank() } ?: return emptyList()

        return listOf(
            SessionConstraint(
                id = record.constraintId?.toString() ?: "feedback-${record.id}",
                type = type,
                description = description
            )
        )
    }

    private fun buildFalsePositiveFeedbackResult(
        record: RuleRecord,
        generated: GeneratedFalsePositiveRuleResult,
        resumedJudgment: Boolean
    ): FalsePositiveFeedbackResult {
        val message = when {
            generated.ruleText != null && generated.reusedExistingHint ->
                if (resumedJudgment) context.getString(R.string.fp_similar_rule_exists_resumed) else context.getString(R.string.fp_recorded_has_similar_rule)
            generated.ruleText != null && generated.usedUserNoteFallback ->
                if (resumedJudgment) context.getString(R.string.fp_rule_saved_resumed) else context.getString(R.string.fp_recorded_saved_rule)
            generated.ruleText != null ->
                if (resumedJudgment) context.getString(R.string.fp_rule_generated_resumed) else context.getString(R.string.fp_recorded_generated_rule)
            else ->
                if (resumedJudgment) context.getString(R.string.fp_recorded_this_resumed) else context.getString(R.string.fp_recorded)
        }

        return FalsePositiveFeedbackResult(
            success = true,
            recordId = record.id,
            generatedRule = generated.ruleText,
            reusedExistingRule = generated.reusedExistingHint,
            userMessage = message
        )
    }

    private fun persistSessionConstraints(sessionId: Long, constraints: List<SessionConstraint>) {
        scope.launch(Dispatchers.IO) {
            try {
                repository.replaceConstraintsForSession(
                    sessionId = sessionId,
                    constraints = constraints.map { it.toEntity(sessionId) }
                )
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to persist session constraints for $sessionId", e)
            }
        }
    }

    /**
     * Stop screen analysis
     */
    private fun stopScreenAnalysis() {
        screenAnalyzer?.stopAnalysis()
        actionExecutor?.clearCooldown()
        InterventionFeedbackDialogOverlay.dismiss()
        actionExecutor = null
        screenAnalyzer = null
        falsePositiveDialogCooldownUntil = 0L
        dialogReentryCooldownUntil = 0L
        resetConstraintMatchStates(emptyList())
        Logger.d(TAG, "Stopped screen analysis")
    }

    /**
     * Resume a paused session
     */
    fun resumeSession() {
        val session = _activeSession.value ?: return

        cancelPauseTimeout()
        _activeSession.value = session.copy(isPaused = false)
        startTimer()

        // Restart screen analysis
        if (ApiConfig.isConfigured()) {
            startScreenAnalysis(session.appPackageName, session.appDisplayName, session.constraints)
        }

        logRuntimeEvent(
            eventType = RuntimeEventType.SESSION_RESUMED,
            session = session,
            payload = mapOf("resume_reason" to "resume_session")
        )

        scope.launch {
            _sessionEvents.emit(SessionEvent.SessionResumed(session))
        }
    }

    /**
     * Pause the current session
     */
    fun pauseSession() {
        val session = _activeSession.value ?: return

        timerJob?.cancel()

        // Stop screen analysis while paused
        stopScreenAnalysis()

        _activeSession.value = session.copy(isPaused = true)

        logRuntimeEvent(
            eventType = RuntimeEventType.SESSION_PAUSED,
            session = session,
            payload = mapOf("pause_reason" to "pause_session")
        )

        Logger.d(TAG, ">>> pauseSession emitting SessionPaused event")
        scope.launch {
            _sessionEvents.emit(SessionEvent.SessionPaused(session))
        }
    }

    /**
     * End the current session
     */
    private suspend fun endSession(reason: SessionEndReason) {
        val session = _activeSession.value ?: return

        Logger.d(TAG, "!!! endSession called, reason=$reason, session=${session.appPackageName}")
        Logger.d(TAG, "!!! Stack trace: ${Exception("endSession trace").stackTraceToString()}")

        timerJob?.cancel()
        cancelPauseTimeout()

        // Stop screen analysis
        stopScreenAnalysis()

        // Update database
        repository.endSession(session.sessionId, reason.name)

        // Clear active session
        _activeSession.value = null
        Logger.d(TAG, "!!! Session cleared (set to null)")

        runtimeEventLogger.log(
            eventType = RuntimeEventType.SESSION_ENDED,
            sessionId = session.sessionId,
            appPackage = session.appPackageName,
            appDisplayName = session.appDisplayName,
            payload = mapOf("end_reason" to reason.name)
        )

        // Emit session ended event
        _sessionEvents.emit(SessionEvent.SessionEnded(session, reason))
    }

    /**
     * Add/modify constraints during active session
     */
    suspend fun modifyConstraints(newConstraints: List<SessionConstraint>) {
        val session = _activeSession.value ?: return

        // Merge with existing constraints
        val merged = mergeConstraints(session.constraints, newConstraints)

        // Update constraint time tracking
        val updatedTimeRemaining = session.constraintTimeRemaining.toMutableMap()
        for (constraint in merged) {
            if (constraint.timeLimitMs != null && !updatedTimeRemaining.containsKey(constraint.id)) {
                updatedTimeRemaining[constraint.id] = constraint.timeLimitMs
            }
        }

        // Update active session
        _activeSession.value = session.copy(
            constraints = merged,
            constraintTimeRemaining = updatedTimeRemaining
        )

        reconcileConstraintMatchStates(merged)
        persistSessionConstraints(session.sessionId, merged)

        // Emit event
        _sessionEvents.emit(SessionEvent.ConstraintsModified(merged))
    }

    /**
     * End session manually (user requested)
     */
    suspend fun endSessionManually() {
        endSession(SessionEndReason.USER_ENDED)
    }

    /**
     * Merge new constraints with existing ones
     */
    private fun mergeConstraints(
        existing: List<SessionConstraint>,
        new: List<SessionConstraint>
    ): List<SessionConstraint> {
        val result = existing.toMutableList()

        for (newConstraint in new) {
            val existingIndex = result.indexOfFirst { it.type == newConstraint.type }

            if (existingIndex >= 0) {
                result[existingIndex] = newConstraint
            } else {
                result.add(newConstraint)
            }
        }

        validateConstraintMutualExclusion(result)

        return result
    }

    private fun validateConstraintMutualExclusion(constraints: List<SessionConstraint>) {
        // ALLOW has been removed, so no mutual exclusion check needed
    }

    /**
     * Start the countdown timer
     */
    private fun startTimer() {
        timerJob?.cancel()

        timerJob = scope.launch {
            while (isActive) {
                delay(1000)

                val session = _activeSession.value ?: break
                if (session.isPaused) continue

                // Update time for each constraint based on its timeScope
                val updatedTimeRemaining = session.constraintTimeRemaining.toMutableMap()

                for (constraint in session.constraints) {
                    val timeLimit = constraint.timeLimitMs ?: continue
                    val remaining = updatedTimeRemaining[constraint.id] ?: continue

                    val shouldDecrement = when (constraint.timeScope) {
                        TimeScope.SESSION -> true
                        TimeScope.PER_CONTENT, TimeScope.CONTINUOUS -> {
                            constraintMatchStates[constraint.id] == true
                        }
                        TimeScope.DAILY_TOTAL -> {
                            dailyTimeTracker.resetIfNewDay(constraint.id)
                            true
                        }
                        null -> true
                    }

                    if (shouldDecrement) {
                        val newRemaining = remaining - 1000
                        updatedTimeRemaining[constraint.id] = newRemaining

                        if (constraint.timeScope == TimeScope.DAILY_TOTAL) {
                            dailyTimeTracker.addTime(constraint.id, 1000)
                        }

                        if (newRemaining <= 0) {
                            Logger.d(TAG, "Constraint timeout: ${constraint.description}")
                            actionExecutor?.executeIntervention(
                                constraint,
                                1.0,
                                "timeout",
                                session.appDisplayName,
                                session.appPackageName
                            )
                            scope.launch {
                                _sessionEvents.emit(SessionEvent.ViolationDetected(constraint, 1.0))
                            }
                        }
                    }
                }

                _activeSession.value = session.copy(constraintTimeRemaining = updatedTimeRemaining)
            }
        }
    }

    private fun resetConstraintMatchStates(constraints: List<SessionConstraint>) {
        constraintMatchStates.clear()
        constraints.forEach { constraint ->
            constraintMatchStates[constraint.id] = false
        }
        _constraintMatchStateFlow.value = constraintMatchStates.toMap()
    }

    private fun reconcileConstraintMatchStates(constraints: List<SessionConstraint>) {
        val previousStates = constraintMatchStates.toMap()
        constraintMatchStates.clear()
        constraints.forEach { constraint ->
            constraintMatchStates[constraint.id] = previousStates[constraint.id] ?: false
        }
        _constraintMatchStateFlow.value = constraintMatchStates.toMap()
    }

    /**
     * Load controlled apps from SharedPreferences
     */
    private fun loadControlledApps(): Set<String> {
        val appsString = prefs.getString(KEY_CONTROLLED_APPS, "") ?: ""
        val apps = if (appsString.isBlank()) {
            emptySet()
        } else {
            appsString.split(",").toSet()
        }
        Logger.d(TAG, "Loaded controlled apps: $apps")
        return apps
    }

    /**
     * Save controlled apps to SharedPreferences
     */
    private fun saveControlledApps(apps: Set<String>) {
        prefs.edit().putString(KEY_CONTROLLED_APPS, apps.joinToString(",")).apply()
        Logger.d(TAG, "Saved controlled apps: $apps")
    }

    /**
     * Save intent for an app. Persists as "last intent" and also appends to
     * the per-app history list (deduplicated by constraint fingerprint).
     */
    fun saveLastIntent(packageName: String, constraints: List<SessionConstraint>) {
        val json = serializeConstraints(constraints)
        prefs.edit().putString("${KEY_LAST_INTENT_PREFIX}$packageName", json).apply()
        Logger.d(TAG, "Saved last intent for $packageName: $json")

        appendToIntentHistory(packageName, constraints)
    }

    fun replaceLastIntent(packageName: String, constraints: List<SessionConstraint>) {
        if (constraints.isEmpty()) {
            prefs.edit().remove("${KEY_LAST_INTENT_PREFIX}$packageName").apply()
            Logger.d(TAG, "Cleared last intent for $packageName")
            return
        }
        val json = serializeConstraints(constraints)
        prefs.edit().putString("${KEY_LAST_INTENT_PREFIX}$packageName", json).apply()
        Logger.d(TAG, "Replaced last intent for $packageName: $json")
    }

    /**
     * Load last intent for an app
     */
    fun loadLastIntent(packageName: String): List<SessionConstraint>? {
        val json = prefs.getString("${KEY_LAST_INTENT_PREFIX}$packageName", null) ?: return null
        return deserializeConstraints(json)
    }

    /**
     * Check if an app has last intent saved
     */
    fun hasLastIntent(packageName: String): Boolean {
        return prefs.contains("${KEY_LAST_INTENT_PREFIX}$packageName")
    }

    /**
     * Load all unique historical intents for a specific app.
     * Most recent first. Each entry is a distinct rule set.
     */
    fun loadIntentHistory(packageName: String): List<List<SessionConstraint>> {
        val json = prefs.getString("${KEY_INTENT_HISTORY_PREFIX}$packageName", null)
            ?: return emptyList()
        return try {
            @Suppress("UNCHECKED_CAST")
            val outerList = gson.fromJson(json, ArrayList::class.java) as ArrayList<ArrayList<Map<String, Any>>>
            outerList.mapNotNull { entry -> deserializeConstraintList(entry) }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to load intent history for $packageName", e)
            emptyList()
        }
    }

    private fun appendToIntentHistory(packageName: String, constraints: List<SessionConstraint>) {
        val fingerprint = getConstraintFingerprint(constraints)

        val existingJson = prefs.getString("${KEY_INTENT_HISTORY_PREFIX}$packageName", null)
        val history = mutableListOf<List<Map<String, Any?>>>()

        if (existingJson != null) {
            try {
                @Suppress("UNCHECKED_CAST")
                val parsed = gson.fromJson(existingJson, ArrayList::class.java) as ArrayList<ArrayList<Map<String, Any>>>
                for (entry in parsed) {
                    val entryConstraints = deserializeConstraintList(entry)
                    if (entryConstraints != null && getConstraintFingerprint(entryConstraints) != fingerprint) {
                        history.add(entry.map { it.toMap() })
                    }
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to parse existing history", e)
            }
        }

        // Prepend the new entry (most recent first)
        val newEntry = constraints.map { constraint ->
            mapOf<String, Any?>(
                "id" to constraint.id,
                "type" to constraint.type.name,
                "description" to constraint.description,
                "timeLimitMs" to constraint.timeLimitMs,
                "timeScope" to (constraint.timeScope?.name ?: "SESSION"),
                "interventionLevel" to constraint.interventionLevel.name,
                "isActive" to constraint.isActive
            )
        }
        history.add(0, newEntry)

        // Cap at MAX_HISTORY_PER_APP
        val trimmed = history.take(MAX_HISTORY_PER_APP)
        prefs.edit().putString("${KEY_INTENT_HISTORY_PREFIX}$packageName", gson.toJson(trimmed)).apply()
        Logger.d(TAG, "Updated intent history for $packageName, now ${trimmed.size} entries")
    }

    /**
     * Save preset rules for a specific app.
     */
    fun savePresetRules(packageName: String, rules: List<SessionConstraint>) {
        val json = gson.toJson(rules.map { constraint ->
            mapOf(
                "id" to constraint.id,
                "type" to constraint.type.name,
                "description" to constraint.description,
                "timeLimitMs" to constraint.timeLimitMs,
                "timeScope" to (constraint.timeScope?.name ?: "SESSION"),
                "interventionLevel" to constraint.interventionLevel.name,
                "isActive" to constraint.isActive,
                "isDefault" to constraint.isDefault
            )
        })
        prefs.edit().putString("${KEY_PRESET_RULES_PREFIX}$packageName", json).apply()
        Logger.d(TAG, "Saved ${rules.size} preset rules for $packageName")
    }

    /**
     * Load preset rules for a specific app.
     */
    fun loadPresetRules(packageName: String): List<SessionConstraint> {
        val json = prefs.getString("${KEY_PRESET_RULES_PREFIX}$packageName", null) ?: return emptyList()
        return try {
            @Suppress("UNCHECKED_CAST")
            val list = gson.fromJson(json, ArrayList::class.java) as ArrayList<Map<String, Any>>
            list.mapNotNull { item ->
                try {
                    SessionConstraint(
                        id = item["id"] as String,
                        type = ConstraintType.valueOf(item["type"] as String),
                        description = item["description"] as String,
                        timeLimitMs = (item["timeLimitMs"] as? Number)?.toLong(),
                        timeScope = try { TimeScope.valueOf(item["timeScope"] as? String ?: "SESSION") } catch (e: Exception) { TimeScope.SESSION },
                        interventionLevel = try { InterventionLevel.valueOf(item["interventionLevel"] as? String ?: "MODERATE") } catch (e: Exception) { InterventionLevel.MODERATE },
                        isActive = item["isActive"] as? Boolean ?: true,
                        isDefault = item["isDefault"] as? Boolean ?: false
                    )
                } catch (e: Exception) {
                    Logger.e(TAG, "Failed to parse preset rule", e)
                    null
                }
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to load preset rules for $packageName", e)
            emptyList()
        }
    }

    /**
     * Set a preset rule as default for a specific app.
     * Only one rule can be default at a time.
     */
    fun setDefaultRule(packageName: String, ruleId: String) {
        val rules = loadPresetRules(packageName)
        val updatedRules = rules.map { rule ->
            rule.copy(isDefault = rule.id == ruleId)
        }
        savePresetRules(packageName, updatedRules)
        Logger.d(TAG, "Set default rule $ruleId for $packageName")
    }

    /**
     * Clear default rule for a specific app.
     */
    fun clearDefaultRule(packageName: String) {
        val rules = loadPresetRules(packageName)
        val updatedRules = rules.map { rule ->
            rule.copy(isDefault = false)
        }
        savePresetRules(packageName, updatedRules)
        Logger.d(TAG, "Cleared default rule for $packageName")
    }

    /**
     * Get the default rule for a specific app.
     */
    fun getDefaultRule(packageName: String): SessionConstraint? {
        return loadPresetRules(packageName).firstOrNull { it.isDefault }
    }

    /**
     * Save intent history (for editing).
     */
    fun saveIntentHistory(packageName: String, history: List<List<SessionConstraint>>) {
        val trimmed = history.take(MAX_HISTORY_PER_APP)
        val json = gson.toJson(trimmed.map { constraints ->
            constraints.map { constraint ->
                mapOf<String, Any?>(
                    "id" to constraint.id,
                    "type" to constraint.type.name,
                    "description" to constraint.description,
                    "timeLimitMs" to constraint.timeLimitMs,
                    "timeScope" to (constraint.timeScope?.name ?: "SESSION"),
                    "interventionLevel" to constraint.interventionLevel.name,
                    "isActive" to constraint.isActive
                )
            }
        })
        prefs.edit().putString("${KEY_INTENT_HISTORY_PREFIX}$packageName", json).apply()
        Logger.d(TAG, "Saved intent history for $packageName, ${trimmed.size} entries")
    }

    /**
     * Fingerprint for deduplication: type+description+timeLimit sorted,
     * so the same logical rule set won't appear twice.
     */
    fun getConstraintFingerprint(constraints: List<SessionConstraint>): String {
        return constraints
            .sortedBy { "${it.type}|${it.description}|${it.timeLimitMs}" }
            .joinToString(";") { "${it.type}|${it.description}|${it.timeLimitMs}" }
    }

    // --- Serialization helpers ---

    private fun serializeConstraints(constraints: List<SessionConstraint>): String {
        return gson.toJson(constraints.map { constraint ->
            mapOf(
                "id" to constraint.id,
                "type" to constraint.type.name,
                "description" to constraint.description,
                "timeLimitMs" to constraint.timeLimitMs,
                "timeScope" to (constraint.timeScope?.name ?: "SESSION"),
                "interventionLevel" to constraint.interventionLevel.name,
                "isActive" to constraint.isActive
            )
        })
    }

    @Suppress("UNCHECKED_CAST")
    private fun deserializeConstraints(json: String): List<SessionConstraint>? {
        return try {
            val list = gson.fromJson(json, ArrayList::class.java) as ArrayList<Map<String, Any>>
            deserializeConstraintList(list)
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to deserialize constraints", e)
            null
        }
    }

    private fun deserializeConstraintList(list: List<Map<String, Any>>): List<SessionConstraint>? {
        return try {
            list.mapNotNull { item ->
                try {
                    SessionConstraint(
                        id = item["id"] as String,
                        type = ConstraintType.valueOf(item["type"] as String),
                        description = item["description"] as String,
                        timeLimitMs = (item["timeLimitMs"] as? Number)?.toLong(),
                        timeScope = try { TimeScope.valueOf(item["timeScope"] as? String ?: "SESSION") } catch (e: Exception) { TimeScope.SESSION },
                        interventionLevel = try { InterventionLevel.valueOf(item["interventionLevel"] as? String ?: "MODERATE") } catch (e: Exception) { InterventionLevel.MODERATE },
                        isActive = item["isActive"] as? Boolean ?: true,
                        isDefault = item["isDefault"] as? Boolean ?: false
                    )
                } catch (e: Exception) {
                    Logger.e(TAG, "Failed to parse constraint", e)
                    null
                }
            }.ifEmpty { null }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to deserialize constraint list", e)
            null
        }
    }


    /**
     * Update controlled apps set
     */
    fun setControlledApps(apps: Set<String>) {
        _controlledApps.value = apps
        saveControlledApps(apps)
    }

    /**
     * Add app to controlled apps
     */
    fun addControlledApp(packageName: String) {
        val newApps = _controlledApps.value + packageName
        _controlledApps.value = newApps
        saveControlledApps(newApps)
        Logger.d(TAG, "Added controlled app: $packageName")
    }

    /**
     * Remove app from controlled apps
     */
    fun removeControlledApp(packageName: String) {
        val newApps = _controlledApps.value - packageName
        _controlledApps.value = newApps
        saveControlledApps(newApps)
        Logger.d(TAG, "Removed controlled app: $packageName")
    }

    fun getControlledAppsSnapshot(): Set<String> {
        return _controlledApps.value.toSet()
    }

    /**
     * Get auto-start setting
     */
    fun isAutoStartEnabled(): Boolean {
        return prefs.getBoolean(KEY_AUTO_START, false)
    }

    /**
     * Set auto-start setting
     */
    fun setAutoStartEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_START, enabled).apply()
        Logger.d(TAG, "Auto-start set to: $enabled")
    }

    /**
     * Check if an app is controlled
     */
    fun isAppControlled(packageName: String): Boolean {
        return packageName in _controlledApps.value
    }

    /**
     * Get last intent for an app
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun getLastIntentForApp(packageName: String): List<SessionConstraint>? {
        // Get last session for this app
        // For now, return null - this would query the database
        return null
    }

    /**
     * Release resources
     */
    fun release() {
        timerJob?.cancel()
        scope.cancel()
        INSTANCE = null
    }
}

/**
 * Active session state (in-memory)
 */
data class ActiveSession(
    val sessionId: Long,
    val appPackageName: String,
    val appDisplayName: String,
    val constraints: List<SessionConstraint>,
    val startTime: Long,
    val isPaused: Boolean = false,
    val constraintTimeRemaining: Map<String, Long?> = emptyMap(),
    val violationCount: Int = 0
)

private data class PendingSessionStartContext(
    val triggerSource: String,
    val switchFromPackage: String?,
    val switchToPackage: String?
)

/**
 * Session constraint (runtime version)
 */
data class SessionConstraint(
    val id: String,
    val type: ConstraintType,
    val description: String,
    val timeLimitMs: Long? = null,
    val timeScope: TimeScope? = null,
    val interventionLevel: InterventionLevel = InterventionLevel.MODERATE,
    val isActive: Boolean = true,
    val isDefault: Boolean = false
)

private fun SessionConstraint.toEntity(sessionId: Long): IntentConstraintEntity {
    return IntentConstraintEntity(
        sessionId = sessionId,
        intentId = 0L,
        type = type,
        contentPattern = description,
        timeLimitMs = timeLimitMs,
        timeScope = timeScope,
        interventionLevel = interventionLevel,
        isActive = isActive
    )
}

private fun IntentConstraintEntity.toSessionConstraint(): SessionConstraint {
    return SessionConstraint(
        id = id.toString(),
        type = type,
        description = contentPattern ?: "",
        timeLimitMs = timeLimitMs,
        timeScope = timeScope,
        interventionLevel = interventionLevel,
        isActive = isActive
    )
}

data class FalsePositiveFeedbackResult(
    val success: Boolean,
    val recordId: String? = null,
    val generatedRule: String? = null,
    val reusedExistingRule: Boolean = false,
    val userMessage: String
)

data class FalsePositiveRulePreviewResult(
    val success: Boolean,
    val generatedRule: String? = null,
    val generatedScopeType: AppHintScopeType = AppHintScopeType.INTENT_SPECIFIC,
    val generatedScopeLabel: String = "",
    val userMessage: String
)

private data class FalsePositiveContext(
    val appName: String,
    val constraints: List<SessionConstraint>
)

/**
 * Session events
 */
sealed class SessionEvent {
    data class ShowVoiceInput(val packageName: String) : SessionEvent()
    data class SessionStarted(val session: ActiveSession) : SessionEvent()
    data class SessionResumed(val session: ActiveSession) : SessionEvent()
    data class SessionPaused(val session: ActiveSession) : SessionEvent()
    data class SessionEnded(val session: ActiveSession, val reason: SessionEndReason) : SessionEvent()
    data object SessionCleared : SessionEvent()
    data class ConstraintsModified(val constraints: List<SessionConstraint>) : SessionEvent()
    data class ViolationDetected(val constraint: SessionConstraint, val confidence: Double) : SessionEvent()
}

/**
 * Session end reasons
 */
enum class SessionEndReason {
    USER_LEFT,
    USER_ENDED,
    TIMEOUT,
    VIOLATION,
    COMPLETED
}
