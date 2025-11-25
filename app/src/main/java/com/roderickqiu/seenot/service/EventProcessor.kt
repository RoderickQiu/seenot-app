package com.roderickqiu.seenot.service

import android.content.pm.PackageManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.roderickqiu.seenot.R
import com.roderickqiu.seenot.data.AppDataStore

class EventProcessor(
    private val context: android.content.Context,
    private val appDataStore: AppDataStore,
    private val notificationManager: NotificationManager,
    private val screenshotAnalyzer: ScreenshotAnalyzer,
    private val constraintManager: ConstraintManager
) {
    private var lastTimeClassName: String? = null
    private var lastTimeClassCapable: Boolean = false
    private var lastTimeCapableClass: String? = null
    private var foregroundWindowId: Int = -1
    private var currentMonitoredPackage: String? = null
    private var currentMonitoredAppName: String? = null
    private var lastMonitoredLogTimeMs: Long = 0L

    private fun eventTypeName(type: Int): String {
        return when (type) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "TYPE_WINDOW_STATE_CHANGED"
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "TYPE_WINDOW_CONTENT_CHANGED"
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> "TYPE_VIEW_SCROLLED"
            AccessibilityEvent.TYPE_VIEW_CLICKED -> "TYPE_VIEW_CLICKED"
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> "TYPE_VIEW_FOCUSED"
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> "TYPE_VIEW_TEXT_CHANGED"
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> "TYPE_VIEW_TEXT_SELECTION_CHANGED"
            AccessibilityEvent.TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY -> "TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY"
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> "TYPE_WINDOWS_CHANGED"
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> "TYPE_NOTIFICATION_STATE_CHANGED"
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED -> "TYPE_VIEW_ACCESSIBILITY_FOCUSED"
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED -> "TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED"
            AccessibilityEvent.TYPE_GESTURE_DETECTION_START -> "TYPE_GESTURE_DETECTION_START"
            AccessibilityEvent.TYPE_GESTURE_DETECTION_END -> "TYPE_GESTURE_DETECTION_END"
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START -> "TYPE_TOUCH_INTERACTION_START"
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_END -> "TYPE_TOUCH_INTERACTION_END"
            else -> "UNKNOWN($type)"
        }
    }

    private fun isCapableClass(
        className: String?,
        lastTimeClassName: String?,
        lastTimeClassCapable: Boolean
    ): Boolean {
        if (className == null) return false
        if (className == lastTimeClassName) return lastTimeClassCapable
        return if (className.contains("Activity")) {
            true
        } else {
            !className.startsWith("android.widget.") &&
                !className.startsWith("android.view.") &&
                !className.startsWith("androidx.") &&
                !className.startsWith("com.android.systemui") &&
                !className.startsWith("android.app") &&
                !className.startsWith("android.inputmethodservice")
        }
    }

    fun processEvent(event: AccessibilityEvent?, service: android.accessibilityservice.AccessibilityService) {
        event?.let { e ->
            when (e.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                AccessibilityEvent.TYPE_VIEW_SCROLLED,
                AccessibilityEvent.TYPE_VIEW_CLICKED,
                AccessibilityEvent.TYPE_VIEW_CONTEXT_CLICKED,
                AccessibilityEvent.TYPE_VIEW_FOCUSED,
                AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
                AccessibilityEvent.TYPE_VIEW_SELECTED,
                AccessibilityEvent.TYPE_GESTURE_DETECTION_END,
                AccessibilityEvent.TYPE_WINDOWS_CHANGED,
                AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED,
                AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED,
                AccessibilityEvent.TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY,
                AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                    foregroundWindowId = e.windowId

                    val className = e.className?.toString()
                    val capable =
                            isCapableClass(
                                    className,
                                    lastTimeClassName,
                                    lastTimeClassCapable
                            )
                    lastTimeClassName = className
                    lastTimeClassCapable = capable
                    if (capable && className != null) {
                        lastTimeCapableClass = className
                        screenshotAnalyzer.lastTimeCapableClass = className
                    }

                    val packageName = e.packageName?.toString()
                    if (packageName != null && capable) {
                        checkAndLogMonitoredApp(packageName, service)
                    }

                    // Regardless of capability, if we are currently in a monitored app,
                    // check for things once a time
                    val now = System.currentTimeMillis()
                    if (currentMonitoredPackage != null) {
                        Log.d(
                                "A11yService",
                                "Accessibility event: ${eventTypeName(e.eventType)}, className: ${e.className?.toString()}, packageName: ${e.packageName?.toString()}, text: ${e.text.toString()}"
                        )
                        if (now - lastMonitoredLogTimeMs >= A11yService.LOG_INTERVAL_MS) {
                            Log.d(
                                    "A11yService",
                                    "Active in monitored app: $currentMonitoredAppName (package: $currentMonitoredPackage) via event: ${eventTypeName(e.eventType)}"
                            )
                            screenshotAnalyzer.tryTakeScreenshot(service, "periodic-active")
                            lastMonitoredLogTimeMs = now
                        }
                    }
                }
            }
        }
    }

    fun getRightWindowNode(service: android.accessibilityservice.AccessibilityService): AccessibilityNodeInfo? {
        // Compatible with multi-window mode
        val ws = service.windows
        for (i in 0 until ws.size) {
            val w = ws[i]
            if (w.id == foregroundWindowId) {
                return w.root
            }
        }
        return service.rootInActiveWindow
    }

    private fun checkAndLogMonitoredApp(packageName: String, service: android.accessibilityservice.AccessibilityService) {
        try {
            // Get app name from package name
            val packageManager = context.packageManager
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            val appName = packageManager.getApplicationLabel(appInfo).toString()

            // Load monitoring apps from settings
            val monitoringApps = appDataStore.loadMonitoringApps()

            // Check if the current app is in the monitoring list
            val isMonitored = monitoringApps.any { it.name == appName && it.isEnabled }

            // If we were in a monitored app and now switching to a different package, log exit
            // first
            val previousPackage = currentMonitoredPackage
            val previousAppName = currentMonitoredAppName
            if (previousPackage != null && previousPackage != packageName) {
                Log.d(
                        "A11yService",
                        "Exited monitored app: $previousAppName (package: $previousPackage)"
                )
                currentMonitoredPackage = null
                currentMonitoredAppName = null
                lastTimeCapableClass = null
                screenshotAnalyzer.currentMonitoredPackage = null
                screenshotAnalyzer.currentMonitoredAppName = null
                screenshotAnalyzer.lastTimeCapableClass = null
            }

            // Then, if the new package is monitored
            if (isMonitored) {
                val now = System.currentTimeMillis()
                if (currentMonitoredPackage == null || currentMonitoredPackage != packageName) {
                    // entering a monitored app (first time or switched)
                    Log.d("A11yService", "Entered monitored app: $appName (package: $packageName)")
                    currentMonitoredPackage = packageName
                    currentMonitoredAppName = appName
                    screenshotAnalyzer.currentMonitoredPackage = packageName
                    screenshotAnalyzer.currentMonitoredAppName = appName
                    lastMonitoredLogTimeMs = now
                    notificationManager.showToast(context.getString(R.string.monitor_app_entered) + appName)
                    screenshotAnalyzer.tryTakeScreenshot(service, "entered")
                    constraintManager.handleOnEnterRules(appName)
                }
            }
        } catch (e: PackageManager.NameNotFoundException) {
            // App not found, ignore
        } catch (e: Exception) {
            Log.e("A11yService", "Error checking monitored app", e)
        }
    }
}

