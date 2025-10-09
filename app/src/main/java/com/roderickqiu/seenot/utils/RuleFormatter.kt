package com.roderickqiu.seenot.utils

import android.content.Context
import com.roderickqiu.seenot.R
import com.roderickqiu.seenot.data.ActionType
import com.roderickqiu.seenot.data.ConditionType
import com.roderickqiu.seenot.data.Rule

/**
 * Utility class for formatting rules with i18n support
 */
object RuleFormatter {

    /**
     * Format a rule for display using i18n strings
     */
    fun formatRule(context: Context, rule: Rule): String {
        val conditionText = formatCondition(context, rule.condition)
        val actionText = formatAction(context, rule.action)
        
        return when (rule.condition.type) {
            ConditionType.TIME_INTERVAL -> "$conditionText$actionText"
            ConditionType.ON_ENTER -> "$conditionText$actionText"
            ConditionType.ON_PAGE -> {
                val comma = getCommaForLanguage(context)
                "$conditionText$comma$actionText"
            }
            ConditionType.ON_CONTENT -> {
                val comma = getCommaForLanguage(context)
                "$conditionText$comma$actionText"
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
                context.getString(R.string.condition_on_page, parameter)
            }

            ConditionType.ON_CONTENT -> {
                val parameter = condition.parameter ?: ""
                context.getString(R.string.condition_on_content, parameter)
            }
        }
    }

    /**
     * Format action part of a rule
     */
    private fun formatAction(
        context: Context,
        action: com.roderickqiu.seenot.data.RuleAction
    ): String {
        return when (action.type) {
            ActionType.REMIND -> {
                if (action.parameter != null) {
                    val comma = getCommaForLanguage(context)
                    "${context.getString(R.string.action_remind)}$comma${action.parameter}"
                } else {
                    context.getString(R.string.action_remind)
                }
            }

            ActionType.AUTO_CLICK -> {
                val parameter = action.parameter ?: ""
                context.getString(R.string.action_auto_click, parameter)
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
