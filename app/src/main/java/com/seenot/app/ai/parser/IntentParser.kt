package com.seenot.app.ai.parser

import com.seenot.app.utils.Logger
import com.google.gson.JsonParser
import com.seenot.app.config.ApiConfig
import com.seenot.app.data.model.ConstraintType
import com.seenot.app.data.model.InterventionLevel
import com.seenot.app.data.model.TimeScope
import com.alibaba.dashscope.aigc.generation.Generation
import com.alibaba.dashscope.aigc.generation.GenerationParam
import com.alibaba.dashscope.common.Message
import com.alibaba.dashscope.common.Role
import com.alibaba.dashscope.protocol.Protocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

class IntentParser {
    companion object { private const val TAG = "IntentParser" }
    private val generation = Generation(Protocol.HTTP.getValue(), "https://dashscope.aliyuncs.com/api/v1")

    suspend fun parseIntent(utterance: String, packageName: String, appName: String): ParsedIntentResult {
        return withContext(Dispatchers.IO) {
            try {
                val prompt = """
请将用户的意图解析为结构化的规则。

应用: $appName ($packageName)
用户说: "$utterance"

⚠️ 重要原则：
1. 只生成一个约束，将用户的所有限制合并到这一个约束中
2. 区分三种场景：
   - "只能看X" → ALLOW（白名单）
   - "不能看X" → DENY（黑名单）
   - "最多X分钟"（无内容限制）→ TIME_CAP
3. 时间限制直接加在约束的 timeLimitMinutes 字段上
4. 如果既有内容限制又有时间限制，优先使用 ALLOW/DENY，时间加在同一个约束上

规则类型:
- ALLOW: 白名单，只允许使用某功能/内容
- DENY: 黑名单，禁止使用某功能/内容
- TIME_CAP: 纯时间限制（无内容限制）

时间范围类型 (timeScope):
- SESSION: 整个会话计时，无论看什么内容都在倒计时
- PER_CONTENT: 只有在目标内容时才计时，切换到其他内容时暂停

⚠️ 如何判断 timeScope：
- "X只能看Y分钟" → PER_CONTENT（只有看X时才计时）
- "不能看X，最多Y分钟" → SESSION（整个会话限时，看到X违规）
- "最多Y分钟" → SESSION（纯时间限制）

干预级别:
- GENTLE: 温和提醒
- MODERATE: 中等提醒
- STRICT: 严格阻止

请用以下JSON格式回复（只返回一个约束）:
{
  "constraints": [
    {
      "type": "ALLOW|DENY|TIME_CAP",
      "description": "规则描述",
      "timeLimitMinutes": null或数字,
      "timeScope": "SESSION|PER_CONTENT",
      "intervention": "GENTLE|MODERATE|STRICT"
    }
  ]
}

示例：
输入："刷微信但不能看朋友圈"
输出：{"constraints":[{"type":"DENY","description":"禁止查看朋友圈","timeLimitMinutes":null,"timeScope":"SESSION","intervention":"MODERATE"}]}

输入："打开小红书，只能看穿搭"
输出：{"constraints":[{"type":"ALLOW","description":"只允许浏览穿搭内容","timeLimitMinutes":null,"timeScope":"SESSION","intervention":"MODERATE"}]}

输入："打开小红书，只能看穿搭5分钟"
输出：{"constraints":[{"type":"ALLOW","description":"只允许浏览穿搭内容","timeLimitMinutes":5,"timeScope":"PER_CONTENT","intervention":"MODERATE"}]}

输入："朋友圈只能看3分钟"
输出：{"constraints":[{"type":"ALLOW","description":"只允许查看朋友圈","timeLimitMinutes":3,"timeScope":"PER_CONTENT","intervention":"MODERATE"}]}

输入："打开抖音，最多刷15分钟"
输出：{"constraints":[{"type":"TIME_CAP","description":"使用时长限制","timeLimitMinutes":15,"timeScope":"SESSION","intervention":"STRICT"}]}

输入："打开小红书看穿搭，但不能看美食，最多10分钟"
输出：{"constraints":[{"type":"DENY","description":"禁止浏览美食内容","timeLimitMinutes":10,"timeScope":"SESSION","intervention":"MODERATE"}]}

如果用户没有明确表达规则，则返回空constraints。
                """.trimIndent()

                val response = callLLM(prompt)
                val constraints = parseConstraintsFromJson(response)
                ParsedIntentResult.Success(
                    constraints = constraints.map {
                        ParsedConstraint(
                            id = UUID.randomUUID().toString(),
                            type = it.type,
                            description = it.description,
                            timeLimit = it.timeLimitMinutes?.let { mins ->
                                TimeLimitData(mins, it.timeScope ?: TimeScope.SESSION)
                            },
                            intervention = it.intervention
                        )
                    },
                    rawUtterance = utterance,
                    source = UtteranceSource.VOICE
                )
            } catch (e: Exception) {
                Logger.e(TAG, "parseIntent failed", e)
                ParsedIntentResult.Error(e.message ?: "解析失败")
            }
        }
    }

    private data class TempConstraint(
        val type: ConstraintType,
        val description: String,
        val timeLimitMinutes: Int?,
        val timeScope: TimeScope?,
        val intervention: InterventionLevel
    )

    private fun parseConstraintsFromJson(response: String): List<TempConstraint> {
        val constraints = mutableListOf<TempConstraint>()
        try {
            val jsonMatch = Regex("""\{[\s\S]*\}""").find(response)
            jsonMatch?.let {
                val json = JsonParser.parseString(it.value).asJsonObject
                json.get("constraints")?.asJsonArray?.forEach { constraintElem ->
                    val c = constraintElem.asJsonObject
                    val typeStr = c.get("type")?.asString?.uppercase() ?: "DENY"
                    val type = when (typeStr) {
                        "ALLOW" -> ConstraintType.ALLOW
                        "TIME_CAP" -> ConstraintType.TIME_CAP
                        else -> ConstraintType.DENY
                    }
                    val interventionStr = c.get("intervention")?.asString?.uppercase() ?: "MODERATE"
                    val intervention = when (interventionStr) {
                        "GENTLE" -> InterventionLevel.GENTLE
                        "STRICT" -> InterventionLevel.STRICT
                        else -> InterventionLevel.MODERATE
                    }
                    val timeScopeStr = c.get("timeScope")?.asString?.uppercase()
                    val timeScope = when (timeScopeStr) {
                        "PER_CONTENT" -> TimeScope.PER_CONTENT
                        "CONTINUOUS" -> TimeScope.CONTINUOUS
                        else -> TimeScope.SESSION
                    }
                    constraints.add(
                        TempConstraint(
                            type = type,
                            description = c.get("description")?.asString ?: "",
                            timeLimitMinutes = c.get("timeLimitMinutes")?.asInt,
                            timeScope = timeScope,
                            intervention = intervention
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to parse constraints from JSON", e)
        }
        return constraints
    }

    private fun callLLM(prompt: String): String {
        val apiKey = ApiConfig.getApiKey()
        if (apiKey.isBlank()) throw IllegalStateException("API key not configured")
        for (attempt in 1..3) try {
            val param = GenerationParam.builder().apiKey(apiKey).model("qwen-plus").messages(listOf(Message.builder().role(Role.SYSTEM.value).content("你是一个智能的意图解析助手。").build(), Message.builder().role(Role.USER.value).content(prompt).build())).resultFormat(GenerationParam.ResultFormat.MESSAGE).temperature(0.3f).build()
            return generation.call(param).output.choices[0].message.content
        } catch (e: Exception) { Logger.w(TAG, "LLM error: ${e.message}"); if(attempt<3) Thread.sleep(1000L*attempt) }
        throw Exception("LLM call failed")
    }
}

sealed class ParsedIntentResult {
    data class Success(val constraints: List<ParsedConstraint>, val rawUtterance: String, val source: UtteranceSource) : ParsedIntentResult()
    data class Error(val message: String) : ParsedIntentResult()
}

data class ParsedConstraint(val id: String, val type: ConstraintType, val description: String, val timeLimit: TimeLimitData?, val intervention: InterventionLevel)
data class TimeLimitData(val durationMinutes: Int, val scope: TimeScope)
enum class UtteranceSource { VOICE, TEXT }
