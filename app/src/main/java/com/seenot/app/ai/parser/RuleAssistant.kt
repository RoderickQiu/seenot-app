package com.seenot.app.ai.parser

import android.content.Context
import com.seenot.app.utils.Logger
import com.google.gson.JsonParser
import com.seenot.app.config.ApiConfig
import com.seenot.app.data.model.ConstraintType
import com.seenot.app.data.model.InterventionLevel
import com.seenot.app.data.model.TimeScope
import com.seenot.app.domain.SessionConstraint
import com.seenot.app.domain.SessionManager
import com.alibaba.dashscope.aigc.generation.Generation
import com.alibaba.dashscope.aigc.generation.GenerationParam
import com.alibaba.dashscope.common.Message
import com.alibaba.dashscope.common.Role
import com.alibaba.dashscope.protocol.Protocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

class RuleAssistant(private val context: Context) {
    companion object { private const val TAG = "RuleAssistant" }
    private val generation = Generation(Protocol.HTTP.getValue(), "https://dashscope.aliyuncs.com/api/v1")

    suspend fun planAndExecute(
        userMessage: String,
        conversationHistory: List<ChatMessage>,
        selectedApp: AppInfo?,
        availableApps: List<AppInfo>,
        sessionManager: SessionManager
    ): ExecutionResult = withContext(Dispatchers.IO) {
        try {
            val maxRounds = 3
            var currentRound = 0
            val executionHistory = mutableListOf<String>()
            var finalResponse = ""

            while (currentRound < maxRounds) {
                currentRound++
                val prompt = buildReActPrompt(userMessage, conversationHistory, selectedApp, availableApps, sessionManager, executionHistory)
                val response = callLLM(prompt)
                Logger.d(TAG, "Round $currentRound LLM Response: $response")

                val actions = parseActions(response)
                if (actions.isEmpty()) {
                    val extracted = extractFinalResponse(response)
                    if (extracted.isNullOrBlank()) {
                        Logger.e(TAG, "LLM returned no actions and no response")
                        return@withContext ExecutionResult.Error("AI无法理解你的请求，请换个说法试试")
                    }
                    finalResponse = extracted
                    break
                }

                val results = actions.map { executeAction(it, availableApps, sessionManager) }
                val resultsText = results.joinToString("\n") {
                    when (it) {
                        is ActionResult.Success -> "✓ ${it.message}"
                        is ActionResult.Error -> "✗ ${it.message}"
                    }
                }
                executionHistory.add("Round $currentRound Actions:\n$resultsText")

                val llmResponse = extractFinalResponse(response)
                if (llmResponse != null && llmResponse.isNotBlank()) {
                    finalResponse = llmResponse
                    break
                }

                val hasTerminalAction = actions.any { it is AIAction.Chat || it is AIAction.Ask }
                if (hasTerminalAction && llmResponse.isNullOrBlank()) {
                    finalResponse = results.firstOrNull { it is ActionResult.Success }?.let { (it as ActionResult.Success).message } ?: ""
                    break
                }
            }

            if (finalResponse.isBlank() && executionHistory.isNotEmpty()) {
                Logger.d(TAG, "No final response, asking LLM to summarize")
                val summaryPrompt = """
用户问题: $userMessage

执行结果:
${executionHistory.joinToString("\n\n")}

请根据执行结果，用自然、简洁的语言回复用户。要求：
1. 不要提及"第一次"、"第二次"、"Round"等执行细节
2. 只告诉用户最终的结果
3. 如果有错误，简单说明原因
4. 直接输出回复内容，不要包含Thought、Action等格式
                """.trimIndent()
                val summaryResponse = callLLM(summaryPrompt)
                finalResponse = summaryResponse
                    .replace(Regex("Thought:.*?(?=\\n|$)", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("Action:.*?(?=\\n|$)", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("Action Input:.*?(?=\\n|$)", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("Final Response:.*?(?=\\n|$)", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("Round \\d+.*?(?=\\n|$)", RegexOption.IGNORE_CASE), "")
                    .trim()
            }

            if (finalResponse.isBlank()) {
                return@withContext ExecutionResult.Error("处理失败，请重试")
            }

            ExecutionResult.Success(emptyList(), finalResponse)
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to plan and execute", e)
            ExecutionResult.Error(e.message ?: "处理失败")
        }
    }
    private fun buildReActPrompt(userMessage: String, conversationHistory: List<ChatMessage>, selectedApp: AppInfo?, availableApps: List<AppInfo>, sessionManager: SessionManager, executionHistory: List<String> = emptyList()): String {
        val historyText = conversationHistory.takeLast(10).joinToString("\n") { msg -> "${if (msg.role == "user") "用户" else "助手"}: ${msg.content}" }
        val appsText = availableApps.take(15).joinToString("\n") { "- ${it.name} (${it.packageName})" }
        val rulesText = selectedApp?.let { app -> sessionManager.loadPresetRules(app.packageName).ifEmpty { null }?.joinToString("\n") { "- ${it.type.name}: ${it.description}" } } ?: "无"
        val executionHistoryText = if (executionHistory.isNotEmpty()) "\n## 执行历史\n" + executionHistory.joinToString("\n\n") else ""

        return """
你是 SeeNot AI 助手，一个智能的规则管理 Agent。你必须严格按照用户使用的语言回复（用户用中文你用中文，用户用英文你用英文）。

## 可用 Actions
1. **create_rule** - 创建新规则
   - app_name: 应用名称
   - rule_type: DENY/TIME_CAP
   - description: 规则描述（不要包含时间信息）
   - time_limit_minutes: 时间限制（支持整数和小数，如5, 7.5, 8.5）
   - intervention: GENTLE/MODERATE/STRICT
2. **update_rule** - 修改规则 (app_name, target_description, new_rule_type?, new_description?, new_time_limit_minutes?, new_intervention?)
3. **delete_rule** - 删除规则 (app_name, description)
4. **list_rules** - 查看规则 (app_name)
5. **list_apps** - 列出应用
6. **check_and_monitor_app** - 检查app是否安装并添加监控 (app_name)
7. **chat** - 聊天/回复用户 (response_text)
8. **ask** - 询问用户 (question)

## 当前状态
- 已选择应用: ${selectedApp?.name ?: "无"}
- 已监控应用: $appsText
- 当前规则: $rulesText$executionHistoryText

## 对话历史
$historyText

## 用户请求
$userMessage

## 重要规则

### 1. 区分查询和操作
- **查询类问题**（"有没有rule"、"查看规则"、"what rules"）：只用 list_rules，不要添加监控
- **操作类问题**（"监控xx"、"给xx加规则"、"限制xx"）：需要时才用 check_and_monitor_app

### 2. 检查应用是否在监控列表
availableApps 是当前监控的应用列表。只有在用户明确要求操作（创建/修改规则）时，才检查并添加监控：
- 用户说"监控抖音"或"给抖音加规则" → 使用 check_and_monitor_app
- 用户说"抖音有没有规则" → 直接使用 list_rules（不要添加监控）

### 3. 避免重复创建规则
在创建规则前，先检查是否已存在相似规则：
- 使用 list_rules 查看现有规则
- 如果已有相似规则，不要重复创建
- 一次只创建一个规则，不要在一个响应中输出多个 create_rule action

### 4. 多步骤任务
你可以执行多个action来完成任务。查看执行历史了解之前action的结果，然后决定下一步。
- 例如：用户说"监控抖音并限制10分钟" → 先 check_and_monitor_app → 再 create_rule
- 完成所有步骤后，在 Final Response 中总结结果

### 4. 不知道用户想做什么时
如果用户的意图不明确，不要返回"我明白了"这种模糊的话。使用 chat action 给出友好提示：
- 例如："我不太理解你的意思，你可以说「查看微信规则」或「给抖音加时间限制」"
- 例如："I'm not sure what you mean. Try things like 'what rules does WeChat have?' or 'set a time limit for Douyin'"

### 4. 回复要自然简洁
执行完action后，在 Final Response 中直接给出结果，不要说"已成功xxx"，直接说"xxx"：
- 错误："已成功删除微信中看朋友圈的时间限制规则"
- 正确："已删除「看朋友圈」规则" 或 "Done! Removed the '朋友圈' rule"

### 5. 时间限制必须精确
**重要**：time_limit_minutes 必须使用用户说的精确数值，支持小数：
- 用户说"7.5分钟" → time_limit_minutes: 7.5（不是7，不是8）
- 用户说"0.3分钟" → time_limit_minutes: 0.3
- 用户说"8.4分钟" → time_limit_minutes: 8.4
- description 不要包含时间信息，只描述行为

### 6. 输出格式
```
Thought: 你的理解
Action: action_name
Action Input: {"param": "value"}
```
如果还需要继续执行action，不要输出 Final Response。
如果任务完成，输出: Final Response: 你想说的话（直接说，不要加前缀）

注意：rule_type 用 DENY/TIME_CAP，intervention 用 GENTLE/MODERATE/STRICT
        """.trimIndent()
    }

    private fun parseActions(response: String): List<AIAction> {
        val actions = mutableListOf<AIAction>()
        Regex("""Action:\s*(\w+)[\s\n]*Action Input:\s*(\{[^}]+\})""", RegexOption.IGNORE_CASE).findAll(response).forEach { match ->
            try {
                val json = JsonParser.parseString(match.groupValues[2]).asJsonObject
                when (match.groupValues[1].lowercase()) {
                    "create_rule" -> json.get("app_name")?.asString?.let { app -> actions.add(AIAction.CreateRule(app, json.get("rule_type")?.asString ?: "DENY", json.get("description")?.asString ?: "", json.get("time_limit_minutes")?.asDouble, json.get("intervention")?.asString ?: "MODERATE")) }
                    "update_rule" -> json.get("app_name")?.asString?.let { app -> actions.add(AIAction.UpdateRule(app, json.get("target_description")?.asString ?: "", json.get("new_rule_type")?.asString, json.get("new_description")?.asString, json.get("new_time_limit_minutes")?.asDouble, json.get("new_intervention")?.asString)) }
                    "delete_rule" -> json.get("app_name")?.asString?.let { app -> actions.add(AIAction.DeleteRule(app, json.get("description")?.asString ?: "")) }
                    "list_rules" -> json.get("app_name")?.asString?.let { actions.add(AIAction.ListRules(it)) }
                    "list_apps" -> actions.add(AIAction.ListApps)
                    "check_and_monitor_app" -> json.get("app_name")?.asString?.let { actions.add(AIAction.CheckAndMonitorApp(it)) }
                    "chat" -> actions.add(AIAction.Chat(json.get("response_text")?.asString ?: "好的。"))
                    "ask" -> actions.add(AIAction.Ask(json.get("question")?.asString ?: "请问你想做什么？"))
                }
            } catch (e: Exception) { Logger.w(TAG, "Parse action error", e) }
        }
        return actions
    }

    private fun executeAction(action: AIAction, availableApps: List<AppInfo>, sessionManager: SessionManager): ActionResult {
        return when (action) {
            is AIAction.CheckAndMonitorApp -> {
                val pm = context.packageManager
                val installedApps = pm.getInstalledApplications(0)
                val searchTerm = action.appName.lowercase()
                
                val matchedApps = installedApps.filter { appInfo ->
                    val appName = pm.getApplicationLabel(appInfo).toString().lowercase()
                    val packageName = appInfo.packageName.lowercase()
                    appName.contains(searchTerm) || packageName.contains(searchTerm) || searchTerm.contains(appName)
                }.map { appInfo ->
                    AppInfo(
                        name = pm.getApplicationLabel(appInfo).toString(),
                        packageName = appInfo.packageName
                    )
                }
                
                when {
                    matchedApps.isEmpty() -> ActionResult.Error("未找到「${action.appName}」，请确认该应用已安装")
                    matchedApps.size == 1 -> {
                        val app = matchedApps[0]
                        if (availableApps.any { it.packageName == app.packageName }) {
                            ActionResult.Success("${app.name} 已在监控列表中")
                        } else {
                            sessionManager.addControlledApp(app.packageName)
                            ActionResult.Success("已添加 ${app.name} 到监控列表")
                        }
                    }
                    else -> {
                        val appList = matchedApps.take(5).joinToString(", ") { it.name }
                        ActionResult.Error("找到多个匹配的应用：$appList。请说得更具体一些")
                    }
                }
            }
            is AIAction.CreateRule -> {
                var app = availableApps.find { it.name == action.appName || it.packageName == action.appName }

                if (app == null) {
                    val pm = context.packageManager
                    val installedApps = pm.getInstalledApplications(0)
                    val searchTerm = action.appName.lowercase()

                    val matchedApps = installedApps.filter { appInfo ->
                        val appName = pm.getApplicationLabel(appInfo).toString().lowercase()
                        val packageName = appInfo.packageName.lowercase()
                        appName.contains(searchTerm) || packageName.contains(searchTerm) || searchTerm.contains(appName)
                    }.map { appInfo ->
                        AppInfo(
                            name = pm.getApplicationLabel(appInfo).toString(),
                            packageName = appInfo.packageName
                        )
                    }

                    when {
                        matchedApps.isEmpty() -> return ActionResult.Error("未找到「${action.appName}」，请确认该应用已安装")
                        matchedApps.size == 1 -> {
                            app = matchedApps[0]
                            sessionManager.addControlledApp(app.packageName)
                        }
                        else -> {
                            val appList = matchedApps.take(5).joinToString(", ") { it.name }
                            return ActionResult.Error("找到多个匹配的应用：$appList。请说得更具体一些")
                        }
                    }
                }

                val existingRules = sessionManager.loadPresetRules(app.packageName)
                val ruleType = when (action.ruleType.uppercase()) {
                    "TIME_CAP" -> ConstraintType.TIME_CAP
                    else -> ConstraintType.DENY
                }

                val isDuplicate = existingRules.any { existing ->
                    existing.description.trim().equals(action.description.trim(), ignoreCase = true) && existing.type == ruleType
                }

                if (isDuplicate) {
                    return ActionResult.Success("${app.name} 已有相似规则，无需重复创建")
                }

                val rule = SessionConstraint(
                    UUID.randomUUID().toString(),
                    ruleType,
                    action.description,
                    action.timeLimitMinutes?.times(60000)?.toLong(),
                    TimeScope.SESSION,
                    when (action.intervention.uppercase()) {
                        "GENTLE" -> InterventionLevel.GENTLE
                        "STRICT" -> InterventionLevel.STRICT
                        else -> InterventionLevel.MODERATE
                    },
                    true
                )
                sessionManager.savePresetRules(app.packageName, existingRules + rule)
                ActionResult.Success("已为 ${app.name} 创建规则: ${rule.type.name} - ${action.description}")
            }
            is AIAction.UpdateRule -> {
                val app = availableApps.find { it.name == action.appName || it.packageName == action.appName } ?: return ActionResult.Error("未找到应用")
                val rules = sessionManager.loadPresetRules(app.packageName)
                val target = rules.find { it.description.contains(action.targetDescription) } ?: return ActionResult.Error("未找到匹配规则")
                val updated = target.copy(
                    type = action.newRuleType?.let {
                        when (it.uppercase()) {
                            "TIME_CAP" -> ConstraintType.TIME_CAP
                            else -> ConstraintType.DENY
                        }
                    } ?: target.type,
                    description = action.newDescription ?: target.description,
                    timeLimitMs = action.newTimeLimitMinutes?.times(60000)?.toLong() ?: target.timeLimitMs,
                    interventionLevel = action.newIntervention?.let {
                        when (it.uppercase()) {
                            "GENTLE" -> InterventionLevel.GENTLE
                            "STRICT" -> InterventionLevel.STRICT
                            else -> InterventionLevel.MODERATE
                        }
                    } ?: target.interventionLevel
                )
                sessionManager.savePresetRules(app.packageName, rules.map { if (it.id == target.id) updated else it })
                ActionResult.Success("已修改 ${app.name} 规则: ${updated.description}")
            }
            is AIAction.DeleteRule -> {
                val app = availableApps.find { it.name == action.appName || it.packageName == action.appName } ?: return ActionResult.Error("未找到应用")
                val rules = sessionManager.loadPresetRules(app.packageName)
                val target = rules.find { it.description.contains(action.description) } ?: return ActionResult.Error("未找到匹配规则")
                sessionManager.savePresetRules(app.packageName, rules.filter { it.id != target.id })
                ActionResult.Success("已删除 ${app.name} 规则: ${target.description}")
            }
            is AIAction.ListRules -> {
                val app = availableApps.find { it.name == action.appName || it.packageName == action.appName }

                if (app != null) {
                    val rules = sessionManager.loadPresetRules(app.packageName)
                    if (rules.isEmpty()) ActionResult.Success("${app.name} 暂无规则")
                    else ActionResult.Success("${app.name} 规则:\n" + rules.mapIndexed { i, r ->
                        val timeInfo = r.timeLimitMs?.let { ms ->
                            val minutes = ms / 60000.0
                            if (minutes % 1.0 == 0.0) "${minutes.toInt()}分钟" else "${minutes}分钟"
                        } ?: ""
                        "${i + 1}. ${r.type.name}: ${r.description}${if (timeInfo.isNotEmpty()) " ($timeInfo)" else ""}"
                    }.joinToString("\n"))
                } else {
                    val pm = context.packageManager
                    val installedApps = pm.getInstalledApplications(0)
                    val searchTerm = action.appName.lowercase()

                    val matchedApps = installedApps.filter { appInfo ->
                        val appName = pm.getApplicationLabel(appInfo).toString().lowercase()
                        val packageName = appInfo.packageName.lowercase()
                        appName.contains(searchTerm) || packageName.contains(searchTerm) || searchTerm.contains(appName)
                    }

                    when {
                        matchedApps.isEmpty() -> ActionResult.Error("未找到「${action.appName}」")
                        matchedApps.size == 1 -> {
                            val foundApp = matchedApps[0]
                            val appName = pm.getApplicationLabel(foundApp).toString()
                            val rules = sessionManager.loadPresetRules(foundApp.packageName)
                            if (rules.isEmpty()) ActionResult.Success("$appName 暂无规则")
                            else ActionResult.Success("$appName 规则:\n" + rules.mapIndexed { i, r ->
                                val timeInfo = r.timeLimitMs?.let { ms ->
                                    val minutes = ms / 60000.0
                                    if (minutes % 1.0 == 0.0) "${minutes.toInt()}分钟" else "${minutes}分钟"
                                } ?: ""
                                "${i + 1}. ${r.type.name}: ${r.description}${if (timeInfo.isNotEmpty()) " ($timeInfo)" else ""}"
                            }.joinToString("\n"))
                        }
                        else -> {
                            val appList = matchedApps.take(5).joinToString(", ") { pm.getApplicationLabel(it).toString() }
                            ActionResult.Error("找到多个匹配的应用：$appList。请说得更具体一些")
                        }
                    }
                }
            }
            is AIAction.ListApps -> ActionResult.Success("已监控应用:\n" + availableApps.joinToString("\n") { "- ${it.name}" })
            is AIAction.Chat -> ActionResult.Success(action.responseText)
            is AIAction.Ask -> ActionResult.Success(action.question)
        }
    }

    private fun extractFinalResponse(response: String): String? = Regex("""Final Response:\s*(.+)""", RegexOption.DOT_MATCHES_ALL).find(response)?.groupValues?.get(1)?.trim()

    private fun callLLM(prompt: String): String {
        val apiKey = ApiConfig.getApiKey()
        if (apiKey.isBlank()) throw IllegalStateException("API key not configured")
        for (attempt in 1..3) try {
            val param = GenerationParam.builder().apiKey(apiKey).model("qwen-plus").messages(listOf(Message.builder().role(Role.SYSTEM.value).content("你是一个智能的规则管理助手。").build(), Message.builder().role(Role.USER.value).content(prompt).build())).resultFormat(GenerationParam.ResultFormat.MESSAGE).temperature(0.3f).build()
            return generation.call(param).output.choices[0].message.content
        } catch (e: Exception) { Logger.w(TAG, "LLM error: ${e.message}"); if(attempt<3) Thread.sleep(1000L*attempt) }
        throw Exception("LLM call failed")
    }
}

sealed class AIAction {
    data class CreateRule(val appName: String, val ruleType: String, val description: String, val timeLimitMinutes: Double?, val intervention: String) : AIAction()
    data class UpdateRule(val appName: String, val targetDescription: String, val newRuleType: String?, val newDescription: String?, val newTimeLimitMinutes: Double?, val newIntervention: String?) : AIAction()
    data class DeleteRule(val appName: String, val description: String) : AIAction()
    data class ListRules(val appName: String) : AIAction()
    data object ListApps : AIAction()
    data class CheckAndMonitorApp(val appName: String) : AIAction()
    data class Chat(val responseText: String) : AIAction()
    data class Ask(val question: String) : AIAction()
}

sealed class ActionResult {
    data class Success(val message: String) : ActionResult()
    data class Error(val message: String) : ActionResult()
}

sealed class ExecutionResult {
    data class Success(val results: List<ActionResult>, val response: String) : ExecutionResult()
    data class Error(val message: String) : ExecutionResult()
}

data class AppInfo(val name: String, val packageName: String)
data class ChatMessage(val role: String, val content: String)
