package com.seenot.app.ai.feedback

import android.content.Context
import com.seenot.app.ai.OpenAiCompatibleClient
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
    }

    private val appHintRepository = AppHintRepository(context)
    private val llmClient = OpenAiCompatibleClient()

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

        val correctedJudgmentText = when (record.constraintType) {
            ConstraintType.TIME_CAP -> if (record.isConditionMatched) "用户纠正后：这里应该判为当前不计时" else "用户纠正后：这里应该判为正在计时"
            ConstraintType.DENY -> if (record.isConditionMatched) "用户纠正后：这里应该判为违规" else "用户纠正后：这里应该判为正常"
            null -> "用户纠正后：未知"
        }

        val correctionGoalText = when (record.constraintType) {
            ConstraintType.TIME_CAP -> if (record.isConditionMatched) {
                "这条规则必须帮助系统以后把同类页面判断为 out_of_scope（不计时），而不是 in_scope（计时）。"
            } else {
                "这条规则必须帮助系统以后把同类页面判断为 in_scope（计时），而不是 out_of_scope（不计时）。"
            }
            ConstraintType.DENY -> if (record.isConditionMatched) {
                "这条规则必须帮助系统以后把同类页面判断为 violates（违规），而不是 safe（正常）。"
            } else {
                "这条规则必须帮助系统以后把同类页面判断为 safe（正常），而不是 violates（违规）。"
            }
            null -> "这条规则必须服务于用户这次纠正后的正确判断。"
        }

        val recordConstraintType = when (record.constraintType) {
            ConstraintType.DENY -> "禁止"
            ConstraintType.TIME_CAP -> "时间限制"
            null -> "未知"
        }

        return """
你是 SeeNot 的误报纠偏引擎。用户已经明确确认：下面这条判断是误报。

你的任务：基于原始 intent、当前截图、应用特点、已有附加规则和用户补充说明，生成 1 条新的“附加判断规则”，用于后续减少同类误报。

注意：用户已经明确确认“系统这次判断反了”。你必须以“用户纠正后的正确判断”为准生成规则，不能顺着系统原判断继续加强。

补充背景：这条原始判断通常来自一个更小、更便宜的在线分析模型，它的页面理解可能不稳定，甚至会误读功能边界。你必须批判性看待“系统原判断”和“AI 对截图的描述”，把它们只当作参考证据，不能盲从；当它们与用户纠正冲突时，以用户纠正为准。

要求：
1. 只输出 1 条规则，必须具体、窄、可执行。
2. 规则的作用是细化边界或说明例外，不能推翻用户原始 intent。
3. 尽量使用应用内具体模块名，比如：群聊、私聊、聊天列表、首页、搜索页、详情页、播放页、评论区、个人主页。
4. 如果是 [禁止] 误报，规则应说明“哪些页面不算违规，哪些页面才算违规”。
5. 如果是 [时间限制] 误报，规则应说明“哪些页面算计时，哪些页面不计时”。
6. 规则方向必须和“用户纠正后”的正确判断一致；如果这次应该算计时，就生成帮助未来判为计时的规则；如果这次应该不计时，就生成帮助未来判为不计时的规则。
7. 不要生成过宽泛的规则，例如“这个 app 都不算”。
8. 如果信息不足以生成高质量规则，返回 no_rule。
9. 输出必须是 JSON，对象格式如下：
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
- $correctedJudgmentText
- AI 对截图的描述：${record.aiResult ?: "未知"}

本次纠正目标：
- $correctionGoalText

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

    private suspend fun callMultimodal(content: List<Map<String, Any>>): String? {
        if (!ApiConfig.isConfigured()) {
            Logger.w(TAG, "API key is empty, skipping false-positive rule generation")
            return null
        }

        return try {
            val prompt = content.firstOrNull { it.containsKey("text") }
                ?.get("text")
                ?.toString()
                .orEmpty()
            val imageDataUrl = content.firstOrNull { it.containsKey("image") }
                ?.get("image")
                ?.toString()

            if (imageDataUrl != null) {
                llmClient.completeVision(
                    userPrompt = prompt,
                    imageDataUrl = imageDataUrl,
                    temperature = 0.2,
                    maxTokens = 600,
                    modelOverride = ApiConfig.getFeedbackModel()
                )
            } else {
                llmClient.completeText(
                    userPrompt = prompt,
                    temperature = 0.2,
                    maxTokens = 600,
                    modelOverride = ApiConfig.getFeedbackModel()
                )
            }
        } catch (e: IllegalStateException) {
            Logger.w(TAG, "Config error while generating false-positive rule: ${e.message}")
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
