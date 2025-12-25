package com.roderickqiu.seenot.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.roderickqiu.seenot.data.ActionType
import com.roderickqiu.seenot.data.Rule
import android.widget.Toast

class ActionExecutor(
    private val accessibilityService: AccessibilityService,
    private val notificationManager: NotificationManager,
    private val context: android.content.Context
) {
    
    private inner class ClickGestureCallback(
        private val x: Float,
        private val y: Float
    ) : android.accessibilityservice.AccessibilityService.GestureResultCallback() {
        override fun onCompleted(gestureDescription: GestureDescription?) {
            super.onCompleted(gestureDescription)
            notificationManager.showToast(context.getString(com.roderickqiu.seenot.R.string.action_auto_click_label) + ": ($x, $y)", Toast.LENGTH_SHORT)
            Log.d("A11yService", "AUTO_CLICK performed at coordinate: ($x, $y)")
        }
        
        override fun onCancelled(gestureDescription: GestureDescription?) {
            super.onCancelled(gestureDescription)
            notificationManager.showToast(context.getString(com.roderickqiu.seenot.R.string.action_auto_click_label) + ": " + context.getString(com.roderickqiu.seenot.R.string.click_failed), Toast.LENGTH_SHORT)
            Log.d("A11yService", "AUTO_CLICK cancelled at coordinate: ($x, $y)")
        }
    }
    
    private inner class ScrollGestureCallback(
        private val isScrollUp: Boolean
    ) : android.accessibilityservice.AccessibilityService.GestureResultCallback() {
        override fun onCompleted(gestureDescription: GestureDescription?) {
            super.onCompleted(gestureDescription)
            Log.d("A11yService", "Scroll gesture completed: ${if (isScrollUp) "UP" else "DOWN"}")
        }
        
        override fun onCancelled(gestureDescription: GestureDescription?) {
            super.onCancelled(gestureDescription)
            Log.d("A11yService", "Scroll gesture cancelled: ${if (isScrollUp) "UP" else "DOWN"}")
        }
    }
    
    fun executeAction(rule: Rule, appName: String) {
        when (rule.action.type) {
            ActionType.REMIND -> {
                val message = rule.action.parameter ?: "Reminder"
                notificationManager.showToast("$appName: $message", Toast.LENGTH_LONG)
                Log.d("A11yService", "Triggered REMIND action: $message")
            }
            ActionType.AUTO_BACK -> {
                notificationManager.showToast(context.getString(com.roderickqiu.seenot.R.string.action_auto_back_label), Toast.LENGTH_SHORT)
                Log.d("A11yService", "Triggered AUTO_BACK action")
                val success = accessibilityService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                if (!success) {
                    accessibilityService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                    notificationManager.showToast(context.getString(com.roderickqiu.seenot.R.string.back_last_failed), Toast.LENGTH_SHORT)
                    Log.d("A11yService", "AUTO_BACK failed, went to home after toast")
                } else {
                    Log.d("A11yService", "AUTO_BACK successful")
                }
            }
            ActionType.AUTO_CLICK -> {
                val parameter = rule.action.parameter
                if (parameter != null && parameter.isNotEmpty()) {
                    performAutoClickAtCoordinate(parameter)
                } else {
                    notificationManager.showToast(context.getString(com.roderickqiu.seenot.R.string.action_auto_click_label) + ": " + context.getString(com.roderickqiu.seenot.R.string.no_parameter), Toast.LENGTH_SHORT)
                    Log.d("A11yService", "AUTO_CLICK triggered but no parameter provided")
                }
            }
            ActionType.AUTO_SCROLL_UP -> {
                performScrollGesture(true)
                notificationManager.showToast(context.getString(com.roderickqiu.seenot.R.string.action_auto_scroll_up_label), Toast.LENGTH_SHORT)
                Log.d("A11yService", "Triggered AUTO_SCROLL_UP action")
            }
            ActionType.AUTO_SCROLL_DOWN -> {
                performScrollGesture(false)
                notificationManager.showToast(context.getString(com.roderickqiu.seenot.R.string.action_auto_scroll_down_label), Toast.LENGTH_SHORT)
                Log.d("A11yService", "Triggered AUTO_SCROLL_DOWN action")
            }
            ActionType.ASK -> {
                // TODO: Implement ASK action
                notificationManager.showToast(context.getString(com.roderickqiu.seenot.R.string.action_triggered, appName, rule.action.type.name), Toast.LENGTH_LONG)
                Log.d("A11yService", "ASK action triggered (TODO)")
            }
        }
    }
    
    private fun performAutoClickAtCoordinate(parameter: String) {
        // Parse coordinate from parameter (format: "x,y")
        val parts = parameter.split(",")
        if (parts.size != 2) {
            notificationManager.showToast(context.getString(com.roderickqiu.seenot.R.string.action_auto_click_label) + ": " + context.getString(com.roderickqiu.seenot.R.string.invalid_coordinate), Toast.LENGTH_SHORT)
            Log.d("A11yService", "AUTO_CLICK: invalid coordinate format: $parameter")
            return
        }
        
        try {
            val x = parts[0].trim().toFloat()
            val y = parts[1].trim().toFloat()
            
            // Create a click gesture at the specified coordinate
            val path = Path()
            path.moveTo(x, y)
            // Small movement to simulate a tap
            path.lineTo(x + 1f, y + 1f)
            
            val gestureBuilder = GestureDescription.Builder()
            val strokeDescription = GestureDescription.StrokeDescription(path, 0L, 50L)
            val gestureDescription = gestureBuilder.addStroke(strokeDescription).build()
            
            accessibilityService.dispatchGesture(gestureDescription, ClickGestureCallback(x, y), null)
        } catch (e: NumberFormatException) {
            notificationManager.showToast(context.getString(com.roderickqiu.seenot.R.string.action_auto_click_label) + ": " + context.getString(com.roderickqiu.seenot.R.string.invalid_coordinate), Toast.LENGTH_SHORT)
            Log.d("A11yService", "AUTO_CLICK: invalid coordinate values: $parameter", e)
        }
    }
    
    private fun performScrollGesture(isScrollUp: Boolean) {
        val path = Path()
        // Use screen dimensions for gesture
        // For scroll up: swipe from top to bottom (content moves up)
        // For scroll down: swipe from bottom to top (content moves down)
        if (isScrollUp) {
            path.moveTo(1000f, 1000f) // Start from top
            path.lineTo(1000f, 2000f) // End at bottom
        } else {
            path.moveTo(1000f, 2000f) // Start from bottom
            path.lineTo(1000f, 1000f) // End at top
        }
        
        val gestureBuilder = GestureDescription.Builder()
        val strokeDescription = GestureDescription.StrokeDescription(path, 100L, 100L)
        val gestureDescription = gestureBuilder.addStroke(strokeDescription).build()
        
        accessibilityService.dispatchGesture(gestureDescription, ScrollGestureCallback(isScrollUp), null)
    }
}

