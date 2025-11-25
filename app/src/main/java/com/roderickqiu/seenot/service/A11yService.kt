package com.roderickqiu.seenot.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.roderickqiu.seenot.data.AppDataStore

class A11yService : AccessibilityService() {

    private lateinit var appDataStore: AppDataStore
    private lateinit var notificationManager: NotificationManager
    private lateinit var actionExecutor: ActionExecutor
    private lateinit var constraintManager: ConstraintManager
    private lateinit var screenshotAnalyzer: ScreenshotAnalyzer
    private lateinit var eventProcessor: EventProcessor

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d("A11yService", "Accessibility service connected")
        
        appDataStore = AppDataStore(this)
        notificationManager = NotificationManager(this)
        actionExecutor = ActionExecutor(this, notificationManager, this)
        constraintManager = ConstraintManager(appDataStore, actionExecutor)
        screenshotAnalyzer = ScreenshotAnalyzer(this, appDataStore, constraintManager, actionExecutor, notificationManager)
        eventProcessor = EventProcessor(this, appDataStore, notificationManager, screenshotAnalyzer)
        
        constraintManager.loadTimeConstraintStates()
        notificationManager.startInForeground(this)
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("A11yService", "Accessibility service created")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        eventProcessor.processEvent(event, this)
    }

    override fun onInterrupt() {
        Log.d("A11yService", "Accessibility service interrupted")
    }

    companion object {
        const val LOG_INTERVAL_MS: Long = 5_000L
    }
}
