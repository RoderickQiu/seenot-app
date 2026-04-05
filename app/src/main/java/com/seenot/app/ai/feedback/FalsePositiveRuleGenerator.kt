package com.seenot.app.ai.feedback

import android.content.Context
import com.seenot.app.ai.OpenAiCompatibleClient
import com.google.gson.JsonParser
import com.seenot.app.config.ApiConfig
import com.seenot.app.data.model.AppHint
import com.seenot.app.data.model.APP_HINT_SOURCE_INTENT_CARRY_OVER
import com.seenot.app.data.model.ConstraintType
import com.seenot.app.data.model.RuleRecord
import com.seenot.app.data.model.buildIntentScopedHintId
import com.seenot.app.data.model.buildIntentScopedHintLabel
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

data class FalsePositiveRulePreview(
    val ruleText: String? = null,
    val targetIntentId: String? = null,
    val targetIntentLabel: String? = null
)

private data class CarryOverHintSelectionResponse(
    val carryOverIds: List<String>
)

class FalsePositiveRuleGenerator(private val context: Context) {

    companion object {
        private const val TAG = "FalsePositiveRuleGen"
    }

    private val appHintRepository = AppHintRepository(context)
    private val llmClient = OpenAiCompatibleClient()

    suspend fun generateRulePreview(
        packageName: String,
        appName: String,
        record: RuleRecord,
        constraints: List<SessionConstraint>,
        userNote: String? = null
    ): FalsePositiveRulePreview = withContext(Dispatchers.IO) {
        val targetConstraint = resolveTargetConstraint(record, constraints) ?: constraints.firstOrNull()
            ?: return@withContext FalsePositiveRulePreview()
        val targetIntentId = buildIntentScopedHintId(targetConstraint)
        val targetIntentLabel = buildIntentScopedHintLabel(targetConstraint)
        val existingHints = appHintRepository.getHintsForIntent(packageName, targetIntentId)
        val trimmedNote = userNote?.trim().orEmpty()

        val generatedRule = if (ApiConfig.isConfigured()) {
            runCatching {
                generateRuleWithAi(
                    appName = appName,
                    packageName = packageName,
                    record = record,
                    targetConstraint = targetConstraint,
                    existingHints = existingHints,
                    userNote = trimmedNote.takeIf { it.isNotBlank() }
                )
            }.onFailure { e ->
                Logger.w(TAG, "Failed to preview false-positive rule with AI: ${e.message}")
            }.getOrNull()
        } else {
            null
        }

        FalsePositiveRulePreview(
            ruleText = generatedRule,
            targetIntentId = targetIntentId,
            targetIntentLabel = targetIntentLabel
        )
    }

    suspend fun generateAndSaveRule(
        packageName: String,
        appName: String,
        record: RuleRecord,
        constraints: List<SessionConstraint>,
        userNote: String? = null
    ): GeneratedFalsePositiveRuleResult = withContext(Dispatchers.IO) {
        val targetConstraint = resolveTargetConstraint(record, constraints) ?: constraints.firstOrNull()
            ?: return@withContext GeneratedFalsePositiveRuleResult()
        val targetIntentId = buildIntentScopedHintId(targetConstraint)
        val targetIntentLabel = buildIntentScopedHintLabel(targetConstraint)
        val existingHints = appHintRepository.getHintsForIntent(packageName, targetIntentId)
        val trimmedNote = userNote?.trim().orEmpty()

        val generatedRule = if (ApiConfig.isConfigured()) {
            runCatching {
                generateRuleWithAi(
                    appName = appName,
                    packageName = packageName,
                    record = record,
                    targetConstraint = targetConstraint,
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

        val saveResult = appHintRepository.saveHintIfNew(
            packageName = packageName,
            intentId = targetIntentId,
            intentLabel = targetIntentLabel,
            hintText = finalRule
        )
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
        targetConstraint: SessionConstraint,
        existingHints: List<AppHint>,
        userNote: String?
    ): String? {
        val prompt = buildPrompt(
            appName = appName,
            packageName = packageName,
            record = record,
            targetConstraint = targetConstraint,
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
        targetConstraint: SessionConstraint,
        existingHints: List<AppHint>,
        userNote: String?
    ): String {
        val constraintsText = run {
            val type = when (targetConstraint.type) {
                ConstraintType.DENY -> "禁止"
                ConstraintType.TIME_CAP -> "时间限制"
            }
            val timePart = targetConstraint.timeLimitMs?.let { "，时长 ${it / 60000.0} 分钟" } ?: ""
            val scopePart = targetConstraint.timeScope?.let { "，范围 ${it.name}" } ?: ""
            "- [$type] ${targetConstraint.description}$timePart$scopePart"
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

这条补充规则只服务于“当前这一条 intent / 约束”，不能替别的 intent 概括，也不能写成整个 app 的通用定义。

注意：用户已经明确确认“系统这次判断反了”。你必须以“用户纠正后的正确判断”为准生成规则，不能顺着系统原判断继续加强。

补充背景：这条原始判断通常来自一个更小、更便宜的在线分析模型，它的页面理解可能不稳定，甚至会误读功能边界。你必须批判性看待“系统原判断”和“AI 对截图的描述”，把它们只当作参考证据，不能盲从；当它们与用户纠正冲突时，以用户纠正为准。

要求：
1. 只输出 1 条规则，必须具体、窄、可执行。
2. 规则的作用是细化边界或说明例外，不能推翻用户原始 intent，更不能重写它的完整定义。
3. 尽量使用应用内具体模块名，比如：群聊、私聊、聊天列表、首页、搜索页、详情页、播放页、评论区、个人主页。
4. 规则应该优先描述“页面边界 / 场景边界 / 功能边界”，而不是直接给出全局放行结论。
5. 如果是 [禁止] 误报，优先写成“什么页面属于 A 类页面、什么页面属于 B 类页面”，例如“全屏沉浸式视频播放页不应仅因顶部位于推荐 tab 就被当作推荐列表页”。
6. 如果是 [时间限制] 误报，优先写成“哪些页面属于计时目标、哪些页面属于相邻但不同的场景边界”，而不是笼统说“都计时/都不计时”。
7. 规则方向必须和“用户纠正后”的正确判断一致；如果这次应该算计时，就生成帮助未来判为计时的规则；如果这次应该不计时，就生成帮助未来判为不计时的规则。
8. 只围绕当前这条约束写，不要为了覆盖“未来相似约束”而故意放宽。
9. 严禁输出“判定为正常”“不违规”“不计入违规”“直接放行”这类全局放行措辞；要改写成“不要把它识别成当前目标边界”或“它更接近另一类页面”。
10. 不要生成过宽泛的规则，例如“这个 app 都不算”。
11. 禁止出现“只有……才算……”“应判定为……”“都应视为……”这类重定义句式。
12. 如果信息不足以生成高质量规则，返回 no_rule。
13. 输出必须是 JSON，对象格式如下：
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

生成规则时，优先避免与“已有附加规则”重复。最终规则应该像“页面分类边界提示”，而不是“结果放行指令”。只输出 JSON，不要输出解释性文字。
        """.trimIndent()
    }

    suspend fun autoCarryOverHintsForIntent(
        packageName: String,
        appName: String,
        targetConstraint: SessionConstraint,
        existingPackageHints: List<AppHint>
    ): List<AppHint> = withContext(Dispatchers.IO) {
        if (!ApiConfig.isConfigured()) return@withContext emptyList()

        val targetIntentId = buildIntentScopedHintId(targetConstraint)
        val targetIntentLabel = buildIntentScopedHintLabel(targetConstraint)
        if (appHintRepository.getHintsForIntent(packageName, targetIntentId).isNotEmpty()) {
            return@withContext emptyList()
        }

        val candidates = existingPackageHints
            .filter { it.isActive && it.intentId != targetIntentId && it.sourceHintId == null }
            .distinctBy { "${it.intentId}|${it.hintText.trim()}" }
            .take(8)

        if (candidates.isEmpty()) return@withContext emptyList()

        val selectedIds = runCatching {
            selectCarryOverHintIdsWithAi(
                appName = appName,
                packageName = packageName,
                targetConstraint = targetConstraint,
                candidates = candidates
            )
        }.onFailure { e ->
            Logger.w(TAG, "Failed to auto carry over hints: ${e.message}")
        }.getOrDefault(emptyList())

        val saved = mutableListOf<AppHint>()
        selectedIds.take(2).forEach { sourceHintId ->
            val sourceHint = candidates.firstOrNull { it.id == sourceHintId } ?: return@forEach
            val saveResult = appHintRepository.saveHintIfNew(
                packageName = packageName,
                intentId = targetIntentId,
                intentLabel = targetIntentLabel,
                hintText = sourceHint.hintText,
                source = APP_HINT_SOURCE_INTENT_CARRY_OVER,
                sourceHintId = sourceHint.id
            )
            if (saveResult.created) {
                saved += saveResult.hint
            }
        }
        saved
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

    suspend fun saveConfirmedRule(
        packageName: String,
        record: RuleRecord,
        constraints: List<SessionConstraint>,
        ruleText: String,
        source: String? = null,
        sourceHintId: String? = null
    ): GeneratedFalsePositiveRuleResult = withContext(Dispatchers.IO) {
        val targetConstraint = resolveTargetConstraint(record, constraints) ?: constraints.firstOrNull()
            ?: return@withContext GeneratedFalsePositiveRuleResult()
        val targetIntentId = buildIntentScopedHintId(targetConstraint)
        val targetIntentLabel = buildIntentScopedHintLabel(targetConstraint)
        val saveResult = appHintRepository.saveHintIfNew(
            packageName = packageName,
            intentId = targetIntentId,
            intentLabel = targetIntentLabel,
            hintText = ruleText,
            source = source ?: com.seenot.app.data.model.APP_HINT_SOURCE_FEEDBACK_GENERATED,
            sourceHintId = sourceHintId
        )
        GeneratedFalsePositiveRuleResult(
            savedHint = saveResult.hint,
            ruleText = saveResult.hint.hintText,
            reusedExistingHint = !saveResult.created,
            usedUserNoteFallback = false
        )
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
                ?.takeIf { it.isNotBlank() && isSafeSupplementalRule(it) }
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to parse generated false-positive rule: ${e.message}")
            null
        }
    }

    private suspend fun selectCarryOverHintIdsWithAi(
        appName: String,
        packageName: String,
        targetConstraint: SessionConstraint,
        candidates: List<AppHint>
    ): List<String> {
        val prompt = """
你在做 SeeNot 的补充规则复用筛选。

目标：判断哪些“旧 intent 的补充规则”可以安全地自动带入到“当前新 intent”。

原则：
1. 只允许选“非常确定仍然适用”的规则。
2. 如果规则会改写当前 intent 定义、过度收窄、或只对旧 intent 特别成立，就不要选。
3. 宁可少选，也不要误带。
4. 最多选择 2 条。
5. 输出 JSON：
{
  "carry_over_ids": ["id1", "id2"]
}

当前应用：
- 应用名：$appName
- 包名：$packageName

当前新 intent：
- ${buildIntentScopedHintLabel(targetConstraint)}

候选旧规则：
${candidates.joinToString("\n") { "- id=${it.id} | 来自=${it.intentLabel} | 规则=${it.hintText}" }}

只输出 JSON。
        """.trimIndent()

        val responseText = llmClient.completeText(
            userPrompt = prompt,
            temperature = 0.1,
            maxTokens = 300,
            modelOverride = ApiConfig.getFeedbackModel()
        )
        return parseCarryOverSelection(responseText, candidates.map { it.id }.toSet())
    }

    private fun parseCarryOverSelection(responseText: String, validIds: Set<String>): List<String> {
        return try {
            val cleaned = cleanJson(responseText)
            val obj = JsonParser.parseString(cleaned).asJsonObject
            obj.getAsJsonArray("carry_over_ids")
                ?.mapNotNull { it.asString?.trim() }
                ?.filter { it in validIds }
                ?.distinct()
                .orEmpty()
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to parse carry over selection: ${e.message}")
            emptyList()
        }
    }

    private fun resolveTargetConstraint(record: RuleRecord, constraints: List<SessionConstraint>): SessionConstraint? {
        return constraints.firstOrNull { constraint ->
            constraint.type == record.constraintType && constraint.description == record.constraintContent
        } ?: constraints.firstOrNull { constraint ->
            constraint.description == record.constraintContent
        }
    }

    private fun isSafeSupplementalRule(rule: String): Boolean {
        val normalized = rule.replace(Regex("\\s+"), "")
        val dangerousPatterns = listOf(
            Regex("只有.+才"),
            Regex("应判定为"),
            Regex("都应视为"),
            Regex("直接放行"),
            Regex("判定为正常"),
            Regex("不违规"),
            Regex("都算"),
            Regex("都不算")
        )
        return dangerousPatterns.none { it.containsMatchIn(normalized) }
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
