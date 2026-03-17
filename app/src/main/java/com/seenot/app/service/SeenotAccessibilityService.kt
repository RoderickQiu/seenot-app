package com.seenot.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.seenot.app.utils.Logger
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import com.seenot.app.MainActivity
import com.seenot.app.R
import com.seenot.app.domain.SessionManager
import com.seenot.app.ui.overlay.FloatingIndicatorOverlay
import com.seenot.app.ui.overlay.IntentInputDialogOverlay
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

        // Debounce threshold: ignore app switches within this time (ms)
        private const val APP_SWITCH_DEBOUNCE_MS = 500L

        var instance: SeenotAccessibilityService? = null

        private val _currentPackage = MutableStateFlow<String?>(null)
        val currentPackage: StateFlow<String?> = _currentPackage.asStateFlow()

        private val _isServiceReady = MutableStateFlow(false)
        val isServiceReady: StateFlow<Boolean> = _isServiceReady.asStateFlow()

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
    private lateinit var sessionManager: SessionManager
    
    // Service-level coroutine scope to prevent leaks
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            instance = this
            _isServiceReady.value = true
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

            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    val packageName = event.packageName?.toString()
                    val className = event.className?.toString()
                    Logger.d(TAG, "TYPE_WINDOW_STATE_CHANGED: $packageName, class: $className, current: ${_currentPackage.value}")

                    if (packageName == null) return

                    // Debounce: ignore rapid switches
                    val now = System.currentTimeMillis()
                    if (now - lastAppSwitchTime < APP_SWITCH_DEBOUNCE_MS) {
                        Logger.d(TAG, "Debouncing app switch: $packageName (${now - lastAppSwitchTime}ms)")
                        return
                    }

                    // Ignore system apps (except launcher)
                    if (isSystemApp(packageName) && !isLauncher(packageName)) {
                        Logger.d(TAG, "Ignoring system app: $packageName")
                        return
                    }

                    if (packageName != _currentPackage.value) {
                        Logger.d(TAG, "App switched to: $packageName")
                        lastAppSwitchTime = now
                        _currentPackage.value = packageName

                        // Check if className is capable before calling checkAndShowOverlay
                        // Exception: always process launcher to ensure proper state tracking
                        val isCapable = isCapableClass(className)
                        if (isCapable || isLauncher(packageName)) {
                            checkAndShowOverlay(packageName, className)
                        } else {
                            Logger.d(TAG, "Not a capable class: $className, package updated but skipping overlay logic")
                        }
                    } else {
                        Logger.d(TAG, "Same package, skipping: $packageName")
                    }
                }
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                    // Can be used for content monitoring if needed
                }
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Error in onAccessibilityEvent", e)
        }
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
            val controlledApps = sessionManager.controlledApps.value
            Logger.d(TAG, "controlledApps: $controlledApps")

            val isControlledApp = packageName in controlledApps
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
                    dismissAllOverlays()
                    showIndicatorAndOverlay(packageName)
                }
                // Switch from monitored app to non-monitored app
                isSwitchingToDifferentApp && !isControlledApp -> {
                    Logger.d(TAG, "Case: Leaving monitored app: $previousPackage -> $packageName (non-controlled)")
                    lastExitedPackage = previousPackage
                    lastExitedTime = System.currentTimeMillis()
                    currentMonitoredPackage = null
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
                        // Check if there's an active session for this app
                        val existingSession = sessionManager.activeSession.value
                        val hasSession = existingSession != null && existingSession.appPackageName == packageName

                        if (hasSession) {
                            Logger.d(TAG, "Quick return to $packageName with active session - swipe back, no overlay changes")
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
    private fun showIndicatorAndOverlay(packageName: String, isQuickReturn: Boolean = false) {
        currentMonitoredPackage = packageName

        Logger.d(TAG, "showIndicatorAndOverlay called for: $packageName, isQuickReturn=$isQuickReturn")

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
        showIntentInputDialog(packageName, appName, sessionManager)
    }

    private fun showIntentInputDialog(packageName: String, appName: String, sessionManager: SessionManager, allowDefaultRuleAutoApply: Boolean = true) {
        showCompactIndicator(packageName, appName, sessionManager)

        IntentInputDialogOverlay.show(
            context = this,
            appName = appName,
            packageName = packageName,
            sessionManager = sessionManager,
            onIntentConfirmed = { constraints ->
                Logger.d(TAG, ">>> Intent confirmed, creating session for $packageName")
                isSessionBeingCreated = true
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
                        sessionManager.createSession(
                            packageName = packageName,
                            displayName = appName,
                            constraints = constraints
                        )
                        Logger.d(TAG, "<<< Session created successfully for $packageName")
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
                showCompactIndicator(packageName, appName, sessionManager)
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
                showIntentInputDialog(packageName, appName, sessionManager, allowDefaultRuleAutoApply = false)
            }
        )
    }

    private fun dismissAllOverlays() {
        IntentInputDialogOverlay.dismiss()
        FloatingIndicatorOverlay.dismiss()
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

    // ==================== Node Operations ====================

    /**
     * Find a node by text
     */
    fun findNodeByText(text: String): AccessibilityNodeInfo? {
        val rootNode = rootInActiveWindow ?: return null
        val nodes = rootNode.findAccessibilityNodeInfosByText(text)
        return if (nodes.isNotEmpty()) nodes[0] else null
    }

    /**
     * Find a node by view ID
     */
    @Suppress("UNUSED_PARAMETER")
    fun findNodeByViewId(viewId: String): AccessibilityNodeInfo? {
        val rootNode = rootInActiveWindow ?: return null
        return rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
    }

    /**
     * Click on a node
     */
    fun clickNode(node: AccessibilityNodeInfo): Boolean {
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    /**
     * Scroll forward
     */
    fun scrollForward(node: AccessibilityNodeInfo): Boolean {
        return node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    }

    /**
     * Scroll backward
     */
    fun scrollBackward(node: AccessibilityNodeInfo): Boolean {
        return node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
    }
}
