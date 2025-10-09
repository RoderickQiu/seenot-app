package com.roderickqiu.seenot.data

import com.roderickqiu.seenot.MonitoringApp

class MonitoringAppRepository {

    /**
     * Get all monitoring apps
     * TODO: Replace with actual data source (Room database, API, etc.)
     */
    fun getAllApps(): List<MonitoringApp> {
        return listOf(
            MonitoringApp(
                name = "知乎",
                rules = listOf(
                    Rule(
                        condition = RuleCondition(
                            type = ConditionType.TIME_INTERVAL,
                            timeInterval = 3
                        ),
                        action = RuleAction(
                            type = ActionType.REMIND
                        )
                    ),
                    Rule(
                        condition = RuleCondition(
                            type = ConditionType.ON_PAGE,
                            parameter = "首页"
                        ),
                        action = RuleAction(
                            type = ActionType.AUTO_CLICK,
                            parameter = "顶部"
                        )
                    ),
                    Rule(
                        condition = RuleCondition(
                            type = ConditionType.ON_ENTER
                        ),
                        action = RuleAction(
                            type = ActionType.ASK
                        )
                    )
                )
            ),
            MonitoringApp(
                name = "微信",
                rules = listOf(
                    Rule(
                        condition = RuleCondition(
                            type = ConditionType.TIME_INTERVAL,
                            timeInterval = 3
                        ),
                        action = RuleAction(
                            type = ActionType.REMIND,
                            parameter = "如果在看公众号"
                        )
                    )
                )
            ),
            MonitoringApp(
                name = "QQ",
                isEnabled = false,
                rules = listOf(
                    Rule(
                        condition = RuleCondition(
                            type = ConditionType.ON_ENTER
                        ),
                        action = RuleAction(
                            type = ActionType.ASK
                        )
                    ),
                    Rule(
                        condition = RuleCondition(
                            type = ConditionType.ON_PAGE,
                            parameter = "如果页面关于QQ空间"
                        ),
                        action = RuleAction(
                            type = ActionType.AUTO_BACK
                        )
                    )
                )
            ),
            MonitoringApp(
                name = "淘宝",
                rules = listOf(
                    Rule(
                        condition = RuleCondition(
                            type = ConditionType.ON_CONTENT,
                            parameter = "如果内容关于商品推荐"
                        ),
                        action = RuleAction(
                            type = ActionType.AUTO_SCROLL_UP
                        )
                    )
                )
            )
        )
    }

    /**
     * Add a new monitoring app
     */
    fun addApp(app: MonitoringApp) {
        // TODO: Implement database insertion
    }

    /**
     * Update an existing monitoring app
     */
    fun updateApp(app: MonitoringApp) {
        // TODO: Implement database update
    }

    /**
     * Delete a monitoring app
     */
    fun deleteApp(appId: String) {
        // TODO: Implement database deletion
    }
}
