package com.roderickqiu.seenot.data

/** Represents a monitoring rule with condition and action */
data class Rule(val condition: RuleCondition, val action: RuleAction)

/** Represents the condition part of a rule */
data class RuleCondition(
    val type: ConditionType,
    val timeInterval: Int? = null, // in minutes, null if not applicable
    val parameter: String? = null // app-specific parameter for ON_PAGE and ON_CONTENT
)

/** Represents the action part of a rule */
data class RuleAction(
    val type: ActionType,
    val parameter: String? = null // app-specific parameter for actions that need it
)

/** Types of conditions */
enum class ConditionType {
    TIME_INTERVAL, // every X minutes
    ON_ENTER, // every time enter
    ON_PAGE, // if on specific page
    ON_CONTENT // if content about specific topic
}

/** Types of actions */
enum class ActionType {
    REMIND,
    AUTO_CLICK,
    AUTO_SCROLL_UP,
    AUTO_SCROLL_DOWN,
    AUTO_BACK,
    ASK
}
