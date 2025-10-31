package com.roderickqiu.seenot.service

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import com.roderickqiu.seenot.R
import com.roderickqiu.seenot.MainActivity
import com.roderickqiu.seenot.data.AppDataStore
import com.roderickqiu.seenot.utils.AccessibilityEventUtils

class A11yService : AccessibilityService() {

    private lateinit var appDataStore: AppDataStore
    private var lastTimeClassName: String? = null
    private var lastTimeClassCapable: Boolean = false
    private var foregroundWindowId: Int = -1
    private var currentMonitoredPackage: String? = null
    private var currentMonitoredAppName: String? = null
    private var lastMonitoredLogTimeMs: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d("A11yService", "Accessibility service connected")
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
                AccessibilityEvent.TYPE_GESTURE_DETECTION_END -> {
                    foregroundWindowId = e.windowId

                    Log.d("A11yService", "Accessibility event: ${AccessibilityEventUtils.eventTypeName(e.eventType)}, className: ${e.className?.toString()}, packageName: ${e.packageName?.toString()}, text: ${e.text.toString()}")

                    val className = e.className?.toString()
                    val capable = AccessibilityEventUtils.isCapableClass(
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
                    if (currentMonitoredPackage != null && now - lastMonitoredLogTimeMs >= LOG_INTERVAL_MS) {
                        Log.d(
                            "A11yService",
                            "Active in monitored app: $currentMonitoredAppName (package: $currentMonitoredPackage) via event: ${AccessibilityEventUtils.eventTypeName(e.eventType)}"
                        )
                        lastMonitoredLogTimeMs = now
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
            
            // If we were in a monitored app and now switching to a different package, log exit first
            val previousPackage = currentMonitoredPackage
            val previousAppName = currentMonitoredAppName
            if (previousPackage != null && previousPackage != packageName) {
                Log.d("A11yService", "Exited monitored app: $previousAppName (package: $previousPackage)")
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

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
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

    companion object {
        private const val CHANNEL_ID = "seenot_accessibility"
        private const val NOTIFICATION_ID = 1001
        const val LOG_INTERVAL_MS: Long = 5_000L
    }
}


