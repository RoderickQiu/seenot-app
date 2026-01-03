package com.roderickqiu.seenot.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.graphics.Path
import android.accessibilityservice.GestureDescription
import android.widget.Toast
import com.roderickqiu.seenot.R
import com.roderickqiu.seenot.data.AppDataStore
import com.roderickqiu.seenot.data.RuleRecordRepo

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
        screenshotAnalyzer = ScreenshotAnalyzer(this, appDataStore, constraintManager, actionExecutor, notificationManager, RuleRecordRepo(this))
        eventProcessor = EventProcessor(this, appDataStore, notificationManager, screenshotAnalyzer, constraintManager)
        
        constraintManager.loadTimeConstraintStates()
        notificationManager.startInForeground(this)
        
        // Store instance for CoordinatePickerOverlay to access
        Companion.instance = this
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
    
    // Method to perform a global click at specific coordinates
    fun performGlobalClick(x: Int, y: Int) {
        val path = Path()
        path.moveTo(x.toFloat(), y.toFloat())
        path.lineTo(x.toFloat() + 1, y.toFloat() + 1)
        
        val gestureBuilder = GestureDescription.Builder()
        val strokeDescription = GestureDescription.StrokeDescription(path, 0L, 100L)
        val gestureDescription = gestureBuilder.addStroke(strokeDescription).build()
        
        dispatchGesture(gestureDescription, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                Toast.makeText(
                    this@A11yService,
                    getString(R.string.coordinate_picker_test_click, x, y),
                    Toast.LENGTH_SHORT
                ).show()
            }
            
            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                Toast.makeText(
                    this@A11yService,
                    getString(R.string.click_failed),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }, null)
    }

    companion object {
        const val LOG_INTERVAL_MS: Long = 5_000L
        private var instance: A11yService? = null
        
        fun getInstance(): A11yService? {
            return instance
        }
    }

    fun setRulesEnabled(enabled: Boolean) {
        if (::constraintManager.isInitialized) {
            constraintManager.setRulesEnabled(enabled)
        }
    }

    fun getConstraintManager(): ConstraintManager? {
        return if (::constraintManager.isInitialized) constraintManager else null
    }
}