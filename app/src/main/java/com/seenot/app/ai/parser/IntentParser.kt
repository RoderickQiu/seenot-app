package com.seenot.app.ai.parser

import android.util.Log
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

规则类型:
- ALLOW: 允许使用
- DENY: 禁止使用
- TIME_CAP: 时间限制

干预级别:
- GENTLE: 温和提醒
- MODERATE: 中等提醒
- STRICT: 严格阻止

请用以下JSON格式回复:
{
  "constraints": [
    {
      "type": "ALLOW|DENY|TIME_CAP",
      "description": "规则描述",
      "timeLimitMinutes": null或数字,
      "intervention": "GENTLE|MODERATE|STRICT"
    }
  ]
}

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
                            timeLimit = it.timeLimitMinutes?.let { mins -> TimeLimitData(mins, TimeScope.SESSION) },
                            intervention = it.intervention
                        )
                    },
                    rawUtterance = utterance,
                    source = UtteranceSource.VOICE
                )
            } catch (e: Exception) {
                Log.e(TAG, "parseIntent failed", e)
                ParsedIntentResult.Error(e.message ?: "解析失败")
            }
        }
    }

    private data class TempConstraint(
        val type: ConstraintType,
        val description: String,
        val timeLimitMinutes: Int?,
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
                    constraints.add(
                        TempConstraint(
                            type = type,
                            description = c.get("description")?.asString ?: "",
                            timeLimitMinutes = c.get("timeLimitMinutes")?.asInt,
                            intervention = intervention
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse constraints from JSON", e)
        }
        return constraints
    }

    private fun callLLM(prompt: String): String {
        val apiKey = ApiConfig.getApiKey()
        if (apiKey.isBlank()) throw IllegalStateException("API key not configured")
        for (attempt in 1..3) try {
            val param = GenerationParam.builder().apiKey(apiKey).model("qwen-plus").messages(listOf(Message.builder().role(Role.SYSTEM.value).content("你是一个智能的意图解析助手。").build(), Message.builder().role(Role.USER.value).content(prompt).build())).resultFormat(GenerationParam.ResultFormat.MESSAGE).temperature(0.3f).build()
            return generation.call(param).output.choices[0].message.content
        } catch (e: Exception) { Log.w(TAG, "LLM error: ${e.message}"); if(attempt<3) Thread.sleep(1000L*attempt) }
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
