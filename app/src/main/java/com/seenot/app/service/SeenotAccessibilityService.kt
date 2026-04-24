package com.seenot.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Path
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.seenot.app.utils.Logger
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import com.seenot.app.MainActivity
import com.seenot.app.R
import com.seenot.app.config.IntentReminderPrefs
import com.seenot.app.domain.SessionManager
import com.seenot.app.observability.RuntimeEventLogger
import com.seenot.app.observability.RuntimeEventType
import com.seenot.app.ui.overlay.FloatingIndicatorOverlay
import com.seenot.app.ui.overlay.IntentInputDialogOverlay
import com.seenot.app.ui.overlay.IntentReminderOverlay
import com.seenot.app.ui.overlay.JudgmentFeedbackConfirmOverlay
import com.seenot.app.ui.overlay.VoiceInputOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * AccessibilityService for SeeNot app.
 * Handles app switch detection, screenshots, and gesture execution.
 * Runs as a foreground service to prevent being killed by the system.
 */
class SeenotAccessibilityService : AccessibilityService() {
    companion object {
        private const val TAG = "SeenotAccessibility"
        private const val CHANNEL_ID = "seenot_accessibility"
        private const val NOTIFICATION_ID = 1001
        private const val APP_SWITCH_DEBOUNCE_MS = 500L
        private const val CONTENT_CHANGED_DEBOUNCE_MS = 1200L
        private const val UNLOCK_FOREGROUND_GUARD_MS = 1500L
        private const val OVERLAY_WATCHDOG_MISSING_MS = 1500L
        private const val OVERLAY_WATCHDOG_STREAK_WINDOW_MS = 1200L
        private const val OVERLAY_WATCHDOG_MIN_EVENTS = 3
        private const val OVERLAY_WATCHDOG_RECOVERY_COOLDOWN_MS = 5000L

        var instance: SeenotAccessibilityService? = null

        private val _currentPackage = MutableStateFlow<String?>(null)
        val currentPackage: StateFlow<String?> = _currentPackage.asStateFlow()

        private val _isServiceReady = MutableStateFlow(false)
        val isServiceReady: StateFlow<Boolean> = _isServiceReady.asStateFlow()

        private val _deviceState = MutableStateFlow(AccessibilityDeviceState())
        val deviceState: StateFlow<AccessibilityDeviceState> = _deviceState.asStateFlow()

    // Track current monitored app to prevent indicator flickering
    private var currentMonitoredPackage: String? = null
    
    // Track if session is being created to prevent race condition
    @Volatile
    private var isSessionBeingCreated = false
    
    // Track last exited package and time to handle false positives (e.g., swipe back triggering短暂切换)
    private var lastExitedPackage: String? = null
    private var lastExitedTime: Long = 0L
    private const val QUICK_RETURN_THRESHOLD_MS = 1000L  // Reduced from 5s to 1s
    }

    private val gestureExecutor: Executor = Executors.newSingleThreadExecutor()
    private var lastAppSwitchTime = 0L
    private var lastContentChangedPackage: String? = null
    private var lastContentChangedClassName: String? = null
    private var lastContentChangedHandledAt: Long = 0L
    private var lastUserPresentAt: Long = 0L
    private var overlayMissingCandidatePackage: String? = null
    private var overlayMissingCandidateFirstAt: Long = 0L
    private var overlayMissingCandidateLastAt: Long = 0L
    private var overlayMissingCandidateEventCount: Int = 0
    private var lastOverlayRecoveryAt: Long = 0L
    private lateinit var sessionManager: SessionManager
    private var deviceStateReceiverRegistered = false
    private val runtimeEventLogger by lazy { RuntimeEventLogger.getInstance(this) }
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingIntentReminderPackage: String? = null
    private var pendingIntentReminderAppName: String? = null
    private var pendingIntentReminderCount: Int = 0
    private val pendingIntentReminderRunnable = Runnable {
        val packageName = pendingIntentReminderPackage ?: return@Runnable
        val appName = pendingIntentReminderAppName ?: packageName
        val sessionManager = SessionManager.getInstance(this)
        if (!shouldShowIntentReminder(packageName, sessionManager)) {
            return@Runnable
        }
        pendingIntentReminderCount += 1
        runtimeEventLogger.log(
            eventType = RuntimeEventType.INTENT_REMINDER_SHOWN,
            sessionId = null,
            appPackage = packageName,
            appDisplayName = appName,
            payload = mapOf(
                "delay_ms" to IntentReminderPrefs.getDelayMs(this),
                "reminder_count" to pendingIntentReminderCount
            )
        )
        IntentReminderOverlay.show(
            context = this,
            appName = appName,
            onSetIntentNow = {
                runtimeEventLogger.log(
                    eventType = RuntimeEventType.INTENT_REMINDER_SET_NOW,
                    sessionId = null,
                    appPackage = packageName,
                    appDisplayName = appName,
                    payload = mapOf("reminder_count" to pendingIntentReminderCount)
                )
                cancelPendingIntentReminder()
                showIntentInputDialog(
                    packageName = packageName,
                    appName = appName,
                    sessionManager = sessionManager,
                    allowDefaultRuleAutoApply = false
                )
            },
            onLater = {
                runtimeEventLogger.log(
                    eventType = RuntimeEventType.INTENT_REMINDER_DEFERRED,
                    sessionId = null,
                    appPackage = packageName,
                    appDisplayName = appName,
                    payload = mapOf("reminder_count" to pendingIntentReminderCount)
                )
                scheduleIntentReminder(packageName, appName)
            }
        )
    }
    
    // Service-level coroutine scope to prevent leaks
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val deviceStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Logger.d(TAG, "Device state broadcast received: ${intent?.action}")
            if (intent?.action == Intent.ACTION_USER_PRESENT) {
                lastUserPresentAt = System.currentTimeMillis()
            }
            updateDeviceState()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            instance = this
            _isServiceReady.value = true
            updateDeviceState()
            registerDeviceStateReceiver()
            Logger.d(TAG, "AccessibilityService connected")

            // Start foreground service to prevent being killed
            try {
                startForegroundService()
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to start foreground service", e)
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Error in onServiceConnected", e)
        }
    }

    private fun registerDeviceStateReceiver() {
        if (deviceStateReceiverRegistered) return

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }

        registerReceiver(deviceStateReceiver, filter)
        deviceStateReceiverRegistered = true
        Logger.d(TAG, "Registered device state receiver")
    }

    private fun unregisterDeviceStateReceiver() {
        if (!deviceStateReceiverRegistered) return

        try {
            unregisterReceiver(deviceStateReceiver)
            Logger.d(TAG, "Unregistered device state receiver")
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to unregister device state receiver: ${e.message}")
        } finally {
            deviceStateReceiverRegistered = false
        }
    }

    private fun updateDeviceState() {
        _deviceState.value = readDeviceState()
    }

    private fun readDeviceState(): AccessibilityDeviceState {
        val powerManager = getSystemService(PowerManager::class.java)
        val keyguardManager = getSystemService(KeyguardManager::class.java)
        val displayManager = getSystemService(DisplayManager::class.java)
        val displayState = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)?.state

        val isInteractive = powerManager?.isInteractive == true
        val isDeviceLocked = keyguardManager?.isDeviceLocked ?: true
        val isKeyguardLocked = keyguardManager?.isKeyguardLocked ?: true
        val shouldAnalyze =
            isInteractive &&
                !isDeviceLocked &&
                !isKeyguardLocked &&
                displayState != null &&
                displayState != Display.STATE_OFF

        return AccessibilityDeviceState(
            shouldAnalyze = shouldAnalyze,
            isInteractive = isInteractive,
            isDeviceLocked = isDeviceLocked,
            isKeyguardLocked = isKeyguardLocked,
            displayState = displayState,
            changedAt = System.currentTimeMillis()
        )
    }

    /**
     * Start foreground service with notification
     */
    private fun startForegroundService() {
        try {
            createNotificationChannel()
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to create notification channel", e)
            return
        }

        val intent = Intent(this, MainActivity::class.java)
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, pendingFlags)

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.fg_notification_title))
            .setContentText(getString(R.string.fg_notification_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()

        try {
            startForeground(NOTIFICATION_ID, notification)
            Logger.d(TAG, "Foreground notification started")
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to start foreground", e)
        }
    }

    /**
     * Create notification channel for foreground service
     */
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.fg_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setBypassDnd(true)
            description = getString(R.string.fg_channel_desc)
            setShowBadge(false)
        }

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            if (event == null) {
                return
            }

            // logAccessibilityEventForTest(event)
            maybeRecoverPausedSessionFromEvent(event)
            maybeRecoverMissingOverlayFromEvent(event)

            val eventType = event.eventType
            if (
                eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                    eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            ) {
                return
            }

            val packageName = event.packageName?.toString()
            val className = event.className?.toString()

            if (packageName == null) return

            if (packageName == this.packageName) {
                Logger.d(TAG, "Ignoring SeeNot package window event before foreground tracking: $packageName")
                return
            }

            val sessionManager = SessionManager.getInstance(this)
            val now = System.currentTimeMillis()
            if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                if (!shouldConsiderContentChanged(packageName, sessionManager)) {
                    return
                }
                if (!shouldProcessContentChanged(packageName, className, now)) {
                    return
                }
            } else {
                resetContentChangedDebounce()
                if (now - lastAppSwitchTime < APP_SWITCH_DEBOUNCE_MS) {
                    Logger.d(TAG, "Debouncing app switch: $packageName (${now - lastAppSwitchTime}ms)")
                    return
                }
            }

            // Ignore system apps (except launcher)
            if (isSystemApp(packageName) && !isLauncher(packageName)) {
                Logger.d(TAG, "Ignoring system app: $packageName")
                return
            }

            if (packageName != _currentPackage.value) {
                // Only publish foreground-package changes once the window looks like
                // a real app/launcher surface; otherwise transient frame/layout events
                // can incorrectly resume sessions while the user is still on desktop.
                val isLauncher = isLauncher(packageName)
                val isCapable = isCapableClass(className)
                val currentPackage = _currentPackage.value
                val isEnteringControlledApp = sessionManager.isAppMonitoringEnabled(packageName)
                val isLeavingControlledContext = sessionManager.isAppMonitoringEnabled(currentPackage)
                val shouldGuardUnlockForeground =
                    isWithinUnlockForegroundGuard(now) &&
                        isEnteringControlledApp &&
                        !isCapable &&
                        !isLauncher &&
                        !isLeavingControlledContext
                val shouldTrackForeground =
                    if (shouldGuardUnlockForeground) {
                        false
                    } else {
                        isCapable ||
                            isLauncher ||
                            isEnteringControlledApp ||
                            isLeavingControlledContext
                    }

                if (shouldTrackForeground) {
                    Logger.d(
                        TAG,
                        "Foreground event accepted: package=$packageName, " +
                            "event=${AccessibilityEvent.eventTypeToString(eventType)}"
                    )
                    lastAppSwitchTime = now
                    _currentPackage.value = packageName
                    checkAndShowOverlay(packageName, className)
                } else {
                    Logger.d(
                        TAG,
                        "Not tracking package update for $packageName, class=$className, " +
                            "isCapable=$isCapable, isLauncher=$isLauncher, " +
                            "isEnteringControlledApp=$isEnteringControlledApp, " +
                            "isLeavingControlledContext=$isLeavingControlledContext, " +
                            "unlockGuard=$shouldGuardUnlockForeground"
                    )
                }
            } else {
                maybeRecoverWeakReentry(packageName, className, eventType)
                Logger.d(TAG, "Same package, skipping: $packageName")
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Error in onAccessibilityEvent", e)
        }
    }

    private fun maybeRecoverPausedSessionFromEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        if (packageName == this.packageName) return

        val eventType = event.eventType
        if (
            eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            return
        }

        if (isSystemApp(packageName) && !isLauncher(packageName)) {
            return
        }

        val sessionManager = SessionManager.getInstance(this)
        val resumed = sessionManager.resumePausedSessionFromAccessibilityEvent(
            packageName = packageName,
            eventType = event.eventType,
            eventSourceClassName = event.className?.toString()
        )
        if (!resumed) return

        Logger.d(
            TAG,
            "Recovered paused session from event stream: " +
                "package=$packageName, event=${AccessibilityEvent.eventTypeToString(event.eventType)}"
        )
        _currentPackage.value = packageName
        currentMonitoredPackage = packageName
        lastExitedPackage = null
        lastExitedTime = 0L
        cancelPendingIntentReminder()
    }

    private fun maybeRecoverMissingOverlayFromEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        if (packageName == this.packageName) return

        val eventType = event.eventType
        if (
            eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            return
        }

        if (isSystemApp(packageName) && !isLauncher(packageName)) return

        val sessionManager = SessionManager.getInstance(this)
        if (!sessionManager.isAppMonitoringEnabled(packageName)) {
            cancelPendingIntentReminder(packageName)
            resetOverlayMissingCandidate(packageName)
            return
        }

        if (isAnySessionOverlayShowing()) {
            resetOverlayMissingCandidate(packageName)
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastOverlayRecoveryAt < OVERLAY_WATCHDOG_RECOVERY_COOLDOWN_MS) {
            return
        }

        if (
            overlayMissingCandidatePackage != packageName ||
                now - overlayMissingCandidateLastAt > OVERLAY_WATCHDOG_STREAK_WINDOW_MS
        ) {
            overlayMissingCandidatePackage = packageName
            overlayMissingCandidateFirstAt = now
            overlayMissingCandidateLastAt = now
            overlayMissingCandidateEventCount = 1
            return
        }

        overlayMissingCandidateLastAt = now
        overlayMissingCandidateEventCount += 1

        val missingDuration = now - overlayMissingCandidateFirstAt
        if (
            overlayMissingCandidateEventCount >= OVERLAY_WATCHDOG_MIN_EVENTS &&
                missingDuration >= OVERLAY_WATCHDOG_MISSING_MS
        ) {
            Logger.w(
                TAG,
                "Overlay watchdog recovering missing overlay for $packageName " +
                    "(events=$overlayMissingCandidateEventCount, missingMs=$missingDuration)"
            )
            lastOverlayRecoveryAt = now
            forceRestartOverlayForCurrentApp(packageName)
            resetOverlayMissingCandidate(packageName)
        }
    }

    private fun shouldConsiderContentChanged(
        packageName: String,
        sessionManager: SessionManager
    ): Boolean {
        if (isLauncher(packageName)) {
            return false
        }

        val activeSession = sessionManager.activeSession.value
        if (activeSession?.appPackageName == packageName) {
            return true
        }

        if (sessionManager.hasResumableSession(packageName)) {
            return true
        }

        if (sessionManager.isAppMonitoringEnabled(packageName)) {
            return true
        }

        return false
    }

    private fun shouldProcessContentChanged(
        packageName: String,
        className: String?,
        now: Long
    ): Boolean {
        val samePackage = lastContentChangedPackage == packageName
        val sameClass = lastContentChangedClassName == className
        val withinDebounceWindow = now - lastContentChangedHandledAt < CONTENT_CHANGED_DEBOUNCE_MS

        if (samePackage && sameClass && withinDebounceWindow) {
            Logger.d(
                TAG,
                "Debouncing content change: package=$packageName, class=$className, " +
                    "delta=${now - lastContentChangedHandledAt}ms"
            )
            return false
        }

        lastContentChangedPackage = packageName
        lastContentChangedClassName = className
        lastContentChangedHandledAt = now
        return true
    }

    private fun resetContentChangedDebounce() {
        lastContentChangedPackage = null
        lastContentChangedClassName = null
        lastContentChangedHandledAt = 0L
    }

    private fun maybeRecoverWeakReentry(
        packageName: String,
        className: String?,
        eventType: Int
    ) {
        if (currentMonitoredPackage == packageName) {
            return
        }
        if (isSessionBeingCreated || isAnySessionOverlayShowing()) {
            return
        }

        val sessionManager = SessionManager.getInstance(this)
        if (!sessionManager.isAppMonitoringEnabled(packageName)) {
            return
        }

        val activeSession = sessionManager.activeSession.value
        val hasSessionContext =
            (activeSession?.appPackageName == packageName) || sessionManager.hasResumableSession(packageName)

        if (isWithinUnlockForegroundGuard(System.currentTimeMillis()) && !hasSessionContext) {
            return
        }

        if (!hasSessionContext && eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            return
        }

        Logger.d(
            TAG,
            "Recovering weak re-entry for $packageName via " +
                "${AccessibilityEvent.eventTypeToString(eventType)}"
        )
        checkAndShowOverlay(packageName, className)
    }

    private fun isWithinUnlockForegroundGuard(now: Long): Boolean {
        return lastUserPresentAt != 0L && now - lastUserPresentAt < UNLOCK_FOREGROUND_GUARD_MS
    }

    private fun isAnySessionOverlayShowing(): Boolean {
        return FloatingIndicatorOverlay.isShowing() || IntentInputDialogOverlay.isShowing()
    }

    private fun resetOverlayMissingCandidate(packageName: String? = null) {
        if (packageName != null && overlayMissingCandidatePackage != packageName) {
            return
        }
        overlayMissingCandidatePackage = null
        overlayMissingCandidateFirstAt = 0L
        overlayMissingCandidateLastAt = 0L
        overlayMissingCandidateEventCount = 0
    }

    private fun logAccessibilityEventForTest(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString()
        val className = event.className?.toString()
        val eventName = AccessibilityEvent.eventTypeToString(event.eventType)
        val contentChangeTypes =
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                event.contentChangeTypes
            } else {
                0
            }

        Logger.d(
            TAG,
            "[TEST][A11Y] event=$eventName, package=$packageName, class=$className, " +
                "current=${_currentPackage.value}, contentChangeTypes=$contentChangeTypes, " +
                "windowId=${event.windowId}"
        )
    }

    // Track last class name for capable class check
    private var lastClassName: String? = null
    private var lastClassCapable: Boolean = false

    /**
     * Check if a class is a "capable" app class (not system UI)
     * Based on seenot-reborn's isCapableClass logic
     */
    private fun isCapableClass(className: String?): Boolean {
        if (className == null) return false
        if (className == lastClassName) return lastClassCapable

        val capable = if (className.contains("Activity")) {
            true
        } else {
            !className.startsWith("android.widget.") &&
                !className.startsWith("android.view.") &&
                !className.startsWith("androidx.") &&
                !className.startsWith("com.android.systemui") &&
                !className.startsWith("android.app") &&
                !className.startsWith("android.inputmethodservice")
        }

        lastClassName = className
        lastClassCapable = capable
        return capable
    }

    /**
     * Check if the package is a controlled app and show the floating overlay
     * Only show/dismiss on state transitions to prevent flickering
     */
    private fun checkAndShowOverlay(packageName: String, className: String? = null) {
        // Ignore empty or null package names - these are likely transient system events
        if (packageName.isNullOrBlank()) {
            Logger.d(TAG, "Empty package name, skipping")
            return
        }

        // Ignore SeeNot's own package to prevent overlay dismissal
        if (packageName == this.packageName) {
            Logger.d(TAG, "Own package $packageName, skipping")
            return
        }

        try {
            // Get controlled apps from SessionManager
            val sessionManager = SessionManager.getInstance(this)
            val isControlledApp = sessionManager.isAppMonitoringEnabled(packageName)
            Logger.d(TAG, "isControlledApp: $isControlledApp for $packageName")

            // Get previous monitored package
            val previousPackage = currentMonitoredPackage
            val wasInMonitoredApp = previousPackage != null

            // Key fix: Only dismiss if we're actually switching to a DIFFERENT package
            // Don't dismiss just because packageName temporarily becomes non-controlled
            // (this can happen during navigation within the same app)
            val isSwitchingToDifferentApp = wasInMonitoredApp && previousPackage != packageName

            when {
                // Switch from one monitored app to another
                isSwitchingToDifferentApp && isControlledApp -> {
                    Logger.d(TAG, "Case: Switching monitored app: $previousPackage -> $packageName")
                    cancelPendingIntentReminder()
                    dismissAllOverlays()
                    showIndicatorAndOverlay(packageName)
                }
                // Switch from monitored app to non-monitored app
                isSwitchingToDifferentApp && !isControlledApp -> {
                    Logger.d(TAG, "Case: Leaving monitored app: $previousPackage -> $packageName (non-controlled)")
                    lastExitedPackage = previousPackage
                    lastExitedTime = System.currentTimeMillis()
                    currentMonitoredPackage = null
                    cancelPendingIntentReminder()
                }
                // Enter monitored app from non-monitored
                !wasInMonitoredApp && isControlledApp -> {
                    Logger.d(TAG, "Case: Entering monitored app: $packageName")
                    
                    val timeSinceExit = System.currentTimeMillis() - lastExitedTime
                    val quickReturn = lastExitedPackage == packageName && 
                        timeSinceExit < QUICK_RETURN_THRESHOLD_MS
                    
                    Logger.d(TAG, "wasInMonitoredApp=$wasInMonitoredApp, lastExitedPackage=$lastExitedPackage, timeSinceExit=${timeSinceExit}ms, quickReturn=$quickReturn")
                    
                    currentMonitoredPackage = packageName
                    
                    if (quickReturn) {
                        val hasResumableSession = sessionManager.hasResumableSession(packageName)

                        if (hasResumableSession) {
                            Logger.d(TAG, "Quick return to $packageName with resumable session - swipe back, no overlay changes")
                            lastExitedPackage = null
                            lastExitedTime = 0L
                            return
                        } else {
                            Logger.d(TAG, "Quick return to $packageName but no session - showing overlay")
                            // Fall through to show overlay
                        }
                    }
                    
                    dismissAllOverlays()
                    lastExitedPackage = null
                    lastExitedTime = 0L
                    cancelPendingIntentReminder()
                    
                    showIndicatorAndOverlay(packageName, isQuickReturn = false)
                }
                // Already in same monitored app - do nothing
                wasInMonitoredApp && previousPackage == packageName -> {
                    Logger.d(TAG, "Case: Same package $packageName, skipping")
                }
                // Switching between non-monitored apps - clean up stale state
                !wasInMonitoredApp && !isControlledApp -> {
                    Logger.d(TAG, "Case: Non-monitored to non-monitored: $packageName")
                    if (lastExitedPackage != null && (System.currentTimeMillis() - lastExitedTime) >= QUICK_RETURN_THRESHOLD_MS) {
                        Logger.d(TAG, "Stale exit cleared")
                        lastExitedPackage = null
                        lastExitedTime = 0L
                        cancelPendingIntentReminder()
                        dismissAllOverlays()
                    }
                }
                else -> {
                    Logger.d(TAG, "Case: No state change relevant for $packageName")
                }
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Error in checkAndShowOverlay", e)
        }
    }

    private fun isSystemApp(packageName: String): Boolean {
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun isLauncher(packageName: String): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
        }
        val resolveInfo = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return resolveInfo?.activityInfo?.packageName == packageName
    }

    /**
     * Show the overlay flow for a controlled app.
     *
     * If there's already an active session, show the compact indicator directly.
     * Otherwise, show the intent input dialog first, then transition to the indicator
     * after the user confirms rules.
     */
    private fun showIndicatorAndOverlay(
        packageName: String,
        isQuickReturn: Boolean = false,
        showLeadingIndicator: Boolean = true
    ) {
        currentMonitoredPackage = packageName
        cancelPendingIntentReminder(packageName)

        Logger.d(
            TAG,
            "showIndicatorAndOverlay called for: $packageName, " +
                "isQuickReturn=$isQuickReturn, showLeadingIndicator=$showLeadingIndicator"
        )

        val sessionManager = SessionManager.getInstance(this)

        val appName = try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName
        }

        // For quick returns (e.g., swipe back triggering brief app switch), still check session
        // but don't create new session - let SessionManager handle resume logic
        val existingSession = sessionManager.activeSession.value
        if (existingSession != null && existingSession.appPackageName == packageName) {
            Logger.d(TAG, "Active session exists, showing compact indicator for: $packageName")
            showCompactIndicator(packageName, appName, sessionManager)
            return
        }

        if (sessionManager.hasResumableSession(packageName)) {
            Logger.d(
                TAG,
                "Resumable session exists for $packageName, showing compact indicator and waiting for SessionManager resume"
            )
            showCompactIndicator(packageName, appName, sessionManager)
            return
        }

        if (isQuickReturn) {
            Logger.d(TAG, "Quick return to $packageName - showing compact indicator, session resume handled by SessionManager")
            showCompactIndicator(packageName, appName, sessionManager)
            return
        }

        if (isSessionBeingCreated) {
            Logger.d(TAG, "Session being created, skipping intent input dialog for: $packageName")
            return
        }

        Logger.d(TAG, "No active session, showing intent input dialog for: $packageName")
        showIntentInputDialog(
            packageName = packageName,
            appName = appName,
            sessionManager = sessionManager,
            showLeadingIndicator = showLeadingIndicator
        )
    }

    private fun showIntentInputDialog(
        packageName: String,
        appName: String,
        sessionManager: SessionManager,
        allowDefaultRuleAutoApply: Boolean = true,
        showLeadingIndicator: Boolean = true
    ) {
        if (showLeadingIndicator) {
            showCompactIndicator(packageName, appName, sessionManager)
        }

        IntentInputDialogOverlay.show(
            context = this,
            appName = appName,
            packageName = packageName,
            sessionManager = sessionManager,
            onIntentConfirmed = { constraints ->
                Logger.d(TAG, ">>> Intent confirmed, creating session for $packageName")
                isSessionBeingCreated = true
                cancelPendingIntentReminder(packageName)
                IntentInputDialogOverlay.dismiss()
                FloatingIndicatorOverlay.dismiss()

                FloatingIndicatorOverlay.showWithConstraints(
                    context = this,
                    appName = appName,
                    packageName = packageName,
                    sessionManager = sessionManager,
                    constraints = constraints,
                    onTapToReopen = {
                        showIntentInputDialog(packageName, appName, sessionManager, allowDefaultRuleAutoApply = false)
                    }
                )

                serviceScope.launch {
                    try {
                        val sessionId = sessionManager.createSession(
                            packageName = packageName,
                            displayName = appName,
                            constraints = constraints
                        )
                        if (sessionId != null) {
                            Logger.d(TAG, "<<< Session created successfully for $packageName")
                        } else {
                            Logger.d(
                                TAG,
                                "Session creation skipped for $packageName because foreground changed before commit"
                            )
                        }
                    } catch (e: Exception) {
                        Logger.e(TAG, "!!! Failed to create session: ${e.message}", e)
                    } finally {
                        isSessionBeingCreated = false
                    }
                }
            },
            onDismissed = {
                Logger.d(TAG, "Dialog dismissed without confirming")
                isSessionBeingCreated = false
                runtimeEventLogger.log(
                    eventType = RuntimeEventType.INTENT_INPUT_DISMISSED,
                    sessionId = null,
                    appPackage = packageName,
                    appDisplayName = appName,
                    payload = mapOf("trigger" to "intent_input_dialog")
                )
                showCompactIndicator(packageName, appName, sessionManager)
                scheduleIntentReminder(packageName, appName)
            },
            allowDefaultRuleAutoApply = allowDefaultRuleAutoApply
        )
    }

    private fun showCompactIndicator(packageName: String, appName: String, sessionManager: SessionManager) {
        if (FloatingIndicatorOverlay.isShowing()) {
            Logger.d(TAG, "FloatingIndicator already showing, skipping showCompactIndicator for: $packageName")
            return
        }
        FloatingIndicatorOverlay.show(
            context = this,
            appName = appName,
            packageName = packageName,
            sessionManager = sessionManager,
            onTapToReopen = {
                cancelPendingIntentReminder(packageName)
                showIntentInputDialog(packageName, appName, sessionManager, allowDefaultRuleAutoApply = false)
            }
        )
    }

    private fun scheduleIntentReminder(packageName: String, appName: String) {
        if (!IntentReminderPrefs.isEnabled(this)) {
            cancelPendingIntentReminder()
            return
        }
        val delayMs = IntentReminderPrefs.getDelayMs(this)
        cancelPendingIntentReminder()
        pendingIntentReminderPackage = packageName
        pendingIntentReminderAppName = appName
        mainHandler.postDelayed(pendingIntentReminderRunnable, delayMs)
    }

    private fun cancelPendingIntentReminder(packageName: String? = null) {
        if (packageName != null && pendingIntentReminderPackage != packageName) {
            return
        }
        mainHandler.removeCallbacks(pendingIntentReminderRunnable)
        pendingIntentReminderPackage = null
        pendingIntentReminderAppName = null
        pendingIntentReminderCount = 0
        IntentReminderOverlay.dismiss()
    }

    private fun shouldShowIntentReminder(packageName: String, sessionManager: SessionManager): Boolean {
        val foregroundPackage = resolveForegroundPackage()
        val isStillForeground = foregroundPackage == packageName || _currentPackage.value == packageName
        if (!isStillForeground) return false
        if (isSessionBeingCreated) return false
        if (sessionManager.activeSession.value?.appPackageName == packageName) return false
        if (sessionManager.hasResumableSession(packageName)) return false
        if (IntentInputDialogOverlay.isShowing()) return false
        if (FloatingIndicatorOverlay.isExpanded()) return false
        return sessionManager.isAppMonitoringEnabled(packageName)
    }

    fun forceRestartOverlayForCurrentApp(packageName: String) {
        val foregroundPackage = resolveForegroundPackage()
        if (foregroundPackage != packageName) {
            Logger.d(
                TAG,
                "forceRestartOverlayForCurrentApp ignored: foreground=$foregroundPackage, requested=$packageName"
            )
            return
        }

        serviceScope.launch {
            try {
                Logger.d(TAG, "Force restarting overlay flow for current app: $packageName")
                _currentPackage.value = packageName
                currentMonitoredPackage = null
                lastExitedPackage = null
                lastExitedTime = 0L
                cancelPendingIntentReminder()
                dismissAllOverlays()
                showIndicatorAndOverlay(
                    packageName = packageName,
                    isQuickReturn = false,
                    showLeadingIndicator = false
                )
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to force restart overlay for $packageName", e)
            }
        }
    }

    private fun resolveForegroundPackage(): String? {
        return _currentPackage.value
    }

    private fun dismissAllOverlays() {
        IntentInputDialogOverlay.dismiss()
        IntentReminderOverlay.dismiss()
        FloatingIndicatorOverlay.dismiss()
        JudgmentFeedbackConfirmOverlay.dismiss()
        VoiceInputOverlay.dismiss()
    }

    override fun onInterrupt() {
        try {
            Logger.d(TAG, "AccessibilityService interrupted")
        } catch (e: Exception) {
            Logger.e(TAG, "Error in onInterrupt", e)
        }
    }

    override fun onDestroy() {
        try {
            super.onDestroy()
            serviceScope.cancel()
            cancelPendingIntentReminder()
            unregisterDeviceStateReceiver()
            stopForeground(STOP_FOREGROUND_REMOVE)
            dismissAllOverlays()
            instance = null
            _isServiceReady.value = false
            Logger.d(TAG, "AccessibilityService destroyed")
        } catch (e: Exception) {
            Logger.e(TAG, "Error in onDestroy", e)
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        try {
            unregisterDeviceStateReceiver()
            cancelPendingIntentReminder()
            instance = null
            _isServiceReady.value = false
            return super.onUnbind(intent)
        } catch (e: Exception) {
            Logger.e(TAG, "Error in onUnbind", e)
            return super.onUnbind(intent)
        }
    }

    // ==================== App Detection ====================

    fun getCurrentAppPackage(): String? = _currentPackage.value

    // ==================== Screenshot ====================

    /**
     * Capture the current screen as a bitmap.
     * Note: This requires the screenshot permission which is granted via AccessibilityService.
     * Returns null if screenshot is not available.
     */
    fun takeScreenshot(callback: (Bitmap?) -> Unit) {
        ScreenshotHelper.takeScreenshot(this) { bitmap ->
            callback(bitmap)
        }
    }

    // ==================== Gestures ====================

    /**
     * Perform a global back action (like pressing back button)
     * Uses performGlobalAction which is more reliable than swipe gestures
     */
    fun performBackGesture(callback: (Boolean) -> Unit) {
        Logger.d(TAG, "[performBackGesture] Performing global back action...")
        try {
            val success = performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            Logger.d(TAG, "[performBackGesture] Global back action result: $success")
            callback(success)
        } catch (e: Exception) {
            Logger.e(TAG, "[performBackGesture] Failed: ${e.message}")
            callback(false)
        }
    }

    /**
     * Perform a home gesture (go to home screen)
     */
    fun performHomeGesture(callback: (Boolean) -> Unit) {
        Logger.d(TAG, "[performHomeGesture] Performing global home action...")
        try {
            val success = performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            Logger.d(TAG, "[performHomeGesture] Global home action result: $success")
            callback(success)
        } catch (e: Exception) {
            Logger.e(TAG, "[performHomeGesture] Failed: ${e.message}")
            callback(false)
        }
    }

    /**
     * Perform a swipe left gesture (for navigation)
     */
    fun performSwipeLeft(callback: (Boolean) -> Unit) {
        performGesture(
            createSwipeGesture(0.9f, 0.5f, 0.1f, 0.5f),
            callback,
            "Swipe left"
        )
    }

    /**
     * Perform a swipe right gesture
     */
    fun performSwipeRight(callback: (Boolean) -> Unit) {
        performGesture(
            createSwipeGesture(0.1f, 0.5f, 0.9f, 0.5f),
            callback,
            "Swipe right"
        )
    }

    /**
     * Perform a swipe down gesture
     */
    fun performSwipeDown(callback: (Boolean) -> Unit) {
        performGesture(
            createSwipeGesture(0.5f, 0.1f, 0.5f, 0.9f),
            callback,
            "Swipe down"
        )
    }

    /**
     * Perform a tap gesture at specific coordinates
     */
    fun performTap(x: Float, y: Float, callback: (Boolean) -> Unit) {
        val gesture = createTapGesture(x, y)
        performGesture(gesture, callback, "Tap at ($x, $y)")
    }

    /**
     * Perform a long press gesture at specific coordinates
     */
    fun performLongPress(x: Float, y: Float, callback: (Boolean) -> Unit) {
        val gesture = createLongPressGesture(x, y)
        performGesture(gesture, callback, "Long press at ($x, $y)")
    }

    private fun performGesture(
        gesture: GestureDescription,
        callback: (Boolean) -> Unit,
        gestureName: String
    ) {
        try {
            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    Logger.d(TAG, "$gestureName completed")
                    callback(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Logger.w(TAG, "$gestureName cancelled")
                    callback(false)
                }
            }, null)
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to perform $gestureName", e)
            callback(false)
        }
    }

    // ==================== Gesture Builders ====================

    private fun createSwipeGesture(
        startXPercent: Float,
        startYPercent: Float,
        endXPercent: Float,
        endYPercent: Float
    ): GestureDescription {
        val displayMetrics = resources.displayMetrics
        val startX = displayMetrics.widthPixels * startXPercent
        val startY = displayMetrics.heightPixels * startYPercent
        val endX = displayMetrics.widthPixels * endXPercent
        val endY = displayMetrics.heightPixels * endYPercent

        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        return GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 200))
            .build()
    }

    private fun createTapGesture(x: Float, y: Float): GestureDescription {
        val path = Path().apply {
            moveTo(x, y)
        }
        return GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
    }

    private fun createLongPressGesture(x: Float, y: Float): GestureDescription {
        val path = Path().apply {
            moveTo(x, y)
        }
        return GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 500))
            .build()
    }

}

data class AccessibilityDeviceState(
    val shouldAnalyze: Boolean = false,
    val isInteractive: Boolean = false,
    val isDeviceLocked: Boolean = true,
    val isKeyguardLocked: Boolean = true,
    val displayState: Int? = null,
    val changedAt: Long = System.currentTimeMillis()
)
