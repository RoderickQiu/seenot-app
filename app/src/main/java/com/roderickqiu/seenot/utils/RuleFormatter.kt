package com.roderickqiu.seenot.utils

import android.content.Context
import com.roderickqiu.seenot.R
import com.roderickqiu.seenot.data.ActionType
import com.roderickqiu.seenot.data.ConditionType
import com.roderickqiu.seenot.data.Rule
import com.roderickqiu.seenot.data.TimeConstraint

/**
 * Utility class for formatting rules with i18n support
 */
object RuleFormatter {
    
    // Maximum characters to display for condition parameter in list view
    private const val MAX_CONDITION_PARAMETER_LENGTH = 15

    /**
     * Format a rule for display using i18n strings
     */
    fun formatRule(context: Context, rule: Rule): String {
        val conditionText = formatCondition(context, rule.condition)
        val actionText = formatAction(context, rule.action)
        
        val baseRule = when (rule.condition.type) {
            ConditionType.TIME_INTERVAL -> "$conditionText$actionText"
            ConditionType.ON_ENTER -> "$conditionText$actionText"
            ConditionType.ON_PAGE -> {
                val comma = getCommaForLanguage(context)
                "$conditionText$comma$actionText"
            }
        }
        
        // Append time constraint if present
        val timeConstraintText = formatTimeConstraint(context, rule.timeConstraint)
        return if (timeConstraintText.isNotEmpty()) {
            "$baseRule${context.getString(R.string.time_constraint_desc_separator)}$timeConstraintText${context.getString(R.string.time_constraint_desc_separator_end)}"
        } else {
            baseRule
        }
    }

    /**
     * Format a rule for list display with condition truncation only
     * Only the condition part is truncated to MAX_CONDITION_DISPLAY_LENGTH characters
     */
    fun formatRuleForList(context: Context, rule: Rule): String {
        // Format condition with truncation for list display
        val conditionText = formatConditionForList(context, rule.condition)
        val actionText = formatAction(context, rule.action)
        
        val baseRule = when (rule.condition.type) {
            ConditionType.TIME_INTERVAL -> "$conditionText$actionText"
            ConditionType.ON_ENTER -> "$conditionText$actionText"
            ConditionType.ON_PAGE -> {
                val comma = getCommaForLanguage(context)
                "$conditionText$comma$actionText"
            }
        }
        
        // Append time constraint if present (no truncation)
        val timeConstraintText = formatTimeConstraint(context, rule.timeConstraint)
        return if (timeConstraintText.isNotEmpty()) {
            "$baseRule${context.getString(R.string.time_constraint_desc_separator)}$timeConstraintText${context.getString(R.string.time_constraint_desc_separator_end)}"
        } else {
            baseRule
        }
    }
    
    /**
     * Format condition for list display with parameter truncation only
     * Only the parameter/description part is truncated, not the condition type itself
     */
    private fun formatConditionForList(
        context: Context,
        condition: com.roderickqiu.seenot.data.RuleCondition
    ): String {
        return when (condition.type) {
            ConditionType.TIME_INTERVAL -> {
                val interval = condition.timeInterval ?: 0
                context.getString(R.string.condition_time_interval, interval)
            }

            ConditionType.ON_ENTER -> {
                context.getString(R.string.condition_on_enter)
            }

            ConditionType.ON_PAGE -> {
                val parameter = condition.parameter ?: ""
                // Truncate only the parameter/description part to MAX_CONDITION_PARAMETER_LENGTH
                val truncatedParameter = if (parameter.length > MAX_CONDITION_PARAMETER_LENGTH) {
                    parameter.take(MAX_CONDITION_PARAMETER_LENGTH) + "..."
                } else {
                    parameter
                }
                context.getString(R.string.condition_on_page, truncatedParameter)
            }
        }
    }

    /**
     * Format condition part of a rule
     */
    private fun formatCondition(
        context: Context,
        condition: com.roderickqiu.seenot.data.RuleCondition
    ): String {
        return when (condition.type) {
            ConditionType.TIME_INTERVAL -> {
                val interval = condition.timeInterval ?: 0
                context.getString(R.string.condition_time_interval, interval)
            }

            ConditionType.ON_ENTER -> {
                context.getString(R.string.condition_on_enter)
            }

            ConditionType.ON_PAGE -> {
                val parameter = condition.parameter ?: ""
                // Truncate parameter if too long for display
                val truncatedParameter = if (parameter.length > 30) {
                    parameter.take(30) + "..."
                } else {
                    parameter
                }
                context.getString(R.string.condition_on_page, truncatedParameter)
            }
        }
    }

    /**
     * Format action part of a rule
     * Note: Action parameters are not displayed in the list view to keep it concise
     */
    private fun formatAction(
        context: Context,
        action: com.roderickqiu.seenot.data.RuleAction
    ): String {
        return when (action.type) {
            ActionType.REMIND -> {
                context.getString(R.string.action_remind)
            }

            ActionType.AUTO_CLICK -> {
                context.getString(R.string.action_auto_click_label)
            }

            ActionType.AUTO_SCROLL_UP -> {
                context.getString(R.string.action_auto_scroll_up)
            }

            ActionType.AUTO_SCROLL_DOWN -> {
                context.getString(R.string.action_auto_scroll_down)
            }

            ActionType.AUTO_BACK -> {
                context.getString(R.string.action_auto_back)
            }

            ActionType.ASK -> {
                context.getString(R.string.action_ask)
            }
        }
    }
    
    /**
     * Format time constraint part of a rule (short version for list display)
     */
    private fun formatTimeConstraint(
        context: Context,
        timeConstraint: TimeConstraint?
    ): String {
        if (timeConstraint == null) {
            return ""
        }
        
        return when (timeConstraint) {
            is TimeConstraint.Continuous -> {
                context.getString(R.string.time_constraint_continuous_desc, formatMinutes(timeConstraint.minutes))
            }
            is TimeConstraint.DailyTotal -> {
                context.getString(R.string.time_constraint_daily_total_desc, formatMinutes(timeConstraint.minutes))
            }
            is TimeConstraint.RecentTotal -> {
                context.getString(R.string.time_constraint_recent_total_desc, timeConstraint.hours, formatMinutes(timeConstraint.minutes))
            }
        }
    }
    
    /**
     * Format minutes value: show as integer if whole number, otherwise show decimal
     */
    fun formatMinutes(minutes: Double): String {
        return if (minutes % 1.0 == 0.0) {
            minutes.toInt().toString()
        } else {
            minutes.toString()
        }
    }
    
    /**
     * Get the appropriate comma for the current language
     */
    private fun getCommaForLanguage(context: Context): String {
        val locale = context.resources.configuration.locales[0]
        return when (locale.language) {
            "zh" -> "，" // Chinese comma
            else -> ", " // English comma with space
        }
    }
    
}
