package com.roderickqiu.seenot.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.roderickqiu.seenot.data.ActionExecution
import com.roderickqiu.seenot.data.ActionExecutionRepo
import com.roderickqiu.seenot.data.ActionType
import com.roderickqiu.seenot.data.Rule
import android.widget.Toast
import com.roderickqiu.seenot.components.AskOverlay
import com.roderickqiu.seenot.utils.GenericUtils
import com.roderickqiu.seenot.utils.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ActionExecutor(
    private val accessibilityService: AccessibilityService,
    private val notificationManager: NotificationManager,
    private val context: android.content.Context,
    private val actionExecutionRepo: ActionExecutionRepo = ActionExecutionRepo(context)
) {
    // Action deduplication related fields
    private val recentActions = mutableMapOf<String, Long>()
    private val deduplicationWindowMs = 3000L // 3 seconds deduplication window
    
    private inner class ClickGestureCallback(
        private val x: Float,
        private val y: Float,
        private val rule: Rule,
        private val appName: String
    ) : android.accessibilityservice.AccessibilityService.GestureResultCallback() {
        override fun onCompleted(gestureDescription: GestureDescription?) {
            super.onCompleted(gestureDescription)
            notificationManager.showToast(context.getString(com.roderickqiu.seenot.R.string.action_auto_click_label) + ": ($x, $y)", Toast.LENGTH_SHORT)
            Logger.d("A11yService", "AUTO_CLICK performed at coordinate: ($x, $y)")
            recordActionExecution(rule, appName, true, null)
        }

        override fun onCancelled(gestureDescription: GestureDescription?) {
            super.onCancelled(gestureDescription)
            notificationManager.showToast(context.getString(com.roderickqiu.seenot.R.string.action_auto_click_label) + ": " + context.getString(com.roderickqiu.seenot.R.string.click_failed), Toast.LENGTH_SHORT)
            Logger.d("A11yService", "AUTO_CLICK cancelled at coordinate: ($x, $y)")
            recordActionExecution(rule, appName, false, "Click gesture was cancelled")
        }
    }
    
    private inner class ScrollGestureCallback(
        private val isScrollUp: Boolean,
        private val rule: Rule? = null,
        private val appName: String? = null
    ) : android.accessibilityservice.AccessibilityService.GestureResultCallback() {
        override fun onCompleted(gestureDescription: GestureDescription?) {
            super.onCompleted(gestureDescription)
            Logger.d("A11yService", "Scroll gesture completed: ${if (isScrollUp) "UP" else "DOWN"}")
            // Scroll recording is handled in the caller (executeAction) since we have rule/appName there
        }

        override fun onCancelled(gestureDescription: GestureDescription?) {
            super.onCancelled(gestureDescription)
            Logger.d("A11yService", "Scroll gesture cancelled: ${if (isScrollUp) "UP" else "DOWN"}")
            // Record failure if we have rule context
            if (rule != null && appName != null) {
                recordActionExecution(rule, appName, false, "Scroll gesture was cancelled")
            }
        }
    }
    
    fun executeAction(rule: Rule, appName: String) {
        // Action deduplication check
        val actionKey = "${appName}_${rule.id}_${rule.action.type}"
        val now = System.currentTimeMillis()
        val lastExecuteTime = recentActions[actionKey] ?: 0

        if (now - lastExecuteTime < deduplicationWindowMs) {
            Logger.d("A11yService", "Skipping duplicate action $actionKey, last executed ${now - lastExecuteTime}ms ago")
            return
        }

        // Update execution timestamp
        recentActions[actionKey] = now

        var isSuccess = true
        var errorMessage: String? = null

        when (rule.action.type) {
            ActionType.REMIND -> {
                val message = rule.action.parameter ?: "Reminder"
                notificationManager.showToast("$appName: $message", Toast.LENGTH_LONG)
                Logger.d("A11yService", "Triggered REMIND action: $message")
                recordActionExecution(rule, appName, isSuccess, errorMessage)
            }
            ActionType.AUTO_BACK -> {
                val reason = rule.condition.parameter ?: ""
                val truncatedReason = if (reason.length > GenericUtils.TOAST_TEXT_MAX_LENGTH) {
                    reason.take(GenericUtils.TOAST_TEXT_MAX_LENGTH) + "..."
                } else {
                    reason
                }
                val toastMessage = if (truncatedReason.isNotEmpty()) {
                    context.getString(com.roderickqiu.seenot.R.string.action_auto_back_label) + ": $truncatedReason"
                } else {
                    context.getString(com.roderickqiu.seenot.R.string.action_auto_back_label)
                }
                notificationManager.showToast(toastMessage, Toast.LENGTH_SHORT)
                Logger.d("A11yService", "Triggered AUTO_BACK action, reason: $reason")
                val success = accessibilityService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                if (!success) {
                    accessibilityService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                    notificationManager.showToast(context.getString(com.roderickqiu.seenot.R.string.back_last_failed), Toast.LENGTH_SHORT)
                    Logger.d("A11yService", "AUTO_BACK failed, went to home after toast")
                    isSuccess = false
                    errorMessage = "Back action failed, fell back to home"
                } else {
                    Logger.d("A11yService", "AUTO_BACK successful")
                }
                recordActionExecution(rule, appName, isSuccess, errorMessage)
            }
            ActionType.AUTO_CLICK -> {
                val parameter = rule.action.parameter
                if (parameter != null && parameter.isNotEmpty()) {
                    performAutoClickAtCoordinate(parameter, rule, appName)
                    // Note: performAutoClickAtCoordinate handles its own recording via callbacks
                } else {
                    notificationManager.showToast(context.getString(com.roderickqiu.seenot.R.string.action_auto_click_label) + ": " + context.getString(com.roderickqiu.seenot.R.string.no_parameter), Toast.LENGTH_SHORT)
                    Logger.d("A11yService", "AUTO_CLICK triggered but no parameter provided")
                    isSuccess = false
                    errorMessage = "No coordinate parameter provided"
                    recordActionExecution(rule, appName, isSuccess, errorMessage)
                }
            }
            ActionType.AUTO_SCROLL_UP -> {
                performScrollGesture(true)
                val reason = rule.condition.parameter ?: ""
                val truncatedReason = if (reason.length > GenericUtils.TOAST_TEXT_MAX_LENGTH) {
                    reason.take(GenericUtils.TOAST_TEXT_MAX_LENGTH) + "..."
                } else {
                    reason
                }
                val toastMessage = if (truncatedReason.isNotEmpty()) {
                    context.getString(com.roderickqiu.seenot.R.string.action_auto_scroll_up_label) + ": $truncatedReason"
                } else {
                    context.getString(com.roderickqiu.seenot.R.string.action_auto_scroll_up_label)
                }
                notificationManager.showToast(toastMessage, Toast.LENGTH_SHORT)
                Logger.d("A11yService", "Triggered AUTO_SCROLL_UP action, reason: $reason")
                recordActionExecution(rule, appName, isSuccess, errorMessage)
            }
            ActionType.AUTO_SCROLL_DOWN -> {
                performScrollGesture(false)
                val reason = rule.condition.parameter ?: ""
                val truncatedReason = if (reason.length > GenericUtils.TOAST_TEXT_MAX_LENGTH) {
                    reason.take(GenericUtils.TOAST_TEXT_MAX_LENGTH) + "..."
                } else {
                    reason
                }
                val toastMessage = if (truncatedReason.isNotEmpty()) {
                    context.getString(com.roderickqiu.seenot.R.string.action_auto_scroll_down_label) + ": $truncatedReason"
                } else {
                    context.getString(com.roderickqiu.seenot.R.string.action_auto_scroll_down_label)
                }
                notificationManager.showToast(toastMessage, Toast.LENGTH_SHORT)
                Logger.d("A11yService", "Triggered AUTO_SCROLL_DOWN action, reason: $reason")
                recordActionExecution(rule, appName, isSuccess, errorMessage)
            }
            ActionType.ASK -> {
                // Show floating overlay for user to manage rule states
                val askOverlay = AskOverlay(
                    context = context,
                    appName = appName,
                    onRulesUpdated = { ruleStates ->
                        // Update rule states in constraint manager
                        val service = A11yService.getInstance()
                        if (service == null) {
                            Logger.e("A11yService", "A11yService instance is null!")
                            return@AskOverlay
                        }
                        val constraintManager = service.getConstraintManager()
                        if (constraintManager == null) {
                            Logger.e("A11yService", "ConstraintManager is null!")
                            return@AskOverlay
                        }
                        constraintManager.setMultipleRuleStates(ruleStates)
                        Logger.d("A11yService", "ASK action updated rule states: $ruleStates")
                        // Verify the states were set
                        val currentStates = constraintManager.getAllRuleStates()
                        Logger.d("A11yService", "Current rule states after update: $currentStates")
                    },
                    onDismiss = {
                        Logger.d("A11yService", "ASK overlay dismissed without changes")
                    }
                )
                askOverlay.show()
                Logger.d("A11yService", "ASK overlay shown for $appName")
                recordActionExecution(rule, appName, isSuccess, errorMessage)
            }
        }
    }

    /**
     * Record action execution to repository
     */
    private fun recordActionExecution(
        rule: Rule,
        appName: String,
        isSuccess: Boolean,
        errorMessage: String?
    ) {
        try {
            val execution = ActionExecution(
                appName = appName,
                packageName = null, // Can be enhanced if available
                ruleId = rule.id,
                actionType = rule.action.type,
                actionParameter = rule.action.parameter,
                conditionDescription = rule.condition.parameter,
                isSuccess = isSuccess,
                errorMessage = errorMessage
            )

            // Save asynchronously to avoid blocking
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    actionExecutionRepo.saveExecution(execution)
                    Logger.d("A11yService", "Recorded action execution: ${execution.id}")
                } catch (e: Exception) {
                    Logger.e("A11yService", "Failed to record action execution", e)
                }
            }
        } catch (e: Exception) {
            Logger.e("A11yService", "Error creating action execution record", e)
        }
    }

    fun showAskOverlay(appName: String) {
        // Show floating overlay for user to manage rule states (for askOnEnter setting)
        val askOverlay = AskOverlay(
            context = context,
            appName = appName,
            onRulesUpdated = { ruleStates ->
                // Update rule states in constraint manager
                val service = A11yService.getInstance()
                if (service == null) {
                    Logger.e("A11yService", "A11yService instance is null!")
                    return@AskOverlay
                }
                val constraintManager = service.getConstraintManager()
                if (constraintManager == null) {
                    Logger.e("A11yService", "ConstraintManager is null!")
                    return@AskOverlay
                }
                constraintManager.setMultipleRuleStates(ruleStates)
                Logger.d("A11yService", "ASK overlay (askOnEnter) updated rule states: $ruleStates")
                // Verify the states were set
                val currentStates = constraintManager.getAllRuleStates()
                Logger.d("A11yService", "Current rule states after update: $currentStates")
            },
            onDismiss = {
                Logger.d("A11yService", "ASK overlay (askOnEnter) dismissed without changes")
                // Re-show monitoring indicator to ensure it's on top
                com.roderickqiu.seenot.components.MonitoringIndicatorOverlay.show(context, appName) {
                    showAskOverlay(appName)
                }
            }
        )
        askOverlay.show()
        Logger.d("A11yService", "ASK overlay shown for $appName (askOnEnter)")
    }

    private fun performAutoClickAtCoordinate(parameter: String, rule: Rule, appName: String) {
        // Parse coordinate from parameter (format: "x,y")
        val parts = parameter.split(",")
        if (parts.size != 2) {
            notificationManager.showToast(context.getString(com.roderickqiu.seenot.R.string.action_auto_click_label) + ": " + context.getString(com.roderickqiu.seenot.R.string.invalid_coordinate), Toast.LENGTH_SHORT)
            Logger.d("A11yService", "AUTO_CLICK: invalid coordinate format: $parameter")
            recordActionExecution(rule, appName, false, "Invalid coordinate format: $parameter")
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

            accessibilityService.dispatchGesture(gestureDescription, ClickGestureCallback(x, y, rule, appName), null)
        } catch (e: NumberFormatException) {
            notificationManager.showToast(context.getString(com.roderickqiu.seenot.R.string.action_auto_click_label) + ": " + context.getString(com.roderickqiu.seenot.R.string.invalid_coordinate), Toast.LENGTH_SHORT)
            Logger.d("A11yService", "AUTO_CLICK: invalid coordinate values: $parameter", e)
            recordActionExecution(rule, appName, false, "Invalid coordinate values: ${e.message}")
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

    /**
     * Clean up expired action records to prevent memory leaks
     * Call this method periodically to clean up records beyond the deduplication window
     */
    fun cleanupExpiredActionRecords() {
        val now = System.currentTimeMillis()
        val expiredKeys = recentActions.filter { (_, timestamp) ->
            now - timestamp >= deduplicationWindowMs
        }.keys

        expiredKeys.forEach { key ->
            recentActions.remove(key)
        }

        if (expiredKeys.isNotEmpty()) {
            Logger.d("A11yService", "Cleaned up ${expiredKeys.size} expired action records")
        }
    }
}

