package com.seenot.app.ai.parser

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.seenot.app.config.ApiConfig
import com.seenot.app.data.model.ConstraintType
import com.seenot.app.data.model.InterventionLevel
import com.seenot.app.data.model.TimeScope
import com.seenot.app.data.model.UtteranceSource
import com.alibaba.dashscope.aigc.generation.Generation
import com.alibaba.dashscope.aigc.generation.GenerationParam
import com.alibaba.dashscope.aigc.generation.GenerationResult
import com.alibaba.dashscope.common.Message
import com.alibaba.dashscope.common.Role
import com.alibaba.dashscope.exception.ApiException
import com.alibaba.dashscope.exception.InputRequiredException
import com.alibaba.dashscope.exception.NoApiKeyException
import com.alibaba.dashscope.protocol.Protocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.Arrays

/**
 * Intent Parser using LLM (Qwen3) for natural language understanding.
 * Uses DashScope native SDK.
 */
class IntentParser(private val context: Context) {

    companion object {
        private const val TAG = "IntentParser"
        private const val BASE_URL = "https://dashscope.aliyuncs.com/api/v1"
    }

    private val gson = Gson()
    private val generation = Generation(Protocol.HTTP.getValue(), BASE_URL)

    suspend fun parseIntent(
        utterance: String,
        @Suppress("UNUSED_PARAMETER") appPackageName: String,
        appDisplayName: String
    ): ParsedIntentResult = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Parsing intent: $utterance for app: $appDisplayName")

            val prompt = buildPrompt(utterance, appDisplayName)
            val response = callLLM(prompt)
            Log.d(TAG, "LLM raw response: $response")
            val constraints = parseLLMResponse(response, utterance)

            if (constraints.isEmpty()) {
                Log.w(TAG, "LLM returned empty constraints, response: $response")
                return@withContext ParsedIntentResult.Error("无法解析意图")
            }

            ParsedIntentResult.Success(
                constraints = constraints,
                rawUtterance = utterance,
                source = UtteranceSource.TEXT
            )

        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse intent", e)
            ParsedIntentResult.Error(e.message ?: "解析失败")
        }
    }

    private fun buildPrompt(utterance: String, appName: String): String {
        return """
将用户对App "$appName" 的意图转换为JSON。

用户输入: "$utterance"

根据以下示例判断意图类型：

示例：
- "只看微信" → ALLOW, 描述: 微信
- "只能看朋友圈" → ALLOW, 描述: 朋友圈
- "不要看朋友圈" → DENY, 描述: 朋友圈
- "不能看群消息" → DENY, 描述: 群消息
- "不想刷短视频" → DENY, 描述: 短视频
- "只能刷3分钟" → TIME_CAP, 限时3分钟
- "连续看10分钟" → TIME_CAP, 限时10分钟, scope: CONTINUOUS
- "每个视频2分钟" → TIME_CAP, 限时2分钟, scope: PER_CONTENT

规则：
- type: ALLOW=只/只要/只能+看X, DENY=不要/不能/别/不想+看X, TIME_CAP=时间限制
- intervention: 提醒我就行→GENTLE, 直接退出/强制→STRICT, 其他→MODERATE
- scope: 连续→CONTINUOUS, 每个→PER_CONTENT, 其他→SESSION
- 忽略语音识别噪音(如smar、um、uh)

输出格式:
{"type": "ALLOW|DENY|TIME_CAP", "description": "中文", "time_limit": {"duration_minutes": 数字, "scope": "SESSION|CONTINUOUS|PER_CONTENT"} | null, "intervention": "GENTLE|MODERATE|STRICT"}

直接输出JSON。
        """.trimIndent()
    }

    private fun callLLM(prompt: String): String {
        val apiKey = ApiConfig.getApiKey()

        if (apiKey.isBlank()) {
            throw IllegalStateException("API key not configured")
        }

        // Retry logic for transient errors
        val maxRetries = 3
        var lastException: Exception? = null

        for (attempt in 1..maxRetries) {
            try {
                val systemMsg = Message.builder()
                    .role(Role.SYSTEM.value)
                    .content("你是一个意图解析器。")
                    .build()

                val userMsg = Message.builder()
                    .role(Role.USER.value)
                    .content(prompt)
                    .build()

                val param = GenerationParam.builder()
                    .apiKey(apiKey)
                    .model("qwen-plus")
                    .messages(Arrays.asList(systemMsg, userMsg))
                    .resultFormat(GenerationParam.ResultFormat.MESSAGE)
                    .temperature(0.3f)
                    .build()

                val result: GenerationResult = generation.call(param)

                return result.output.choices[0].message.content

            } catch (e: NoApiKeyException) {
                Log.e(TAG, "No API key", e)
                throw IllegalStateException("API key not configured")
            } catch (e: ApiException) {
                lastException = e
                Log.w(TAG, "API error (attempt $attempt/$maxRetries): ${e.message}")
                if (attempt < maxRetries) {
                    Thread.sleep(1000L * attempt)
                }
            } catch (e: InputRequiredException) {
                Log.e(TAG, "Input required error", e)
                throw e
            } catch (e: Exception) {
                lastException = e
                Log.w(TAG, "LLM call failed (attempt $attempt/$maxRetries): ${e.message}")
                if (attempt < maxRetries) {
                    Thread.sleep(1000L * attempt)
                }
            }
        }

        throw lastException ?: Exception("LLM call failed after $maxRetries attempts")
    }

    /**
     * Parse LLM response with post-processing for scope keywords
     */
    private fun parseLLMResponse(response: String, originalUtterance: String): List<ParsedConstraint> {
        if (response.isBlank()) return emptyList()

        var constraints = parseLLMResponseRaw(response)

        // Post-processing: detect CONTINUOUS/PER_CONTENT from keywords
        if (originalUtterance.isNotBlank()) {
            val continuousKeywords = listOf("连续", "持续", "不间断", "不停地")
            val perContentKeywords = listOf("每个", "单车", "每次")

            constraints = constraints.map { constraint ->
                if (constraint.timeLimit != null) {
                    val newScope = when {
                        continuousKeywords.any { originalUtterance.contains(it) } -> TimeScope.CONTINUOUS
                        perContentKeywords.any { originalUtterance.contains(it) } -> TimeScope.PER_CONTENT
                        else -> null
                    }
                    if (newScope != null) {
                        constraint.copy(
                            timeLimit = TimeLimitData(
                                constraint.timeLimit.durationMinutes,
                                newScope
                            )
                        )
                    } else {
                        constraint
                    }
                } else {
                    constraint
                }
            }
        }

        return constraints
    }

    private fun parseLLMResponseRaw(response: String): List<ParsedConstraint> {
        if (response.isBlank()) return emptyList()

        return try {
            val json = JsonParser.parseString(response).asJsonObject

            // Handle both formats:
            // 1. {"constraints": [...]} - expected format
            // 2. {"type": "...", ...} - direct object format from LLM
            val constraintsArray = json.getAsJsonArray("constraints")
                ?: if (json.has("type")) {
                    // Direct object format - wrap in array
                    com.google.gson.JsonArray().also { it.add(json) }
                } else {
                    return emptyList()
                }

            constraintsArray.mapNotNull { element ->
                try {
                    val obj = element.asJsonObject
                    val typeStr = obj.get("type")?.asString ?: return@mapNotNull null
                    val type = when (typeStr.uppercase()) {
                        "ALLOW" -> ConstraintType.ALLOW
                        "DENY" -> ConstraintType.DENY
                        "TIME_CAP" -> ConstraintType.TIME_CAP
                        else -> return@mapNotNull null
                    }

                    val description = obj.get("description")?.asString ?: ""
                    val interventionStr = obj.get("intervention")?.asString ?: "MODERATE"
                    val intervention = when (interventionStr.uppercase()) {
                        "GENTLE" -> InterventionLevel.GENTLE
                        "STRICT" -> InterventionLevel.STRICT
                        else -> InterventionLevel.MODERATE
                    }

                    // Handle time_limit which can be null
                    val timeLimitElement = obj.get("time_limit")
                    val timeLimit = if (timeLimitElement != null && !timeLimitElement.isJsonNull) {
                        timeLimitElement.asJsonObject.let { tl ->
                            val duration = tl.get("duration_minutes")?.asInt
                            val scopeStr = tl.get("scope")?.asString ?: "SESSION"
                            val scope = when (scopeStr) {
                                "PER_CONTENT" -> TimeScope.PER_CONTENT
                                "CONTINUOUS" -> TimeScope.CONTINUOUS
                                else -> TimeScope.SESSION
                            }
                            if (duration != null && duration > 0) {
                                TimeLimitData(duration, scope)
                            } else null
                        }
                    } else null

                    ParsedConstraint(
                        id = UUID.randomUUID().toString(),
                        type = type,
                        description = description,
                        timeLimit = timeLimit,
                        intervention = intervention
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse constraint: $element", e)
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse LLM response", e)
            emptyList()
        }
    }

    fun release() {
        // DashScope SDK handles cleanup automatically
    }
}

/**
 * Result of intent parsing
 */
sealed class ParsedIntentResult {
    data class Success(
        val constraints: List<ParsedConstraint>,
        val rawUtterance: String,
        val source: UtteranceSource
    ) : ParsedIntentResult()

    data class Error(val message: String) : ParsedIntentResult()
}

/**
 * Parsed constraint from natural language
 */
data class ParsedConstraint(
    val id: String,
    val type: ConstraintType,
    val description: String,
    val timeLimit: TimeLimitData?,
    val intervention: InterventionLevel
)

/**
 * Time limit specification
 */
data class TimeLimitData(
    val durationMinutes: Int,
    val scope: TimeScope
)
