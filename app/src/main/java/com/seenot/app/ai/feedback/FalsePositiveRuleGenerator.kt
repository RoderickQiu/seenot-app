package com.seenot.app.ai.feedback

import android.content.Context
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationParam
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationResult
import com.alibaba.dashscope.common.MultiModalMessage
import com.alibaba.dashscope.common.Role
import com.alibaba.dashscope.exception.ApiException
import com.alibaba.dashscope.exception.NoApiKeyException
import com.google.gson.JsonParser
import com.seenot.app.config.ApiConfig
import com.seenot.app.data.model.AppHint
import com.seenot.app.data.model.ConstraintType
import com.seenot.app.data.model.RuleRecord
import com.seenot.app.data.repository.AppHintRepository
import com.seenot.app.domain.SessionConstraint
import com.seenot.app.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Base64

data class GeneratedFalsePositiveRuleResult(
    val savedHint: AppHint? = null,
    val ruleText: String? = null,
    val reusedExistingHint: Boolean = false,
    val usedUserNoteFallback: Boolean = false
)

class FalsePositiveRuleGenerator(private val context: Context) {

    companion object {
        private const val TAG = "FalsePositiveRuleGen"
        private const val MODEL_NAME = "qwen3.6-plus"
    }

    private val appHintRepository = AppHintRepository(context)

    suspend fun generateAndSaveRule(
        packageName: String,
        appName: String,
        record: RuleRecord,
        constraints: List<SessionConstraint>,
        userNote: String? = null
    ): GeneratedFalsePositiveRuleResult = withContext(Dispatchers.IO) {
        val existingHints = appHintRepository.getHintsForPackage(packageName)
        val trimmedNote = userNote?.trim().orEmpty()

        val generatedRule = if (ApiConfig.isConfigured()) {
            runCatching {
                generateRuleWithAi(
                    appName = appName,
                    packageName = packageName,
                    record = record,
                    constraints = constraints,
                    existingHints = existingHints,
                    userNote = trimmedNote.takeIf { it.isNotBlank() }
                )
            }.onFailure { e ->
                Logger.w(TAG, "Failed to generate false-positive rule with AI: ${e.message}")
            }.getOrNull()
        } else {
            null
        }

        val finalRule = generatedRule ?: trimmedNote.takeIf { it.isNotBlank() }
        if (finalRule.isNullOrBlank()) {
            return@withContext GeneratedFalsePositiveRuleResult()
        }

        val saveResult = appHintRepository.saveHintIfNew(packageName, finalRule)
        GeneratedFalsePositiveRuleResult(
            savedHint = saveResult.hint,
            ruleText = saveResult.hint.hintText,
            reusedExistingHint = !saveResult.created,
            usedUserNoteFallback = generatedRule.isNullOrBlank() && trimmedNote.isNotBlank()
        )
    }

    private suspend fun generateRuleWithAi(
        appName: String,
        packageName: String,
        record: RuleRecord,
        constraints: List<SessionConstraint>,
        existingHints: List<AppHint>,
        userNote: String?
    ): String? {
        val prompt = buildPrompt(
            appName = appName,
            packageName = packageName,
            record = record,
            constraints = constraints,
            existingHints = existingHints,
            userNote = userNote
        )

        val content = mutableListOf<Map<String, Any>>()
        buildImageContent(record.imagePath)?.let { image ->
            content += mapOf("image" to image)
        }
        content += mapOf("text" to prompt)

        val responseText = callMultimodal(content) ?: return null
        return parseGeneratedRule(responseText)
    }

    private fun buildPrompt(
        appName: String,
        packageName: String,
        record: RuleRecord,
        constraints: List<SessionConstraint>,
        existingHints: List<AppHint>,
        userNote: String?
    ): String {
        val constraintsText = if (constraints.isNotEmpty()) {
            constraints.joinToString("\n") { constraint ->
                val type = when (constraint.type) {
                    ConstraintType.DENY -> "禁止"
                    ConstraintType.TIME_CAP -> "时间限制"
                }
                val timePart = constraint.timeLimitMs?.let { "，时长 ${it / 60000.0} 分钟" } ?: ""
                val scopePart = constraint.timeScope?.let { "，范围 ${it.name}" } ?: ""
                "- [$type] ${constraint.description}$timePart$scopePart"
            }
        } else {
            "- 无法拿到完整 intent，只能参考本条 record"
        }

        val existingHintsText = if (existingHints.isNotEmpty()) {
            existingHints.take(8).joinToString("\n") { "- ${it.hintText}" }
        } else {
            "- 暂无"
        }

        val judgmentText = when (record.constraintType) {
            ConstraintType.TIME_CAP -> if (record.isConditionMatched) "系统判定：正在计时" else "系统判定：当前不计时"
            ConstraintType.DENY -> if (record.isConditionMatched) "系统判定：正常" else "系统判定：违规"
            null -> "系统判定：未知"
        }

        val recordConstraintType = when (record.constraintType) {
            ConstraintType.DENY -> "禁止"
            ConstraintType.TIME_CAP -> "时间限制"
            null -> "未知"
        }

        return """
你是 SeeNot 的误报纠偏引擎。用户已经明确确认：下面这条判断是误报。

你的任务：基于原始 intent、当前截图、应用特点、已有附加规则和用户补充说明，生成 1 条新的“附加判断规则”，用于后续减少同类误报。

要求：
1. 只输出 1 条规则，必须具体、窄、可执行。
2. 规则的作用是细化边界或说明例外，不能推翻用户原始 intent。
3. 尽量使用应用内具体模块名，比如：群聊、私聊、聊天列表、首页、搜索页、详情页、播放页、评论区、个人主页。
4. 如果是 [禁止] 误报，规则应说明“哪些页面不算违规，哪些页面才算违规”。
5. 如果是 [时间限制] 误报，规则应说明“哪些页面算计时，哪些页面不计时”。
6. 不要生成过宽泛的规则，例如“这个 app 都不算”。
7. 如果信息不足以生成高质量规则，返回 no_rule。
8. 输出必须是 JSON，对象格式如下：
{
  "decision": "create_rule" 或 "no_rule",
  "supplemental_rule": "规则文本",
  "reason": "一句话解释"
}

应用信息：
- 应用名：$appName
- 包名：$packageName

当前 intent / 约束：
$constraintsText

这条误报 record：
- 约束类型：$recordConstraintType
- 约束内容：${record.constraintContent ?: "未知"}
- $judgmentText
- AI 对截图的描述：${record.aiResult ?: "未知"}

已有附加规则：
$existingHintsText

用户补充说明：
${userNote ?: "无"}

生成规则时，优先避免与“已有附加规则”重复。只输出 JSON，不要输出解释性文字。
        """.trimIndent()
    }

    private fun buildImageContent(imagePath: String?): String? {
        if (imagePath.isNullOrBlank()) return null
        val file = File(imagePath)
        if (!file.exists() || !file.isFile) return null

        return try {
            val mimeType = when (file.extension.lowercase()) {
                "png" -> "image/png"
                "webp" -> "image/webp"
                else -> "image/jpeg"
            }
            val base64 = Base64.getEncoder().encodeToString(file.readBytes())
            "data:$mimeType;base64,$base64"
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to encode image for false-positive rule generation", e)
            null
        }
    }

    private fun callMultimodal(content: List<Map<String, Any>>): String? {
        val apiKey = ApiConfig.getApiKey()
        if (apiKey.isBlank()) {
            Logger.w(TAG, "API key is empty, skipping false-positive rule generation")
            return null
        }

        return try {
            val userMessage = MultiModalMessage.builder()
                .role(Role.USER.getValue())
                .content(content)
                .build()

            val param = MultiModalConversationParam.builder()
                .apiKey(apiKey)
                .model(MODEL_NAME)
                .message(userMessage)
                .temperature(0.2f)
                .maxTokens(600)
                .enableThinking(false)
                .build()

            val conversation = MultiModalConversation()
            val result: MultiModalConversationResult = conversation.call(param)
            val responseContent = result.output.choices[0].message.content ?: return null
            responseContent.firstOrNull()?.get("text") as? String
        } catch (e: NoApiKeyException) {
            Logger.w(TAG, "No API key for false-positive rule generation: ${e.message}")
            null
        } catch (e: ApiException) {
            Logger.w(TAG, "API error while generating false-positive rule: ${e.message}")
            null
        } catch (e: Exception) {
            Logger.w(TAG, "Unexpected error while generating false-positive rule", e)
            null
        }
    }

    private fun parseGeneratedRule(responseText: String): String? {
        return try {
            val cleaned = cleanJson(responseText)
            val obj = JsonParser.parseString(cleaned).asJsonObject
            val decision = obj.get("decision")?.asString?.trim()?.lowercase()
            if (decision != "create_rule") {
                return null
            }
            obj.get("supplemental_rule")
                ?.asString
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to parse generated false-positive rule: ${e.message}")
            null
        }
    }

    private fun cleanJson(text: String): String {
        var cleaned = text.trim()

        if (cleaned.startsWith("```")) {
            val firstNewline = cleaned.indexOf('\n')
            if (firstNewline >= 0) {
                cleaned = cleaned.substring(firstNewline + 1)
            }
        }

        cleaned = cleaned.trim().trimEnd('`').trim()

        val jsonStart = cleaned.indexOf('{')
        val jsonEnd = cleaned.lastIndexOf('}')
        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            cleaned = cleaned.substring(jsonStart, jsonEnd + 1)
        }

        return cleaned
    }
}
