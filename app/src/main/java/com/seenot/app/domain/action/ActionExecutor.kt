package com.seenot.app.domain.action

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import com.seenot.app.data.model.InterventionLevel
import com.seenot.app.data.repository.RuleRecordRepository
import com.seenot.app.domain.SessionConstraint
import com.seenot.app.service.SeenotAccessibilityService
import com.seenot.app.utils.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
 * - Action recording to database
 */
class ActionExecutor(private val context: Context) {

    companion object {
        private const val TAG = "ActionExecutor"

        // Cooldown after forced actions (ms)
        private const val COOLDOWN_MS = 30_000L // 30 seconds

        // Minimum time between same action types
        private const val ACTION_THROTTLE_MS = 5_000L // 5 seconds
    }

    private val ruleRecordRepository = RuleRecordRepository(context)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

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
        confidence: Double,
        reason: String = "violation", // "violation" or "timeout"
        appName: String = "unknown",
        packageName: String? = null
    ) {
        Logger.d(TAG, "=== executeIntervention called ===")
        Logger.d(TAG, "Constraint: ${constraint.description}")
        Logger.d(TAG, "Type: ${constraint.type}, Intervention: ${constraint.interventionLevel}")
        Logger.d(TAG, "Confidence: $confidence")
        Logger.d(TAG, "Reason: $reason")

        // Check cooldown
        if (System.currentTimeMillis() < cooldownEndTime) {
            Logger.d(TAG, "❌ In cooldown period (until ${cooldownEndTime - System.currentTimeMillis()}ms left), skipping action")
            return
        } else {
            Logger.d(TAG, "✅ Cooldown passed, can execute action")
        }

        // Check throttle
        val now = System.currentTimeMillis()
        if (now - lastActionTime < ACTION_THROTTLE_MS && lastActionType == ActionType.GO_HOME) {
            Logger.d(TAG, "❌ Action throttled (last action ${now - lastActionTime}ms ago), skipping")
            return
        }

        val interventionLevel = constraint.interventionLevel
        Logger.d(TAG, "Intervention level: $interventionLevel")

        // Determine action based on confidence and intervention level
        val action = determineAction(interventionLevel, confidence)
        Logger.d(TAG, "→ Determined action: $action")

        executeAction(action, constraint, reason, appName, packageName)
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
                Logger.d(TAG, "[determineAction] GENTLE level → TOAST")
                ActionType.TOAST
            }

            InterventionLevel.MODERATE -> {
                if (confidence >= 0.9) {
                    Logger.d(TAG, "[determineAction] MODERATE level, confidence=$confidence ≥ 0.9 → AUTO_BACK")
                    ActionType.AUTO_BACK
                } else {
                    Logger.d(TAG, "[determineAction] MODERATE level, confidence=$confidence < 0.9 → TOAST")
                    ActionType.TOAST
                }
            }

            InterventionLevel.STRICT -> {
                when {
                    confidence >= 0.9 -> {
                        Logger.d(TAG, "[determineAction] STRICT level, confidence=$confidence ≥ 0.9 → GO_HOME")
                        ActionType.GO_HOME
                    }
                    confidence >= 0.7 -> {
                        Logger.d(TAG, "[determineAction] STRICT level, confidence=$confidence ≥ 0.7 → AUTO_BACK")
                        ActionType.AUTO_BACK
                    }
                    else -> {
                        Logger.d(TAG, "[determineAction] STRICT level, confidence=$confidence < 0.7 → TOAST")
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
    private fun executeAction(action: ActionType, constraint: SessionConstraint, reason: String, appName: String, packageName: String?) {
        val now = System.currentTimeMillis()
        lastActionTime = now
        lastActionType = action

        Logger.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        Logger.d(TAG, "🎯 EXECUTING ACTION: $action")
        Logger.d(TAG, "📋 Constraint: ${constraint.description}")
        Logger.d(TAG, "📋 Type: ${constraint.type}")
        Logger.d(TAG, "📋 Reason: $reason")
        Logger.d(TAG, "⏰ Timestamp: $now")
        Logger.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

        when (action) {
            ActionType.TOAST -> executeToast(constraint)
            ActionType.AUTO_BACK -> executeAutoBack(constraint)
            ActionType.GO_HOME -> executeGoHome(constraint)
            ActionType.HUD_HIGHLIGHT -> executeHudHighlight(constraint.description)
            ActionType.VIBRATE -> executeVibrate()
        }

        // Record action to database
        recordAction(constraint, action, reason, now, appName, packageName)

        // Set cooldown for forced actions
        if (action == ActionType.AUTO_BACK || action == ActionType.GO_HOME) {
            cooldownEndTime = now + COOLDOWN_MS
            Logger.d(TAG, "⏳ Set cooldown: ${COOLDOWN_MS}ms (until ${cooldownEndTime})")
        } else {
            Logger.d(TAG, "⏭️ No cooldown for action: $action")
        }

        // Notify listeners
        onActionTaken?.invoke(action)
    }

    /**
     * Record action to database
     */
    private fun recordAction(constraint: SessionConstraint, action: ActionType, reason: String, timestamp: Long, appName: String, packageName: String?) {
        scope.launch {
            try {
                val record = com.seenot.app.data.model.RuleRecord(
                    sessionId = 0, // TODO: get actual session ID
                    appName = appName,
                    packageName = packageName,
                    constraintId = constraint.id.toLongOrNull(),
                    constraintType = constraint.type,
                    constraintContent = constraint.description,
                    isConditionMatched = false, // Action means something triggered
                    actionType = action.name,
                    actionReason = reason,
                    actionTimestamp = timestamp
                )
                ruleRecordRepository.saveRecord(record)
                Logger.d(TAG, "💾 Recorded action: ${action.name} for ${constraint.description}")
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to record action", e)
            }
        }
    }

    /**
     * Show toast notification
     */
    private fun executeToast(constraint: SessionConstraint) {
        val message = when (constraint.type) {
            com.seenot.app.data.model.ConstraintType.TIME_CAP -> {
                // TIME_CAP: show time limit reached message
                val timeLimit = constraint.timeLimitMs?.let { ms ->
                    val minutes = ms / 60000
                    val seconds = (ms % 60000) / 1000
                    when {
                        minutes > 0 && seconds > 0 -> "${minutes}分${seconds}秒"
                        minutes > 0 -> "${minutes}分钟"
                        else -> "${seconds}秒"
                    }
                } ?: "时间"
                "⏰ ${constraint.description} 已达 $timeLimit"
            }
            com.seenot.app.data.model.ConstraintType.DENY -> {
                // DENY: show violation warning
                "⚠️ 注意: ${constraint.description}"
            }
            com.seenot.app.data.model.ConstraintType.ALLOW -> {
                // ALLOW: show out of scope warning
                "⚠️ 注意: 不在允许范围内"
            }
        }

        Logger.d(TAG, "[executeToast] Showing toast: $message")
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        Logger.d(TAG, "[executeToast] Toast shown ✓")
    }

    /**
     * Perform auto-back gesture
     */
    private fun executeAutoBack(constraint: SessionConstraint) {
        Logger.d(TAG, "[executeAutoBack] Attempting auto-back gesture...")

        // Show toast before action
        val message = when (constraint.type) {
            com.seenot.app.data.model.ConstraintType.TIME_CAP -> "⏰ 时间到，自动返回"
            else -> "⚠️ 违规，自动返回"
        }
        
        scope.launch {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }

        val service = SeenotAccessibilityService.instance
        if (service != null) {
            service.performBackGesture { success ->
                Logger.d(TAG, "[executeAutoBack] Auto-back gesture result: ${if (success) "✅ SUCCESS" else "❌ FAILED"}")
            }
        } else {
            Logger.w(TAG, "[executeAutoBack] AccessibilityService is null, cannot perform gesture")
        }
    }

    /**
     * Go to home screen
     */
    private fun executeGoHome(constraint: SessionConstraint) {
        Logger.d(TAG, "[executeGoHome] Attempting to go home...")

        // Show toast before action
        val message = when (constraint.type) {
            com.seenot.app.data.model.ConstraintType.TIME_CAP -> "⏰ 时间到，返回主屏幕"
            else -> "⚠️ 严重违规，返回主屏幕"
        }
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

        val service = SeenotAccessibilityService.instance
        if (service != null) {
            service.performHomeGesture { success ->
                Logger.d(TAG, "[executeGoHome] Go home gesture result: ${if (success) "✅ SUCCESS" else "❌ FAILED"}")
            }
        } else {
            Logger.w(TAG, "[executeGoHome] AccessibilityService is null, cannot perform gesture")
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
            Logger.e(TAG, "Vibration failed", e)
        }
    }

    /**
     * Allow this once - override for false positives
     */
    fun allowOnce() {
        Logger.d(TAG, "Allow once triggered")
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
