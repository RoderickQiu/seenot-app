package com.seenot.app.domain

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.seenot.app.ai.screen.ScreenAnalyzer
import com.seenot.app.config.ApiConfig
import com.seenot.app.data.local.SeenotDatabase
import com.seenot.app.data.local.entity.SessionEntity
import com.seenot.app.data.model.ConstraintType
import com.seenot.app.data.model.InterventionLevel
import com.seenot.app.data.model.TimeScope
import com.seenot.app.data.repository.SessionRepository
import com.seenot.app.domain.action.ActionExecutor
import com.seenot.app.service.SeenotAccessibilityService
import com.seenot.app.utils.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

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

    // Screen analyzer for AI vision
    private var screenAnalyzer: ScreenAnalyzer? = null

    // Action executor for interventions
    private var actionExecutor: ActionExecutor? = null

    // Session pause tracking for 30s recovery (used when leaving to non-controlled apps / home)
    private var sessionPausedAt: Long? = null

    // Suspended sessions from controlled-app-to-controlled-app switches
    // packageName -> (session snapshot, pausedAt timestamp)
    private val suspendedSessions = mutableMapOf<String, Pair<ActiveSession, Long>>()

    // Content match state for conditional timing
    private val constraintMatchStates = mutableMapOf<String, Boolean>()

    init {
        // Start listening for app changes
        Logger.d(TAG, "SessionManager initializing, observing app changes")
        Logger.i(TAG, "SessionManager initializing, observing app changes")
        observeAppChanges()
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

    /**
     * Handle app switch
     */
    private suspend fun handleAppChange(packageName: String) {
        val controlled = _controlledApps.value
        val currentSession = _activeSession.value

        Logger.d(TAG, "App changed to: $packageName, controlled apps: $controlled")

        if (packageName in controlled) {
            // User entered a controlled app
            Logger.d(TAG, "Entered controlled app: $packageName")
            onControlledAppEntered(packageName)
        } else {
            // User switched to a non-controlled app
            if (currentSession != null) {
                Logger.d(TAG, "Left controlled app: ${currentSession.appPackageName}")
                onControlledAppExited(currentSession.appPackageName)
            }
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
                    Logger.d(TAG, ">>> Resuming session within 30s for: $packageName")
                    resumeSession()
                    sessionPausedAt = null
                } else {
                    Logger.d(TAG, ">>> Session expired (>30s or no pausedAt), creating new session for: $packageName")
                    endSession(SessionEndReason.USER_LEFT)
                    requestNewSession(packageName)
                }
            } else {
                Logger.d(TAG, ">>> Session already active for: $packageName, ignoring")
            }
        } else if (currentSession != null && currentSession.appPackageName != packageName) {
            Logger.d(TAG, ">>> Switching from ${currentSession.appPackageName} to $packageName, suspending old session")
            suspendCurrentSession(currentSession)

            tryResumeSuspendedSession(packageName) || run {
                requestNewSession(packageName)
                false
            }
        } else if (currentSession == null) {
            if (!tryResumeSuspendedSession(packageName)) {
                Logger.d(TAG, ">>> No active or suspended session, creating new session for: $packageName")
                requestNewSession(packageName)
            }
        }
    }

    private fun suspendCurrentSession(session: ActiveSession) {
        timerJob?.cancel()
        stopScreenAnalysis()
        _activeSession.value = session.copy(isPaused = true)
        suspendedSessions[session.appPackageName] = Pair(session.copy(isPaused = true), System.currentTimeMillis())
        _activeSession.value = null
        Logger.d(TAG, ">>> Suspended session for ${session.appPackageName}")
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

    private suspend fun onControlledAppExited(@Suppress("UNUSED_PARAMETER") packageName: String) {
        if (_activeSession.value == null) {
            Logger.d(TAG, "onControlledAppExited: no active session, ignoring")
            return
        }

        pauseSession()
        sessionPausedAt = System.currentTimeMillis()
        Logger.d(TAG, ">>> Session paused at ${sessionPausedAt}, will auto-end if not resumed within 30s")
    }

    /**
     * Request a new session - triggers voice input overlay
     */
    private suspend fun requestNewSession(packageName: String) {
        // Emit event to show voice input overlay
        _sessionEvents.emit(SessionEvent.ShowVoiceInput(packageName))
    }

    /**
     * Create a new session after user declares intent
     */
    suspend fun createSession(
        packageName: String,
        displayName: String,
        constraints: List<SessionConstraint>
    ): Long {
        // Create session in database (no global time limit)
        val sessionId = repository.createSession(
            appPackageName = packageName,
            appDisplayName = displayName,
            totalTimeLimitMs = null
        )

        // Create active session with per-constraint time tracking
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

        // Initialize match states
        constraintMatchStates.clear()
        constraints.forEach { constraintMatchStates[it.id] = false }

        // Save last intent for this app
        saveLastIntent(packageName, constraints)

        // Start timer
        startTimer()

        // Start screen analysis if API is configured
        Logger.d(TAG, "=== Creating session, checking API config ===")
        Logger.i(TAG, "Creating session for $packageName, checking API config")
        Logger.d(TAG, "ApiConfig.isConfigured() = ${ApiConfig.isConfigured()}")
        Logger.d(TAG, "ApiConfig.getApiKey() = ${ApiConfig.getApiKey().take(8)}...")
        if (ApiConfig.isConfigured()) {
            Logger.d(TAG, ">>> Calling startScreenAnalysis()")
            Logger.i(TAG, "Starting screen analysis for $packageName")
            startScreenAnalysis(packageName, constraints)
            Logger.d(TAG, "<<< startScreenAnalysis() returned")
        } else {
            Logger.w(TAG, "!!! API not configured, skipping screen analysis !!!")
            Logger.w(TAG, "API not configured, skipping screen analysis")
        }

        // Emit session started event
        _sessionEvents.emit(SessionEvent.SessionStarted(activeSession))

        return sessionId
    }

    /**
     * Start screen analysis for the current session
     */
    @Suppress("UNUSED_PARAMETER")
    private fun startScreenAnalysis(packageName: String, constraints: List<SessionConstraint>) {
        if (screenAnalyzer == null) {
            screenAnalyzer = ScreenAnalyzer(context)
        }
        if (actionExecutor == null) {
            actionExecutor = ActionExecutor(context)
        }

        // Start periodic analysis
        screenAnalyzer?.startAnalysis(
            packageName = packageName,
            constraints = constraints,
            onViolation = { constraint, confidence ->
                handleViolation(constraint, confidence)
            }
        )

        Logger.d(TAG, "Started screen analysis for session")
    }

    /**
     * Handle violation detected by screen analyzer
     */
    private fun handleViolation(constraint: SessionConstraint, confidence: Double) {
        Logger.d(TAG, "Violation detected: ${constraint.description}, confidence: $confidence")
        Logger.w(TAG, "Violation detected: ${constraint.description}, confidence: $confidence")

        // Update session state with violation
        val currentSession = _activeSession.value ?: return
        _activeSession.value = currentSession.copy(
            violationCount = currentSession.violationCount + 1
        )

        // Execute intervention based on confidence
        actionExecutor?.executeIntervention(
            constraint,
            confidence,
            "violation",
            currentSession.appDisplayName,
            currentSession.appPackageName
        )

        // Emit violation event
        scope.launch {
            _sessionEvents.emit(SessionEvent.ViolationDetected(constraint, confidence))
        }
    }

    /**
     * Update content match state from screen analyzer
     * Used for conditional timing (PER_CONTENT constraints)
     */
    fun updateContentMatchState(constraintId: String, isMatching: Boolean) {
        constraintMatchStates[constraintId] = isMatching
        Logger.d(TAG, "Content match state updated: $constraintId = $isMatching")
    }

    /**
     * Stop screen analysis
     */
    private fun stopScreenAnalysis() {
        screenAnalyzer?.stopAnalysis()
        actionExecutor?.clearCooldown()
        actionExecutor = null
        screenAnalyzer = null
        Logger.d(TAG, "Stopped screen analysis")
    }

    /**
     * Resume a paused session
     */
    fun resumeSession() {
        val session = _activeSession.value ?: return

        _activeSession.value = session.copy(isPaused = false)
        startTimer()

        // Restart screen analysis
        if (ApiConfig.isConfigured()) {
            startScreenAnalysis(session.appPackageName, session.constraints)
        }

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

        // Stop screen analysis
        stopScreenAnalysis()

        // Update database
        repository.endSession(session.sessionId, reason.name)

        // Clear active session
        _activeSession.value = null
        Logger.d(TAG, "!!! Session cleared (set to null)")

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
        val hasAllow = constraints.any { it.type == ConstraintType.ALLOW }
        val hasDeny = constraints.any { it.type == ConstraintType.DENY }

        if (hasAllow && hasDeny) {
            Logger.w(TAG, "Constraint validation warning: Both ALLOW and DENY constraints present. Latest intent takes precedence.")
        }
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
        val fingerprint = constraintFingerprint(constraints)

        val existingJson = prefs.getString("${KEY_INTENT_HISTORY_PREFIX}$packageName", null)
        val history = mutableListOf<List<Map<String, Any?>>>()

        if (existingJson != null) {
            try {
                @Suppress("UNCHECKED_CAST")
                val parsed = gson.fromJson(existingJson, ArrayList::class.java) as ArrayList<ArrayList<Map<String, Any>>>
                for (entry in parsed) {
                    val entryConstraints = deserializeConstraintList(entry)
                    if (entryConstraints != null && constraintFingerprint(entryConstraints) != fingerprint) {
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

    private fun constraintFingerprint(constraints: List<SessionConstraint>): String {
        return getConstraintFingerprint(constraints)
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

/**
 * Session events
 */
sealed class SessionEvent {
    data class ShowVoiceInput(val packageName: String) : SessionEvent()
    data class SessionStarted(val session: ActiveSession) : SessionEvent()
    data class SessionResumed(val session: ActiveSession) : SessionEvent()
    data class SessionPaused(val session: ActiveSession) : SessionEvent()
    data class SessionEnded(val session: ActiveSession, val reason: SessionEndReason) : SessionEvent()
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

