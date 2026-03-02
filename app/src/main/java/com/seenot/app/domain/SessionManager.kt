package com.seenot.app.domain

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
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

    // SharedPreferences for persisting controlled apps
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Gson for JSON serialization
    private val gson = Gson()

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

    init {
        // Start listening for app changes
        Log.d(TAG, "SessionManager initializing, observing app changes")
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

        Log.d(TAG, "App changed to: $packageName, controlled apps: $controlled")

        if (packageName in controlled) {
            // User entered a controlled app
            Log.d(TAG, "Entered controlled app: $packageName")
            onControlledAppEntered(packageName)
        } else {
            // User left a controlled app
            val currentSession = _activeSession.value
            if (currentSession != null && currentSession.appPackageName == packageName) {
                Log.d(TAG, "Left controlled app: $packageName")
                onControlledAppExited(packageName)
            }
        }
    }

    /**
     * Called when user enters a controlled app
     */
    private suspend fun onControlledAppEntered(packageName: String) {
        val currentSession = _activeSession.value

        if (currentSession != null && currentSession.appPackageName == packageName) {
            // User returned to the same app - resume session
            Log.d(TAG, "Resuming session for: $packageName")
            resumeSession()
        } else if (currentSession == null) {
            // No active session - create new one
            Log.d(TAG, "Creating new session for: $packageName")
            requestNewSession(packageName)
        }
    }

    /**
     * Called when user leaves a controlled app
     */
    private suspend fun onControlledAppExited(@Suppress("UNUSED_PARAMETER") packageName: String) {
        if (_activeSession.value == null) return

        // End the current session
        endSession(SessionEndReason.USER_LEFT)
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
        // Calculate total time limit from constraints
        val totalTimeLimit = constraints
            .filter { it.type == ConstraintType.TIME_CAP }
            .firstOrNull()
            ?.timeLimitMs

        // Create session in database
        val sessionId = repository.createSession(
            appPackageName = packageName,
            appDisplayName = displayName,
            totalTimeLimitMs = totalTimeLimit
        )

        // Create active session
        val activeSession = ActiveSession(
            sessionId = sessionId,
            appPackageName = packageName,
            appDisplayName = displayName,
            constraints = constraints,
            startTime = System.currentTimeMillis(),
            isPaused = false,
            timeRemainingMs = totalTimeLimit
        )

        _activeSession.value = activeSession

        // Save last intent for this app (for "continue last intent" feature)
        saveLastIntent(packageName, constraints)

        // Start timer
        startTimer()

        // Start screen analysis if API is configured
        Log.d(TAG, "=== Creating session, checking API config ===")
        Log.d(TAG, "ApiConfig.isConfigured() = ${ApiConfig.isConfigured()}")
        Log.d(TAG, "ApiConfig.getApiKey() = ${ApiConfig.getApiKey().take(8)}...")
        if (ApiConfig.isConfigured()) {
            Log.d(TAG, ">>> Calling startScreenAnalysis()")
            startScreenAnalysis(packageName, constraints)
            Log.d(TAG, "<<< startScreenAnalysis() returned")
        } else {
            Log.w(TAG, "!!! API not configured, skipping screen analysis !!!")
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

        Log.d(TAG, "Started screen analysis for session")
    }

    /**
     * Handle violation detected by screen analyzer
     */
    private fun handleViolation(constraint: SessionConstraint, confidence: Double) {
        Log.d(TAG, "Violation detected: ${constraint.description}, confidence: $confidence")

        // Update session state with violation
        val currentSession = _activeSession.value ?: return
        _activeSession.value = currentSession.copy(
            violationCount = currentSession.violationCount + 1
        )

        // Execute intervention based on confidence
        actionExecutor?.executeIntervention(constraint, confidence)

        // Emit violation event
        scope.launch {
            _sessionEvents.emit(SessionEvent.ViolationDetected(constraint, confidence))
        }
    }

    /**
     * Stop screen analysis
     */
    private fun stopScreenAnalysis() {
        screenAnalyzer?.stopAnalysis()
        actionExecutor?.clearCooldown()
        actionExecutor = null
        screenAnalyzer = null
        Log.d(TAG, "Stopped screen analysis")
    }

    /**
     * Resume a paused session
     */
    fun resumeSession() {
        val session = _activeSession.value ?: return

        _activeSession.value = session.copy(isPaused = false)
        startTimer()

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

        timerJob?.cancel()

        // Stop screen analysis
        stopScreenAnalysis()

        // Update database
        repository.endSession(session.sessionId, reason.name)

        // Clear active session
        _activeSession.value = null

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

        // Update active session
        _activeSession.value = session.copy(constraints = merged)

        // Recalculate time limit
        val totalTimeLimit = merged
            .filter { it.type == ConstraintType.TIME_CAP }
            .firstOrNull()
            ?.timeLimitMs

        _activeSession.value = session.copy(
            constraints = merged,
            timeRemainingMs = totalTimeLimit
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
                // Replace existing constraint of same type
                result[existingIndex] = newConstraint
            } else {
                // Add new constraint
                result.add(newConstraint)
            }
        }

        return result
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

                val remaining = session.timeRemainingMs?.let { it - 1000 }

                if (remaining != null && remaining <= 0) {
                    // Time's up!
                    _activeSession.value = session.copy(timeRemainingMs = 0)
                    endSession(SessionEndReason.TIMEOUT)
                    break
                }

                _activeSession.value = session.copy(timeRemainingMs = remaining)
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
        Log.d(TAG, "Loaded controlled apps: $apps")
        return apps
    }

    /**
     * Save controlled apps to SharedPreferences
     */
    private fun saveControlledApps(apps: Set<String>) {
        prefs.edit().putString(KEY_CONTROLLED_APPS, apps.joinToString(",")).apply()
        Log.d(TAG, "Saved controlled apps: $apps")
    }

    /**
     * Save intent for an app. Persists as "last intent" and also appends to
     * the per-app history list (deduplicated by constraint fingerprint).
     */
    fun saveLastIntent(packageName: String, constraints: List<SessionConstraint>) {
        val json = serializeConstraints(constraints)
        prefs.edit().putString("${KEY_LAST_INTENT_PREFIX}$packageName", json).apply()
        Log.d(TAG, "Saved last intent for $packageName: $json")

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
            Log.e(TAG, "Failed to load intent history for $packageName", e)
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
                Log.e(TAG, "Failed to parse existing history", e)
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
        Log.d(TAG, "Updated intent history for $packageName, now ${trimmed.size} entries")
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
                "isActive" to constraint.isActive
            )
        })
        prefs.edit().putString("${KEY_PRESET_RULES_PREFIX}$packageName", json).apply()
        Log.d(TAG, "Saved ${rules.size} preset rules for $packageName")
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
                        isActive = item["isActive"] as? Boolean ?: true
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse preset rule", e)
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load preset rules for $packageName", e)
            emptyList()
        }
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
        Log.d(TAG, "Saved intent history for $packageName, ${trimmed.size} entries")
    }

    /**
     * Fingerprint for deduplication: type+description+timeLimit sorted,
     * so the same logical rule set won't appear twice.
     */
    private fun constraintFingerprint(constraints: List<SessionConstraint>): String {
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
            Log.e(TAG, "Failed to deserialize constraints", e)
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
                        isActive = item["isActive"] as? Boolean ?: true
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse constraint", e)
                    null
                }
            }.ifEmpty { null }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deserialize constraint list", e)
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
        Log.d(TAG, "Added controlled app: $packageName")
    }

    /**
     * Remove app from controlled apps
     */
    fun removeControlledApp(packageName: String) {
        val newApps = _controlledApps.value - packageName
        _controlledApps.value = newApps
        saveControlledApps(newApps)
        Log.d(TAG, "Removed controlled app: $packageName")
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
        Log.d(TAG, "Auto-start set to: $enabled")
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
    val timeRemainingMs: Long? = null,
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
    val isActive: Boolean = true
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

