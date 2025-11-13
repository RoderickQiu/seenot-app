package com.roderickqiu.seenot.data

import android.content.Context
import com.roderickqiu.seenot.data.MonitoringApp
import com.roderickqiu.seenot.data.TimeConstraint

class MonitoringAppRepository(private val context: Context) {

    private val dataStore = AppDataStore(context)

    /**
     * Get all monitoring apps
     */
    fun getAllApps(): List<MonitoringApp> {
        val savedApps = dataStore.loadMonitoringApps()
        
        // If this is the first launch, initialize with default data
        if (dataStore.isFirstLaunch()) {
            val defaultApps = getDefaultApps()
            dataStore.saveMonitoringApps(defaultApps)
            dataStore.markAsLaunched()
            return defaultApps
        }
        
        return savedApps
    }

    /**
     * Get default apps for first launch
     */
    private fun getDefaultApps(): List<MonitoringApp> {
        return listOf(
            MonitoringApp(
                name = "知乎",
                isEnabled = false,
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
                            type = ConditionType.ON_CONTENT,
                            parameter = "在看公众号文章"
                        ),
                        action = RuleAction(
                            type = ActionType.REMIND,
                            parameter = "你已经连续看了3分钟公众号文章"
                        ),
                        timeConstraint = TimeConstraint.Continuous(minutes = 3)
                    )
                )
            ),
            MonitoringApp(
                name = "QQ",
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
                            parameter = "QQ空间的动态列表"
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
                            parameter = "整页长的商品推荐瀑布流列表"
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
        val currentApps = dataStore.loadMonitoringApps().toMutableList()
        currentApps.add(app)
        dataStore.saveMonitoringApps(currentApps)
    }

    /**
     * Update an existing monitoring app
     */
    fun updateApp(app: MonitoringApp) {
        dataStore.updateMonitoringApp(app)
    }

    /**
     * Delete a monitoring app
     */
    fun deleteApp(appId: String) {
        dataStore.deleteMonitoringApp(appId)
    }

    /**
     * Delete a specific rule from a monitoring app
     */
    fun deleteRule(appId: String, ruleId: String) {
        dataStore.deleteRule(appId, ruleId)
    }
}
