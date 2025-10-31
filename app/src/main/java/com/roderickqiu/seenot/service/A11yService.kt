package com.roderickqiu.seenot.service

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.roderickqiu.seenot.MainActivity
import com.roderickqiu.seenot.R
import com.roderickqiu.seenot.data.AppDataStore
import com.roderickqiu.seenot.utils.AccessibilityEventUtils

class A11yService : AccessibilityService() {

    private lateinit var appDataStore: AppDataStore
    private lateinit var mHandler: Handler
    private var lastTimeClassName: String? = null
    private var lastTimeClassCapable: Boolean = false
    private var foregroundWindowId: Int = -1
    private var currentMonitoredPackage: String? = null
    private var currentMonitoredAppName: String? = null
    private var lastMonitoredLogTimeMs: Long = 0L
    private var isTakingScreenshot: Boolean = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d("A11yService", "Accessibility service connected")
        mHandler = Handler(Looper.getMainLooper())
        appDataStore = AppDataStore(this)
        startInForeground()
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("A11yService", "Accessibility service created")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
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
                            AccessibilityEventUtils.isCapableClass(
                                    className,
                                    lastTimeClassName,
                                    lastTimeClassCapable
                            )
                    lastTimeClassName = className
                    lastTimeClassCapable = capable

                    val packageName = e.packageName?.toString()
                    if (packageName != null && capable) {
                        checkAndLogMonitoredApp(packageName)
                    }

                    // Regardless of capability, if we are currently in a monitored app,
                    // check for things once a time
                    val now = System.currentTimeMillis()
                    if (currentMonitoredPackage != null) {
                        Log.d(
                                "A11yService",
                                "Accessibility event: ${AccessibilityEventUtils.eventTypeName(e.eventType)}, className: ${e.className?.toString()}, packageName: ${e.packageName?.toString()}, text: ${e.text.toString()}"
                        )
                        if (now - lastMonitoredLogTimeMs >= LOG_INTERVAL_MS) {
                            Log.d(
                                    "A11yService",
                                    "Active in monitored app: $currentMonitoredAppName (package: $currentMonitoredPackage) via event: ${AccessibilityEventUtils.eventTypeName(e.eventType)}"
                            )
                            tryTakeScreenshot("periodic-active")
                            lastMonitoredLogTimeMs = now
                        }
                    }
                }
            }
        }
    }

    private fun getRightWindowNode(): AccessibilityNodeInfo? {
        // Compatible with multi-window mode
        val ws = windows
        for (i in 0 until ws.size) {
            val w = ws[i]
            if (w.id == foregroundWindowId) {
                return w.root
            }
        }
        return rootInActiveWindow
    }

    private fun checkAndLogMonitoredApp(packageName: String) {
        try {
            // Get app name from package name
            val packageManager = packageManager
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
            }

            // Then, if the new package is monitored
            if (isMonitored) {
                val now = System.currentTimeMillis()
                if (currentMonitoredPackage == null || currentMonitoredPackage != packageName) {
                    // entering a monitored app (first time or switched)
                    Log.d("A11yService", "Entered monitored app: $appName (package: $packageName)")
                    currentMonitoredPackage = packageName
                    currentMonitoredAppName = appName
                    lastMonitoredLogTimeMs = now
                    showToast(getString(R.string.monitor_app_entered) + appName)
                    tryTakeScreenshot("entered")
                }
            }
        } catch (e: PackageManager.NameNotFoundException) {
            // App not found, ignore
        } catch (e: Exception) {
            Log.e("A11yService", "Error checking monitored app", e)
        }
    }

    override fun onInterrupt() {
        // No-op
    }

    private fun showToast(message: String, duration: Int = Toast.LENGTH_SHORT) {
        mHandler.post { Toast.makeText(applicationContext, message, duration).show() }
    }

    private fun startInForeground() {
        val channelId = CHANNEL_ID
        val channelName = getString(R.string.fg_channel_name)
        val channelDescription = getString(R.string.fg_channel_desc)
        val importance = NotificationManager.IMPORTANCE_HIGH
        val channel = NotificationChannel(channelId, channelName, importance)
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC)
        channel.setBypassDnd(true)
        channel.description = channelDescription
        channel.setShowBadge(false)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)

        val intent = Intent(this, MainActivity::class.java)
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, pendingFlags)

        val notification: Notification =
                NotificationCompat.Builder(this, CHANNEL_ID)
                        .setContentTitle(getString(R.string.fg_notification_title))
                        .setContentText(getString(R.string.fg_notification_text))
                        .setSmallIcon(R.mipmap.ic_launcher)
                        .setContentIntent(pendingIntent)
                        .setOngoing(true)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .build()
        startForeground(NOTIFICATION_ID, notification)

        Log.d("A11yService", "Foreground service started")
    }

    private fun tryTakeScreenshot(reason: String) {
        if (isTakingScreenshot) {
            return
        }
        isTakingScreenshot = true
        val executor = ContextCompat.getMainExecutor(this)
        try {
            takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    executor,
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(
                                screenshotResult: AccessibilityService.ScreenshotResult
                        ) {
                            isTakingScreenshot = false
                            Log.d(
                                    "A11yService",
                                    "Screenshot success for $currentMonitoredAppName ($currentMonitoredPackage), reason=$reason"
                            )

                            try {
                                val hb = screenshotResult.hardwareBuffer
                                val cs = screenshotResult.colorSpace
                                val hardwareBitmap = Bitmap.wrapHardwareBuffer(hb, cs)
                                if (hardwareBitmap != null) {
                                    val originalWidth = hardwareBitmap.width
                                    val originalHeight = hardwareBitmap.height
                                    val scaledWidth = originalWidth / 2
                                    val scaledHeight = originalHeight / 2

                                    // Create a mutable copy first, then directly scale it
                                    val mutableBitmap =
                                            hardwareBitmap.copy(Bitmap.Config.ARGB_8888, true)
                                    val scaledBitmap =
                                            Bitmap.createScaledBitmap(
                                                    mutableBitmap,
                                                    scaledWidth,
                                                    scaledHeight,
                                                    true
                                            )
                                    mutableBitmap.recycle()

                                    Log.d(
                                            "A11yService",
                                            "Bitmap created and scaled from screenshot: ${scaledBitmap.width}x${scaledBitmap.height}"
                                    )
                                } else {
                                    Log.w(
                                            "A11yService",
                                            "Failed to wrap hardware buffer into Bitmap"
                                    )
                                }
                            } catch (t: Throwable) {
                                Log.e("A11yService", "Error creating Bitmap from screenshot", t)
                            } finally {
                                try {
                                    screenshotResult.hardwareBuffer.close()
                                } catch (_: Throwable) {}
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            isTakingScreenshot = false
                            Log.w(
                                    "A11yService",
                                    "Screenshot failed (code=$errorCode) for $currentMonitoredAppName ($currentMonitoredPackage), reason=$reason"
                            )
                        }
                    }
            )
        } catch (t: Throwable) {
            isTakingScreenshot = false
            Log.e("A11yService", "Screenshot exception for reason=$reason", t)
        }
    }

    companion object {
        private const val CHANNEL_ID = "seenot_accessibility"
        private const val NOTIFICATION_ID = 1001
        const val LOG_INTERVAL_MS: Long = 5_000L
    }
}
