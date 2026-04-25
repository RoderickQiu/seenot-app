package com.seenot.app.ai.parser

import android.content.Context
import com.seenot.app.R
import com.seenot.app.ai.OpenAiCompatibleClient
import com.seenot.app.config.AppLocalePrefs
import com.seenot.app.utils.Logger
import com.google.gson.JsonParser
import com.seenot.app.data.model.ConstraintType
import com.seenot.app.data.model.InterventionLevel
import com.seenot.app.data.model.TimeScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

class IntentParser(private val contextRef: () -> Context) {
    companion object { private const val TAG = "IntentParser" }
    private val llmClient = OpenAiCompatibleClient()
    private class IntentParseFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private fun buildLanguageAwareExamples(languageCode: String): String {
        return if (languageCode == AppLocalePrefs.LANG_ZH) {
            """
输入："刷微信但不能看朋友圈"
输出：{"constraints":[{"type":"DENY","description":"朋友圈","timeLimitMinutes":null,"timeScope":"SESSION","intervention":"MODERATE"}]}

输入："不能看朋友圈和视频号"
输出：{"constraints":[{"type":"DENY","description":"朋友圈和视频号","timeLimitMinutes":null,"timeScope":"SESSION","intervention":"MODERATE"}]}

输入："只看微信消息"
输出：{"constraints":[{"type":"DENY","description":"除微信消息外的其他内容","timeLimitMinutes":null,"timeScope":"SESSION","intervention":"MODERATE"}]}

输入："只想看旅行相关内容"
输出：{"constraints":[{"type":"DENY","description":"除旅行相关内容外的其他内容","timeLimitMinutes":null,"timeScope":"SESSION","intervention":"MODERATE"}]}

输入："only look at messages"
输出：{"constraints":[{"type":"DENY","description":"除消息外的其他内容","timeLimitMinutes":null,"timeScope":"SESSION","intervention":"MODERATE"}]}

输入："每天最多10分钟"
输出：{"constraints":[],"unsupportedMode":"DAILY_TOTAL"}

输入："打开抖音，最多刷15分钟"
输出：{"constraints":[{"type":"TIME_CAP","description":"使用时长限制","timeLimitMinutes":15,"timeScope":"SESSION","intervention":"STRICT"}]}
            """.trimIndent()
        } else {
            """
Input: "刷微信但不能看朋友圈"
Output: {"constraints":[{"type":"DENY","description":"Moments","timeLimitMinutes":null,"timeScope":"SESSION","intervention":"MODERATE"}]}

Input: "不能看朋友圈和视频号"
Output: {"constraints":[{"type":"DENY","description":"Moments and Channels","timeLimitMinutes":null,"timeScope":"SESSION","intervention":"MODERATE"}]}

Input: "只看微信消息"
Output: {"constraints":[{"type":"DENY","description":"all other content except WeChat messages","timeLimitMinutes":null,"timeScope":"SESSION","intervention":"MODERATE"}]}

Input: "only look at messages"
Output: {"constraints":[{"type":"DENY","description":"all other content except messages","timeLimitMinutes":null,"timeScope":"SESSION","intervention":"MODERATE"}]}

Input: "每天最多10分钟"
Output: {"constraints":[],"unsupportedMode":"DAILY_TOTAL"}

Input: "打开抖音，最多刷15分钟"
Output: {"constraints":[{"type":"TIME_CAP","description":"usage time limit","timeLimitMinutes":15,"timeScope":"SESSION","intervention":"STRICT"}]}
            """.trimIndent()
        }
    }

    suspend fun parseIntent(utterance: String, packageName: String, appName: String): ParsedIntentResult {
        return withContext(Dispatchers.IO) {
            val context = contextRef()
            try {
                val outputLanguageName = AppLocalePrefs.getAiOutputLanguageName(context)
                val outputLanguageCode = AppLocalePrefs.getLanguage(context)
                val examples = buildLanguageAwareExamples(outputLanguageCode)
                val prompt = """
请将用户的意图解析为结构化的规则。

应用: $appName ($packageName)
用户说: "$utterance"
当前 SeeNot 软件语言: $outputLanguageName

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
7. **语言规则（高优先级）**：description 必须使用当前 SeeNot 软件语言（当前设置：$outputLanguageName），而不是跟随用户输入语言。
   - 如果当前 SeeNot 是英文，即使用户输入中文，description 也必须输出英文。
   - 如果当前 SeeNot 是中文，即使用户输入英文，description 也必须输出中文。
   - 不要输出中英混杂 description。
8. 规则必须保持泛化，不要把推理建立在某个具体 app、品牌或中文固定短语上；同类语义在淘宝/京东/Amazon、微信/WhatsApp/Telegram 中都应一致处理

规则类型:
- DENY: 黑名单，禁止使用某功能/内容
- TIME_CAP: 纯时间限制（无内容限制）

时间范围类型 (timeScope):
- SESSION: 整个会话计时，无论看什么内容都在倒计时
- PER_CONTENT: 只有在目标内容时才计时，切换到其他内容时暂停
- CONTINUOUS: 只有在同一内容持续停留时才计时

⚠️ 如何判断 timeScope：
- "X只能看Y分钟" → PER_CONTENT（只有看X时才计时）
- "不能看X，最多Y分钟" → SESSION（整个会话限时，看到X违规）
- "最多Y分钟" → SESSION（纯时间限制）
- "每天最多Y分钟" / "今天只能看Y分钟" / "today total" / "daily total" 这类“每日累计”语义当前不支持。
  对这类输入，不要改写成 SESSION 或 PER_CONTENT；返回空 constraints，并额外返回 "unsupportedMode":"DAILY_TOTAL"。

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
      "timeScope": "SESSION|PER_CONTENT|CONTINUOUS",
      "intervention": "GENTLE|MODERATE|STRICT"
    }
  ],
  "unsupportedMode": null或"DAILY_TOTAL"
}

示例：
$examples

如果用户没有明确表达规则，则返回空constraints。
                """.trimIndent()

                val response = callLLM(prompt)
                val parseResult = parseConstraintsFromJson(response)
                if (parseResult.unsupportedMode == "DAILY_TOTAL") {
                    return@withContext ParsedIntentResult.Error(
                        context.getString(R.string.voice_err_daily_total_not_supported)
                    )
                }
                val constraints = parseResult.constraints
                if (constraints.isEmpty()) {
                    return@withContext ParsedIntentResult.Error(
                        context.getString(R.string.voice_err_parse_intent_failed)
                    )
                }
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
                val errorMsg = when (e) {
                    is LlmException -> context.getString(R.string.voice_err_parse_failed)
                    is IntentParseFormatException -> context.getString(R.string.voice_err_parse_failed)
                    else -> e.message ?: context.getString(R.string.voice_err_parse_failed_simple)
                }
                ParsedIntentResult.Error(errorMsg)
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

    private data class ParsePayload(
        val constraints: List<TempConstraint>,
        val unsupportedMode: String? = null
    )

    private fun parseConstraintsFromJson(response: String): ParsePayload {
        val constraints = mutableListOf<TempConstraint>()
        val jsonPayload = extractJsonObject(response)
        try {
            val json = JsonParser.parseString(jsonPayload).asJsonObject
            val unsupportedMode = json.get("unsupportedMode")
                ?.takeIf { !it.isJsonNull }
                ?.asString
                ?.uppercase()
            val constraintsArray = json.get("constraints")
                ?: throw IntentParseFormatException("Missing constraints field")
            if (!constraintsArray.isJsonArray) {
                throw IntentParseFormatException("constraints is not an array")
            }
            constraintsArray.asJsonArray.forEach { constraintElem ->
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
                    "DAILY_TOTAL" -> {
                        return ParsePayload(emptyList(), unsupportedMode = "DAILY_TOTAL")
                    }
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
            return ParsePayload(constraints = constraints, unsupportedMode = unsupportedMode)
        } catch (e: IntentParseFormatException) {
            Logger.w(TAG, "Failed to parse constraints from JSON", e)
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to parse constraints from JSON", e)
            throw IntentParseFormatException("Invalid parser JSON", e)
        }
    }

    private fun extractJsonObject(response: String): String {
        val direct = response.trim()
        if (direct.startsWith("{") && direct.endsWith("}")) {
            return direct
        }

        val start = response.indexOf('{')
        val end = response.lastIndexOf('}')
        if (start >= 0 && end > start) {
            return response.substring(start, end + 1)
        }

        throw IntentParseFormatException("Parser response did not contain a JSON object")
    }

    private suspend fun callLLM(prompt: String): String {
        val outputLanguageName = AppLocalePrefs.getAiOutputLanguageName(contextRef())
        for (attempt in 1..3) try {
            return llmClient.completeText(
                systemPrompt = "You are a multilingual intent parser. Interpret user intent semantically across languages and apps. Output all user-facing description text in the current SeeNot UI language: $outputLanguageName.",
                userPrompt = prompt,
                temperature = 0.3,
                maxTokens = 800
            )
        } catch (e: Exception) {
            Logger.w(TAG, "LLM error: ${e.message}")
            if (attempt < 3) Thread.sleep(1000L * attempt)
        }
        throw LlmException("LLM call failed")
    }
}

class LlmException(message: String) : Exception(message)

sealed class ParsedIntentResult {
    data class Success(val constraints: List<ParsedConstraint>, val rawUtterance: String, val source: UtteranceSource) : ParsedIntentResult()
    data class Error(val message: String) : ParsedIntentResult()
}

data class ParsedConstraint(val id: String, val type: ConstraintType, val description: String, val timeLimit: TimeLimitData?, val intervention: InterventionLevel)
data class TimeLimitData(val durationMinutes: Int, val scope: TimeScope)
enum class UtteranceSource { VOICE, TEXT }
