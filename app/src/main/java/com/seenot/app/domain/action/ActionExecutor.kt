package com.seenot.app.domain.action

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.widget.Toast
import com.seenot.app.data.model.InterventionLevel
import com.seenot.app.domain.SessionConstraint
import com.seenot.app.service.SeenotAccessibilityService

/**
 * Action Executor - Handles intervention actions
 *
 * Actions:
 * - GENTLE: Toast notification + HUD highlight
 * - MODERATE: Toast + Auto-back
 * - STRICT: Go directly to home screen
 *
 * Features:
 * - Action deduplication (prevent loops)
 * - Cooldown period after forced actions
 * - "Allow once" override for false positives
 */
class ActionExecutor(private val context: Context) {

    companion object {
        private const val TAG = "ActionExecutor"

        // Cooldown after forced actions (ms)
        private const val COOLDOWN_MS = 30_000L // 30 seconds

        // Minimum time between same action types
        private const val ACTION_THROTTLE_MS = 5_000L // 5 seconds
    }

    private var lastActionTime = 0L
    private var lastActionType: ActionType? = null
    private var cooldownEndTime = 0L

    // Callback for HUD updates
    private var onViolationWarning: ((String) -> Unit)? = null
    private var onActionTaken: ((ActionType) -> Unit)? = null

    /**
     * Execute intervention based on constraint and confidence
     */
    fun executeIntervention(
        constraint: SessionConstraint,
        confidence: Double
    ) {
        Log.d(TAG, "=== executeIntervention called ===")
        Log.d(TAG, "Constraint: ${constraint.description}")
        Log.d(TAG, "Type: ${constraint.type}, Intervention: ${constraint.interventionLevel}")
        Log.d(TAG, "Confidence: $confidence")

        // Check cooldown
        if (System.currentTimeMillis() < cooldownEndTime) {
            Log.d(TAG, "❌ In cooldown period (until ${cooldownEndTime - System.currentTimeMillis()}ms left), skipping action")
            return
        } else {
            Log.d(TAG, "✅ Cooldown passed, can execute action")
        }

        // Check throttle
        val now = System.currentTimeMillis()
        if (now - lastActionTime < ACTION_THROTTLE_MS && lastActionType == ActionType.GO_HOME) {
            Log.d(TAG, "❌ Action throttled (last action ${now - lastActionTime}ms ago), skipping")
            return
        }

        val interventionLevel = constraint.interventionLevel
        Log.d(TAG, "Intervention level: $interventionLevel")

        // Determine action based on confidence and intervention level
        val action = determineAction(interventionLevel, confidence)
        Log.d(TAG, "→ Determined action: $action")

        executeAction(action, constraint.description)
    }

    /**
     * Determine which action to take
     */
    private fun determineAction(
        level: InterventionLevel,
        confidence: Double
    ): ActionType {
        val action = when (level) {
            InterventionLevel.GENTLE -> {
                Log.d(TAG, "[determineAction] GENTLE level → TOAST")
                ActionType.TOAST
            }

            InterventionLevel.MODERATE -> {
                if (confidence >= 0.9) {
                    Log.d(TAG, "[determineAction] MODERATE level, confidence=$confidence ≥ 0.9 → AUTO_BACK")
                    ActionType.AUTO_BACK
                } else {
                    Log.d(TAG, "[determineAction] MODERATE level, confidence=$confidence < 0.9 → TOAST")
                    ActionType.TOAST
                }
            }

            InterventionLevel.STRICT -> {
                when {
                    confidence >= 0.9 -> {
                        Log.d(TAG, "[determineAction] STRICT level, confidence=$confidence ≥ 0.9 → GO_HOME")
                        ActionType.GO_HOME
                    }
                    confidence >= 0.7 -> {
                        Log.d(TAG, "[determineAction] STRICT level, confidence=$confidence ≥ 0.7 → AUTO_BACK")
                        ActionType.AUTO_BACK
                    }
                    else -> {
                        Log.d(TAG, "[determineAction] STRICT level, confidence=$confidence < 0.7 → TOAST")
                        ActionType.TOAST
                    }
                }
            }
        }
        return action
    }

    /**
     * Execute the action
     */
    private fun executeAction(action: ActionType, reason: String) {
        val now = System.currentTimeMillis()
        lastActionTime = now
        lastActionType = action

        Log.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        Log.d(TAG, "🎯 EXECUTING ACTION: $action")
        Log.d(TAG, "📋 Reason: $reason")
        Log.d(TAG, "⏰ Timestamp: $now")
        Log.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

        when (action) {
            ActionType.TOAST -> executeToast(reason)
            ActionType.AUTO_BACK -> executeAutoBack()
            ActionType.GO_HOME -> executeGoHome()
            ActionType.HUD_HIGHLIGHT -> executeHudHighlight(reason)
            ActionType.VIBRATE -> executeVibrate()
        }

        // Set cooldown for forced actions
        if (action == ActionType.AUTO_BACK || action == ActionType.GO_HOME) {
            cooldownEndTime = now + COOLDOWN_MS
            Log.d(TAG, "⏳ Set cooldown: ${COOLDOWN_MS}ms (until ${cooldownEndTime})")
        } else {
            Log.d(TAG, "⏭️ No cooldown for action: $action")
        }

        // Notify listeners
        onActionTaken?.invoke(action)
    }

    /**
     * Show toast notification
     */
    private fun executeToast(message: String) {
        Log.d(TAG, "[executeToast] Showing toast: $message")
        Toast.makeText(
            context,
            "⚠️ 注意: $message",
            Toast.LENGTH_SHORT
        ).show()
        Log.d(TAG, "[executeToast] Toast shown ✓")
    }

    /**
     * Perform auto-back gesture
     */
    private fun executeAutoBack() {
        Log.d(TAG, "[executeAutoBack] Attempting auto-back gesture...")
        val service = SeenotAccessibilityService.instance
        if (service != null) {
            service.performBackGesture { success ->
                Log.d(TAG, "[executeAutoBack] Auto-back gesture result: ${if (success) "✅ SUCCESS" else "❌ FAILED"}")
            }
        } else {
            Log.w(TAG, "[executeAutoBack] AccessibilityService is null, cannot perform gesture")
        }
    }

    /**
     * Go to home screen
     */
    private fun executeGoHome() {
        Log.d(TAG, "[executeGoHome] Attempting to go home...")
        val service = SeenotAccessibilityService.instance
        if (service != null) {
            service.performHomeGesture { success ->
                Log.d(TAG, "[executeGoHome] Go home gesture result: ${if (success) "✅ SUCCESS" else "❌ FAILED"}")
            }
        } else {
            Log.w(TAG, "[executeGoHome] AccessibilityService is null, cannot perform gesture")
        }
    }

    /**
     * Highlight HUD with warning
     */
    private fun executeHudHighlight(message: String) {
        onViolationWarning?.invoke(message)
    }

    /**
     * Vibrate for attention
     */
    private fun executeVibrate() {
        try {
            val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vibrator.vibrate(
                    VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(200)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Vibration failed", e)
        }
    }

    /**
     * Allow this once - override for false positives
     */
    fun allowOnce() {
        Log.d(TAG, "Allow once triggered")
        cooldownEndTime = System.currentTimeMillis() + COOLDOWN_MS
    }

    /**
     * Set violation warning callback
     */
    fun setOnViolationWarning(callback: (String) -> Unit) {
        onViolationWarning = callback
    }

    /**
     * Set action taken callback
     */
    fun setOnActionTaken(callback: (ActionType) -> Unit) {
        onActionTaken = callback
    }

    /**
     * Clear cooldown (for testing)
     */
    fun clearCooldown() {
        cooldownEndTime = 0
    }
}

/**
 * Action types for interventions
 */
enum class ActionType {
    TOAST,           // Show toast notification
    AUTO_BACK,       // Perform back gesture
    GO_HOME,         // Go to home screen
    HUD_HIGHLIGHT,   // Highlight HUD
    VIBRATE          // Vibrate device
}
