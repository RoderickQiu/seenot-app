package com.seenot.app.ai.parser

import com.seenot.app.ai.OpenAiCompatibleClient
import com.seenot.app.utils.Logger
import com.google.gson.JsonParser
import com.seenot.app.data.model.ConstraintType
import com.seenot.app.data.model.InterventionLevel
import com.seenot.app.data.model.TimeScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

class IntentParser {
    companion object { private const val TAG = "IntentParser" }
    private val llmClient = OpenAiCompatibleClient()

    suspend fun parseIntent(utterance: String, packageName: String, appName: String): ParsedIntentResult {
        return withContext(Dispatchers.IO) {
            try {
                val prompt = """
请将用户的意图解析为结构化的规则。

应用: $appName ($packageName)
用户说: "$utterance"

⚠️ 重要原则：
1. 只生成一个约束，将用户的所有限制合并到这一个约束中
2. 用户可能使用任何语言表达同一个意图。你必须按语义理解，而不是只匹配中文措辞。
3. 区分两种语义：
   - Exclusive / allowlist intent：用户只允许自己看/做某类内容，例如 "只看X"、"只想看X"、"only X"、"just X"、"nothing but X"。由于当前 schema 没有 ALLOW，这类语义也要编码为 DENY，但 description 必须改写成“会触发干预的补集内容”，即“除X外的其他内容”。
   - Blocklist intent：用户不允许某类内容，例如 "不能看X"、"不要看X"、"don't look at X"。这类也使用 DENY，description 直接写被禁止内容。
   - Pure time limit：只有时间限制、没有内容限制时，使用 TIME_CAP。
3. 时间限制直接加在约束的 timeLimitMinutes 字段上
4. 如果既有内容限制又有时间限制，使用 DENY，时间加在同一个约束上
5. **多条件合并**：如果用户提到多个内容（如"不能看朋友圈和视频号"），将它们合并到一个约束的 description 中
6. 对于 DENY，description 必须始终写“什么内容会触发干预”，不能直接复述 allowlist 原话
7. **语言规则（高优先级）**：description 必须与用户输入保持同一种语言。中文输入就输出中文 description；英文输入就输出英文 description；不要无故翻译成另一种语言。
8. 规则必须保持泛化，不要把推理建立在某个具体 app、品牌或中文固定短语上；同类语义在淘宝/京东/Amazon、微信/WhatsApp/Telegram 中都应一致处理

规则类型:
- DENY: 黑名单，禁止使用某功能/内容
- TIME_CAP: 纯时间限制（无内容限制）

时间范围类型 (timeScope):
- SESSION: 整个会话计时，无论看什么内容都在倒计时
- PER_CONTENT: 只有在目标内容时才计时，切换到其他内容时暂停
- DAILY_TOTAL: 每日累计时间，跨会话持久化（今天总共最多X分钟）

⚠️ 如何判断 timeScope：
- "X只能看Y分钟" → PER_CONTENT（只有看X时才计时）
- "不能看X，最多Y分钟" → SESSION（整个会话限时，看到X违规）
- "最多Y分钟" → SESSION（纯时间限制）
- "每天最多Y分钟" / "今天只能看Y分钟" → DAILY_TOTAL（每日累计）

干预级别:
- GENTLE: 温和提醒
- MODERATE: 中等提醒
- STRICT: 严格阻止

请用以下JSON格式回复（只返回一个约束）:
{
  "constraints": [
    {
      "type": "DENY|TIME_CAP",
      "description": "规则描述",
      "timeLimitMinutes": null或数字,
      "timeScope": "SESSION|PER_CONTENT|DAILY_TOTAL",
      "intervention": "GENTLE|MODERATE|STRICT"
    }
  ]
}

示例：
输入："刷微信但不能看朋友圈"
输出：{"constraints":[{"type":"DENY","description":"朋友圈","timeLimitMinutes":null,"timeScope":"SESSION","intervention":"MODERATE"}]}

输入："不能看朋友圈和视频号"
输出：{"constraints":[{"type":"DENY","description":"朋友圈和视频号","timeLimitMinutes":null,"timeScope":"SESSION","intervention":"MODERATE"}]}

输入："只看微信消息"
输出：{"constraints":[{"type":"DENY","description":"除微信消息外的其他内容","timeLimitMinutes":null,"timeScope":"SESSION","intervention":"MODERATE"}]}

输入："只想看旅行相关内容"
输出：{"constraints":[{"type":"DENY","description":"除旅行相关内容外的其他内容","timeLimitMinutes":null,"timeScope":"SESSION","intervention":"MODERATE"}]}

输入："only look at messages"
输出：{"constraints":[{"type":"DENY","description":"all other content except messages","timeLimitMinutes":null,"timeScope":"SESSION","intervention":"MODERATE"}]}

输入："only look at WeChat messages"
输出：{"constraints":[{"type":"DENY","description":"all other content except WeChat messages","timeLimitMinutes":null,"timeScope":"SESSION","intervention":"MODERATE"}]}

输入："just work chats, no feed"
输出：{"constraints":[{"type":"DENY","description":"feed","timeLimitMinutes":null,"timeScope":"SESSION","intervention":"MODERATE"}]}

输入："每天最多10分钟"
输出：{"constraints":[{"type":"TIME_CAP","description":"每日时间限制","timeLimitMinutes":10,"timeScope":"DAILY_TOTAL","intervention":"STRICT"}]}

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
                        "DAILY_TOTAL" -> TimeScope.DAILY_TOTAL
                        else -> TimeScope.SESSION
                    }
                    constraints.add(
                        TempConstraint(
                            type = type,
                            description = c.get("description")?.asString ?: "",
                            timeLimitMinutes = c.get("timeLimitMinutes")?.takeIf { !it.isJsonNull }?.asInt,
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

    private suspend fun callLLM(prompt: String): String {
        for (attempt in 1..3) try {
            return llmClient.completeText(
                systemPrompt = "You are a multilingual intent parser. 你要按语义理解用户意图，而不是依赖某一种语言或某个具体 app 的固定说法。",
                userPrompt = prompt,
                temperature = 0.3,
                maxTokens = 800
            )
        } catch (e: Exception) {
            Logger.w(TAG, "LLM error: ${e.message}")
            if (attempt < 3) Thread.sleep(1000L * attempt)
        }
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
