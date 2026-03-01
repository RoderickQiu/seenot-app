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
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import com.seenot.app.MainActivity
import com.seenot.app.R
import com.seenot.app.domain.SessionManager
import com.seenot.app.ui.overlay.FloatingIndicatorOverlay
import com.seenot.app.ui.overlay.VoiceInputOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

        var instance: SeenotAccessibilityService? = null

        private val _currentPackage = MutableStateFlow<String?>(null)
        val currentPackage: StateFlow<String?> = _currentPackage.asStateFlow()

        private val _isServiceReady = MutableStateFlow(false)
        val isServiceReady: StateFlow<Boolean> = _isServiceReady.asStateFlow()

        // Track current monitored app to prevent indicator flickering
        private var currentMonitoredPackage: String? = null
    }

    private val gestureExecutor: Executor = Executors.newSingleThreadExecutor()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isServiceReady.value = true
        Log.d(TAG, "AccessibilityService connected")

        // Start foreground service to prevent being killed
        try {
            startForegroundService()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service", e)
        }
    }

    /**
     * Start foreground service with notification
     */
    private fun startForegroundService() {
        try {
            createNotificationChannel()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create notification channel", e)
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
            // Use reflection to call setForegroundServiceType on Android 14+
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                try {
                    val method = Service::class.java.getDeclaredMethod("setForegroundServiceType", Int::class.javaPrimitiveType)
                    method.isAccessible = true
                    // ForegroundService.SPECIAL_USE = 4
                    method.invoke(this, 4)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to set foreground service type", e)
                }
            }
            startForeground(NOTIFICATION_ID, notification)
            Log.d(TAG, "Foreground notification started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground", e)
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
        event ?: return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val packageName = event.packageName?.toString()
                val className = event.className?.toString()
                Log.d(TAG, "TYPE_WINDOW_STATE_CHANGED: $packageName, class: $className, current: ${_currentPackage.value}")

                if (packageName != null && packageName != _currentPackage.value) {
                    Log.d(TAG, "App switched to: $packageName")
                    _currentPackage.value = packageName

                    // Check if this is a controlled app and show overlay
                    checkAndShowOverlay(packageName, className)
                } else {
                    Log.d(TAG, "Same package, skipping: $packageName")
                }
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // Can be used for content monitoring if needed
            }
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
            Log.d(TAG, "Empty package name, skipping")
            return
        }

        // Check if class is "capable" - ignore system UI classes
        // This prevents false triggers during navigation within the same app
        val isCapable = isCapableClass(className)
        if (!isCapable) {
            Log.d(TAG, "Not a capable class: $className, skipping")
            return
        }

        // Ignore SeeNot's own package to prevent overlay dismissal
        if (packageName == this.packageName) {
            Log.d(TAG, "Own package $packageName, skipping")
            return
        }

        try {
            // Get controlled apps from SessionManager
            val sessionManager = SessionManager.getInstance(this)
            val controlledApps = sessionManager.controlledApps.value
            Log.d(TAG, "controlledApps: $controlledApps")

            val isControlledApp = packageName in controlledApps
            Log.d(TAG, "isControlledApp: $isControlledApp for $packageName")

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
                    Log.d(TAG, "Case: Switching monitored app: $previousPackage -> $packageName")
                    FloatingIndicatorOverlay.dismiss()
                    VoiceInputOverlay.dismiss()
                    showIndicatorAndOverlay(packageName)
                }
                // Switch from monitored app to non-monitored app
                isSwitchingToDifferentApp && !isControlledApp -> {
                    Log.d(TAG, "Case: Leaving monitored app: $previousPackage -> $packageName (non-controlled)")

                    // End the active session if one exists
                    val currentSession = sessionManager.activeSession.value
                    if (currentSession != null) {
                        Log.d(TAG, "Ending session for $previousPackage")
                        CoroutineScope(Dispatchers.Main + SupervisorJob()).launch {
                            sessionManager.endSessionManually()
                        }
                    }

                    FloatingIndicatorOverlay.dismiss()
                    VoiceInputOverlay.dismiss()
                    currentMonitoredPackage = null
                }
                // Enter monitored app from non-monitored
                !wasInMonitoredApp && isControlledApp -> {
                    Log.d(TAG, "Case: Entering monitored app: $packageName")
                    showIndicatorAndOverlay(packageName)
                }
                // Already in same monitored app - do nothing (prevent flickering)
                wasInMonitoredApp && previousPackage == packageName -> {
                    Log.d(TAG, "Case: Same package $packageName, skipping")
                }
                // Not in monitored app and not entering one - do nothing
                else -> {
                    Log.d(TAG, "Case: No state change relevant for $packageName")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in checkAndShowOverlay", e)
        }
    }

    /**
     * Show the floating indicator and prepare voice input overlay
     */
    private fun showIndicatorAndOverlay(packageName: String) {
        currentMonitoredPackage = packageName

        Log.d(TAG, "showIndicatorAndOverlay called for: $packageName")

        // Get SessionManager
        val sessionManager = SessionManager.getInstance(this)

        // Get app display name
        val appName = try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName
        }

        Log.d(TAG, "Showing floating overlay for controlled app: $packageName")

        // Show the floating indicator overlay - 方案 A: 点击自动录音
        // 用户点击悬浮球 → 自动开始录音 → 自动停止并处理 → 创建 Session
        FloatingIndicatorOverlay.show(
            context = this,
            appName = appName,
            packageName = packageName,
            sessionManager = sessionManager,
            onIntentConfirmed = { constraints ->
                // Create session in AccessibilityService (not MainScreen!)
                Log.d(TAG, ">>> Creating session for $packageName")
                CoroutineScope(Dispatchers.Main + SupervisorJob()).launch {
                    try {
                        sessionManager.createSession(
                            packageName = packageName,
                            displayName = appName,
                            constraints = constraints
                        )
                        Log.d(TAG, "<<< Session created successfully for $packageName")
                    } catch (e: Exception) {
                        Log.e(TAG, "!!! Failed to create session: ${e.message}", e)
                    }
                }
            }
        )
    }

    /**
     * Show voice input overlay directly in the monitored app (like seenot-reborn's AskOverlay)
     * Obsolete: FloatingIndicatorOverlay now handles everything directly
     */
    private fun showVoiceInputOverlay(packageName: String, appName: String) {
        // Now handled by FloatingIndicatorOverlay directly
        // This method kept for backward compatibility
        val sessionManager = SessionManager.getInstance(this)

        FloatingIndicatorOverlay.show(
            context = this,
            appName = appName,
            packageName = packageName,
            sessionManager = sessionManager,
            onIntentConfirmed = { constraints ->
                Log.d(TAG, "Session created for $packageName with ${constraints.size} constraints")
            }
        )
    }

    override fun onInterrupt() {
        Log.d(TAG, "AccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        stopForeground(STOP_FOREGROUND_REMOVE)
        FloatingIndicatorOverlay.dismiss()
        instance = null
        _isServiceReady.value = false
        Log.d(TAG, "AccessibilityService destroyed")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        _isServiceReady.value = false
        return super.onUnbind(intent)
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
        Log.d(TAG, "[performBackGesture] Performing global back action...")
        try {
            val success = performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            Log.d(TAG, "[performBackGesture] Global back action result: $success")
            callback(success)
        } catch (e: Exception) {
            Log.e(TAG, "[performBackGesture] Failed: ${e.message}")
            callback(false)
        }
    }

    /**
     * Perform a home gesture (go to home screen)
     */
    fun performHomeGesture(callback: (Boolean) -> Unit) {
        Log.d(TAG, "[performHomeGesture] Performing global home action...")
        try {
            val success = performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            Log.d(TAG, "[performHomeGesture] Global home action result: $success")
            callback(success)
        } catch (e: Exception) {
            Log.e(TAG, "[performHomeGesture] Failed: ${e.message}")
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
                    Log.d(TAG, "$gestureName completed")
                    callback(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.w(TAG, "$gestureName cancelled")
                    callback(false)
                }
            }, null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to perform $gestureName", e)
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
