package com.seenot.app.ai.feedback

import android.content.Context
import com.seenot.app.config.AppLocalePrefs
import com.google.gson.JsonParser
import com.seenot.app.ai.OpenAiCompatibleClient
import com.seenot.app.config.ApiConfig
import com.seenot.app.data.model.APP_HINT_SOURCE_INTENT_CARRY_OVER
import com.seenot.app.data.model.AppHint
import com.seenot.app.data.model.AppHintScopeType
import com.seenot.app.data.model.ConstraintType
import com.seenot.app.data.model.RuleRecord
import com.seenot.app.data.model.buildAppGeneralScopeKey
import com.seenot.app.data.model.buildAppGeneralScopeLabel
import com.seenot.app.data.model.buildIntentScopedHintId
import com.seenot.app.data.model.buildIntentScopedHintLabel
import com.seenot.app.data.repository.AppHintRepository
import com.seenot.app.domain.SessionConstraint
import com.seenot.app.observability.RuntimeEventLogger
import com.seenot.app.observability.RuntimeEventType
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
    val scopeType: AppHintScopeType = AppHintScopeType.INTENT_SPECIFIC,
    val scopeKey: String? = null,
    val targetIntentId: String? = null,
    val targetIntentLabel: String? = null
)

class FalsePositiveRuleGenerator(private val context: Context) {

    companion object {
        private const val TAG = "FalsePositiveRuleGen"
    }

    private val appHintRepository = AppHintRepository(context)
    private val runtimeEventLogger = RuntimeEventLogger.getInstance(context)
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
        val targetIntentLabel = buildIntentScopedHintLabel(null, targetConstraint)
        val appGeneralHints = appHintRepository.getHintsForAppGeneral(packageName)
        val intentSpecificHints = appHintRepository.getHintsForIntent(packageName, targetIntentId)
        val trimmedNote = userNote?.trim().orEmpty()

        val generated = if (ApiConfig.isConfigured()) {
            runCatching {
                generateScopedRuleWithAi(
                    appName = appName,
                    packageName = packageName,
                    record = record,
                    targetConstraint = targetConstraint,
                    appGeneralHints = appGeneralHints,
                    intentSpecificHints = intentSpecificHints,
                    userNote = trimmedNote.takeIf { it.isNotBlank() }
                )
            }.onFailure { e ->
                Logger.w(TAG, "Failed to preview false-positive rule with AI: ${e.message}")
            }.getOrNull()
        } else {
            null
        }

        val resolvedScopeType = generated?.scopeType ?: AppHintScopeType.INTENT_SPECIFIC
        val scopeKey = when (resolvedScopeType) {
            AppHintScopeType.APP_GENERAL -> buildAppGeneralScopeKey(packageName)
            AppHintScopeType.INTENT_SPECIFIC -> targetIntentId
        }
        val label = when (resolvedScopeType) {
            AppHintScopeType.APP_GENERAL -> buildAppGeneralScopeLabel(null)
            AppHintScopeType.INTENT_SPECIFIC -> targetIntentLabel
        }

        FalsePositiveRulePreview(
            ruleText = generated?.ruleText,
            scopeType = resolvedScopeType,
            scopeKey = scopeKey,
            targetIntentId = scopeKey,
            targetIntentLabel = label
        )
    }

    suspend fun generateAndSaveRule(
        packageName: String,
        appName: String,
        record: RuleRecord,
        constraints: List<SessionConstraint>,
        userNote: String? = null
    ): GeneratedFalsePositiveRuleResult = withContext(Dispatchers.IO) {
        val preview = generateRulePreview(
            packageName = packageName,
            appName = appName,
            record = record,
            constraints = constraints,
            userNote = userNote
        )
        val finalRule = preview.ruleText ?: userNote?.trim()?.takeIf { it.isNotBlank() }
        if (finalRule.isNullOrBlank()) {
            return@withContext GeneratedFalsePositiveRuleResult()
        }

        val saveResult = appHintRepository.saveHintIfNew(
            packageName = packageName,
            scopeType = preview.scopeType,
            scopeKey = preview.scopeKey.orEmpty(),
            intentId = preview.targetIntentId.orEmpty(),
            intentLabel = preview.targetIntentLabel.orEmpty(),
            hintText = finalRule
        )

        runtimeEventLogger.log(
            eventType = RuntimeEventType.RULE_GENERATED,
            sessionId = record.sessionId,
            appPackage = packageName,
            appDisplayName = appName,
            payload = mapOf(
                "rule_id" to saveResult.hint.id,
                "source_record_id" to record.id,
                "constraint_id" to record.constraintId,
                "scope_type" to preview.scopeType.name,
                "reused_existing_rule" to !saveResult.created
            )
        )

        GeneratedFalsePositiveRuleResult(
            savedHint = saveResult.hint,
            ruleText = saveResult.hint.hintText,
            reusedExistingHint = !saveResult.created,
            usedUserNoteFallback = preview.ruleText.isNullOrBlank() && !userNote.isNullOrBlank()
        )
    }

    suspend fun saveConfirmedRule(
        packageName: String,
        record: RuleRecord,
        constraints: List<SessionConstraint>,
        scopeType: AppHintScopeType,
        ruleText: String,
        source: String? = null,
        sourceHintId: String? = null
    ): GeneratedFalsePositiveRuleResult = withContext(Dispatchers.IO) {
        val targetConstraint = resolveTargetConstraint(record, constraints) ?: constraints.firstOrNull()
            ?: return@withContext GeneratedFalsePositiveRuleResult()

        val scopeKey = when (scopeType) {
            AppHintScopeType.APP_GENERAL -> buildAppGeneralScopeKey(packageName)
            AppHintScopeType.INTENT_SPECIFIC -> buildIntentScopedHintId(targetConstraint)
        }
        val label = when (scopeType) {
            AppHintScopeType.APP_GENERAL -> buildAppGeneralScopeLabel(null)
            AppHintScopeType.INTENT_SPECIFIC -> buildIntentScopedHintLabel(null, targetConstraint)
        }

        val saveResult = appHintRepository.saveHintIfNew(
            packageName = packageName,
            scopeType = scopeType,
            scopeKey = scopeKey,
            intentId = scopeKey,
            intentLabel = label,
            hintText = ruleText,
            source = source ?: com.seenot.app.data.model.APP_HINT_SOURCE_FEEDBACK_GENERATED,
            sourceHintId = sourceHintId
        )
        runtimeEventLogger.log(
            eventType = RuntimeEventType.RULE_GENERATED,
            sessionId = record.sessionId,
            appPackage = packageName,
            appDisplayName = packageName,
            payload = mapOf(
                "rule_id" to saveResult.hint.id,
                "source_record_id" to record.id,
                "constraint_id" to record.constraintId,
                "scope_type" to scopeType.name,
                "reused_existing_rule" to !saveResult.created,
                "source_hint_id" to sourceHintId
            )
        )
        GeneratedFalsePositiveRuleResult(
            savedHint = saveResult.hint,
            ruleText = saveResult.hint.hintText,
            reusedExistingHint = !saveResult.created,
            usedUserNoteFallback = false
        )
    }

    suspend fun autoCarryOverHintsForIntent(
        packageName: String,
        appName: String,
        targetConstraint: SessionConstraint,
        existingPackageHints: List<AppHint>
    ): List<AppHint> = withContext(Dispatchers.IO) {
        if (!ApiConfig.isConfigured()) return@withContext emptyList()

        val targetIntentId = buildIntentScopedHintId(targetConstraint)
        val targetIntentLabel = buildIntentScopedHintLabel(null, targetConstraint)
        if (appHintRepository.getHintsForIntent(packageName, targetIntentId).isNotEmpty()) {
            return@withContext emptyList()
        }

        val candidates = existingPackageHints
            .filter {
                it.isActive &&
                    it.scopeType == AppHintScopeType.INTENT_SPECIFIC &&
                    it.intentId != targetIntentId &&
                    it.sourceHintId == null
            }
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
                scopeType = AppHintScopeType.INTENT_SPECIFIC,
                scopeKey = targetIntentId,
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

    private suspend fun generateScopedRuleWithAi(
        appName: String,
        packageName: String,
        record: RuleRecord,
        targetConstraint: SessionConstraint,
        appGeneralHints: List<AppHint>,
        intentSpecificHints: List<AppHint>,
        userNote: String?
    ): ScopedRuleGenerationResult? {
        val prompt = buildPrompt(
            appName = appName,
            packageName = packageName,
            record = record,
            targetConstraint = targetConstraint,
            appGeneralHints = appGeneralHints,
            intentSpecificHints = intentSpecificHints,
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
        appGeneralHints: List<AppHint>,
        intentSpecificHints: List<AppHint>,
        userNote: String?
    ): String {
        val outputLanguageName = AppLocalePrefs.getAiOutputLanguageName(context)

        val constraintsText = run {
            val type = when (targetConstraint.type) {
                ConstraintType.DENY -> "禁止"
                ConstraintType.TIME_CAP -> "时间限制"
            }
            val timePart = targetConstraint.timeLimitMs?.let { "，时长 ${it / 60000.0} 分钟" } ?: ""
            val scopePart = targetConstraint.timeScope?.let { "，范围 ${it.name}" } ?: ""
            "- [$type] ${targetConstraint.description}$timePart$scopePart"
        }

        val appGeneralHintsText = if (appGeneralHints.isNotEmpty()) {
            appGeneralHints.take(8).joinToString("\n") { "- ${it.hintText}" }
        } else {
            "- 暂无"
        }

        val intentSpecificHintsText = if (intentSpecificHints.isNotEmpty()) {
            intentSpecificHints.take(8).joinToString("\n") { "- ${it.hintText}" }
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

        val systemDecisionValue = when (record.constraintType) {
            ConstraintType.TIME_CAP -> if (record.isConditionMatched) "in_scope" else "out_of_scope"
            ConstraintType.DENY -> if (record.isConditionMatched) "safe" else "violates"
            null -> "unknown"
        }

        val correctedDecisionValue = when (record.constraintType) {
            ConstraintType.TIME_CAP -> if (record.isConditionMatched) "out_of_scope" else "in_scope"
            ConstraintType.DENY -> if (record.isConditionMatched) "violates" else "safe"
            null -> "unknown"
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

        val denyCorrectionSemanticsText = when (record.constraintType) {
            ConstraintType.DENY -> if (record.isConditionMatched) {
                """
                对这条 DENY 约束，这次用户纠正的真实语义是：当前截图其实应该被纳入"会触发干预/被禁止"的集合，而不是被排除在该集合外。
                - 如果当前 intent 是直接禁止型 blocklist，这意味着：当前截图已经足以算作 description 所描述的被禁对象。
                - 如果当前 intent 是补集型 exclusive/allowlist（例如"除 X 外的其他内容"），这意味着：当前截图仍不能被确认属于例外集合 X，因此仍应落在补集侧并触发干预。
                - 因而你生成的规则必须帮助系统未来更容易把同类截图纳入触发集合；不能写成"哪些情况不算违规"、"哪些情况应放过"、或任何把当前截图排除出去的规则。
                """.trimIndent()
            } else {
                """
                对这条 DENY 约束，这次用户纠正的真实语义是：当前截图其实不应该被纳入"会触发干预/被禁止"的集合。
                - 如果当前 intent 是直接禁止型 blocklist，这意味着：当前截图还不足以算作 description 所描述的被禁对象。
                - 如果当前 intent 是补集型 exclusive/allowlist（例如"除 X 外的其他内容"），这意味着：当前截图已经有足够证据属于例外集合 X，或至少不能继续把它算作补集侧。
                - 因而你生成的规则必须帮助系统未来把同类截图排除出触发集合；不能反过来加强禁止侧 membership。
                """.trimIndent()
            }
            else -> "无"
        }

        val denyCorrectionSemanticsSection = if (record.constraintType == ConstraintType.DENY) {
            """

补充说明（仅适用于本次 DENY 纠正）：
- $denyCorrectionSemanticsText
            """.trimIndent()
        } else {
            ""
        }

        val recordConstraintType = when (record.constraintType) {
            ConstraintType.DENY -> "禁止"
            ConstraintType.TIME_CAP -> "时间限制"
            null -> "未知"
        }

        return """
你是 SeeNot 的误判纠偏引擎。用户已经明确确认：下面这条判断是误判。

你的任务：基于原始 intent、当前截图、应用特点、已有补充规则和用户补充说明，先判断"当前截图与这个 intent 的归属关系为什么错了"，再生成 1 条新的"补充判断规则"，用于后续减少同类误判。

但你必须先判断这条规则更适合沉淀在哪一层：
1. APP_GENERAL：适用于这个 app 的通用页面边界，可被多个 intent 复用
2. INTENT_SPECIFIC：只适用于当前这一条 intent / 约束

注意：用户已经明确确认"系统这次判断反了"。你必须以"用户纠正后的正确判断"为准生成规则，不能顺着系统原判断继续加强。
其中"系统错误解释"是本次误判的反例材料，不是可信事实；尤其不能把其中的页面名、模块名、功能名直接当作截图真实所在位置。

要求：
1. 只输出 1 条规则，必须具体、窄、可执行。
2. 规则的作用是细化边界或说明例外，不能推翻用户原始 intent，更不能重写它的完整定义。
3. 如果你选择 APP_GENERAL，规则必须描述这个 app 的通用页面机制或通用边界，不要把当前主题词当成核心锚点。
4. 如果你选择 INTENT_SPECIFIC，规则可以围绕当前 intent 的具体目标边界来写。
5. 只有当截图里有直接、明确的界面证据能确认当前就在某个具体模块时，才允许在规则里写这个模块名；不要因为页面里出现了相关内容、入口、卡片、推荐流、预览、话题词，就推断用户已经进入那个模块。
6. 如果不能高把握确认具体模块，就不要硬写模块名；优先改写成更稳健的页面边界描述，例如"在非目标模块中出现相关内容卡片/入口/预览时，不算进入目标模块"。
7. 规则应该优先描述"页面边界 / 场景边界 / 功能边界"，而不是直接给出全局放行结论。
8. 如果截图更像是"别的页面里出现了目标相关内容"，要明确区分"看到相关内容"与"已经进入目标模块/目标页面"是两回事，不能混为一谈。
9. 规则方向必须和"用户纠正后"的正确判断一致；如果这次应该算计时，就生成帮助未来判为计时的规则；如果这次应该不计时，就生成帮助未来判为不计时的规则。
9.5. 对于 DENY 约束，你必须先判断它属于哪一种语义：
   - 直接禁止型 blocklist：description 直接写被禁止内容，例如"朋友圈"、"视频号"
   - 补集型 exclusive/allowlist：description 写的是"除 X 外的其他内容"、"all other content except X" 或等价语义，表示真正被允许/保留的是例外集合 X，而不是 description 里字面提到的"其他内容"
   - 如果是补集型 exclusive/allowlist，后续所有 membership 判断都必须围绕"当前截图是否明确落在被允许的例外集合 X 内"来做；严禁把它当成普通 blocklist 去加强某个被禁对象，也严禁把补充规则写成"X 相关都放行"这种正向 allow 规则
9.6. 如果当前约束是 DENY，必须额外遵守：
   - 当用户纠正后的目标 decision 是 `violates` 时，你的核心任务是加强"当前截图属于触发集合"的 membership 判断，而不是描述如何把它排除出去。
   - 当用户纠正后的目标 decision 是 `safe` 时，你的核心任务是加强"当前截图不属于触发集合"的排除条件，而不是描述如何把它纳入进去。
   - 换句话说：`safe -> wrong` 在 DENY 下不表示"系统太严了要放松"，而表示"系统太松了，这里其实应该拦"。
10. 必须严格按两个阶段完成任务：
   - 阶段 A：先只回答"当前截图相对于当前 intent，为什么应该判成 `$correctedDecisionValue` 而不是 `$systemDecisionValue`"。这一步只允许围绕当前 intent 本身做 membership 判断。
   - 阶段 B：再把阶段 A 的结论压缩成 1 条可复用的规则。
   - 阶段 B 不得引入阶段 A 没有用到的新对照类、新来源路径或新页面假设。
11. 必须先判断本次误判主要属于哪个错误层级，并在 `error_type` 中输出：
   - SURFACE_CONTAINER：页面、模块、内容容器识别错
   - CONTENT_TOPIC：页面/容器对了，但正文主题、对象、语义边界判断错
   - ACTION_ENGAGEMENT：页面和内容相关，但用户行为强度判断错，例如入口/预览/曝光 vs 主动打开/消费/发布/互动
   - SOURCE_PROVENANCE：承载页面和内容来源混淆，例如动态中分享的文章、卡片、外链、小程序、视频等
   - STATE_MODE：同一功能下状态判断错，例如编辑态、发布态、详情态、评论区、搜索结果、通知页、设置页
   - EVIDENCE_UNCERTAINTY：截图证据不足，系统却高置信猜测模块、主题或行为
   - MIXED_UNKNOWN：多个层级混合或无法可靠区分
12. 阶段 A 只能讨论"当前 intent 的纳入条件 / 排除条件 / 边界条件"，不能把注意力转移到无关 intent 或无关对照类。
13. 如果你发现自己在解释时主要在说"它更像别的什么"，而不是"它为什么不属于当前 intent / 为什么属于当前 intent"，说明推理跑偏了；此时返回 no_rule。
14. 生成规则必须只修正被选中的错误层级，不能跨层级改写 intent；如果错误层级不清楚，返回 no_rule。
15. 只有当截图有直接页面结构证据确认当前就在目标模块/目标页面时，才可以把目标模块/目标页面写成规则前提。
16. 如果 `error_type` 是 SURFACE_CONTAINER 或 EVIDENCE_UNCERTAINTY，且本次纠正方向是 out_of_scope/safe，规则应围绕"非目标页面/非目标容器不应被算作目标"写；不要把系统错误解释里的目标页面名当作前提。
17. 如果 `error_type` 是 CONTENT_TOPIC，且截图证据确认当前确实在目标模块内，可以用目标模块作为前提来细化内容主题边界。
18. 如果 `error_type` 是 SOURCE_PROVENANCE，必须明确本 intent 应按"承载页面/用户所在容器"还是按"内容来源/被打开的原始内容"判断，不能把二者混用。
19. 当用户纠正为 out_of_scope/safe 时，规则只能排除非目标页面、非目标内容、非目标行为或证据不足场景；不能把原 intent 明确包含的核心目标排除掉。例：intent 是"朋友圈内容"时，不能生成"普通朋友圈图文动态不算朋友圈内容"；但 intent 是"体育相关朋友圈内容"时，可以在确认确实位于朋友圈的前提下细化"非体育主题不算"。
20. 如果原始 intent 包含多个可选目标（例如"X 或者 Y"、"X 或 Y"、"X/Y"），且用户纠正后的正确判断是 out_of_scope/safe，那么规则必须说明当前截图为什么不属于任何一个可选目标；严禁加强其中任意一个目标的正向判断。若不能同时排除所有可选目标，返回 no_rule。
21. 当用户纠正为 in_scope/violates 时，规则必须说明当前截图中哪些可见证据足以把它纳入原 intent；不能编造截图中不可见的来源路径、入口路径或上一跳页面。
21.5. 特别地，当约束类型是 DENY 且目标 decision 是 `violates`：
   - 如果是 blocklist，规则必须回答"当前截图具备哪些可见证据，因此已经算作被禁止对象"。
   - 如果是补集型 exclusive/allowlist，规则必须回答"当前截图缺少哪些必要证据，因此仍不能算作例外集合 X，仍应落在补集/触发侧"。
   - 不允许输出任何本质上把当前截图解释为 safe 的规则。
22. 严禁生成截图中没有直接证据的上下文前提，例如"在聊天窗口中发送/接收""从群聊打开""从朋友圈分享进入""从推荐流点击进入"。如果截图只展示已打开的内容详情，只能基于当前可见的详情页结构和内容证据写规则。
23. `supplemental_rule` 必须描述页面边界、内容边界、行为边界或来源边界本身，不要把系统 decision 标签直接写进规则文本。禁止在规则正文中出现 `in_scope`、`out_of_scope`、`safe`、`violates`、`应判定为`、`应计时`、`不计时`、`违规`、`正常` 这类结果词；这些只能体现在你的内部推理和 `reason` 中，不能写进规则本身。
23.5. 如果当前 intent 是补集型 exclusive/allowlist（例如"除 X 外的其他内容" / "all other content except X"）：
   - `supplemental_rule` 只能细化"什么情况下仍然不能确认属于例外集合 X"、或"例外集合 X 的必要页面/内容/行为证据是什么"
   - 当本次纠正目标是 safe/out_of_scope 时，优先写"仅因出现 X 相关词、卡片、入口、推荐、预览，不能视为已落入例外集合 X"
   - 当本次纠正目标是 violates/in_scope 时，优先写"只有哪些当前可见证据足以确认已落入例外集合 X，缺少这些证据时仍按补集处理"
   - 不得把规则写成对 X 的泛化放行、不得把补集型 intent 改写成普通"禁止 Y"、也不得只排除某一个 Y 而忽略"除 X 外的其他内容"这一整体语义
24. 如果信息不足以判断错误层级或生成高质量规则，返回 no_rule。
25. 输出必须是 JSON，对象格式如下：
{
  "decision": "create_rule" 或 "no_rule",
  "scope_type": "APP_GENERAL" 或 "INTENT_SPECIFIC",
  "error_type": "SURFACE_CONTAINER / CONTENT_TOPIC / ACTION_ENGAGEMENT / SOURCE_PROVENANCE / STATE_MODE / EVIDENCE_UNCERTAINTY / MIXED_UNKNOWN",
  "supplemental_rule": "规则文本",
  "reason": "一句话解释"
}

**输出语言规则（最高优先级）：**
- `supplemental_rule` 和 `reason` 必须使用 $outputLanguageName
- 不要因为截图内容是中文就输出中文；也不要因为截图内容是英文就输出英文。始终跟随 SeeNot 当前界面语言
- 不要输出中英混杂的 supplemental_rule / reason

应用信息：
- 应用名：$appName
- 包名：$packageName

当前 intent / 约束：
$constraintsText

这条误判 record：
- 约束类型：$recordConstraintType
- $judgmentText
- $correctedJudgmentText
- 系统原 decision：$systemDecisionValue
- 用户纠正后的目标 decision：$correctedDecisionValue
- 系统错误解释（只作反例，不可当作事实）：${record.aiResult ?: "未知"}

本次纠正目标：
- $correctionGoalText

$denyCorrectionSemanticsSection

已有通用边界规则：
$appGeneralHintsText

当前 intent 已有专属补充规则：
$intentSpecificHintsText

用户补充说明：
${userNote ?: "无"}

只输出 JSON，不要输出解释性文字。
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
                    maxTokens = 700,
                    modelOverride = ApiConfig.getFeedbackModel()
                )
            } else {
                llmClient.completeText(
                    userPrompt = prompt,
                    temperature = 0.2,
                    maxTokens = 700,
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

    private fun parseGeneratedRule(responseText: String): ScopedRuleGenerationResult? {
        return try {
            val cleaned = cleanJson(responseText)
            val obj = JsonParser.parseString(cleaned).asJsonObject
            val decision = obj.get("decision")?.asString?.trim()?.lowercase()
            if (decision != "create_rule") {
                return null
            }
            val scopeType = when (obj.get("scope_type")?.asString?.trim()?.uppercase()) {
                AppHintScopeType.APP_GENERAL.name -> AppHintScopeType.APP_GENERAL
                else -> AppHintScopeType.INTENT_SPECIFIC
            }
            val ruleText = obj.get("supplemental_rule")
                ?.asString
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: return null
            ScopedRuleGenerationResult(
                scopeType = scopeType,
                ruleText = ruleText
            )
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

目标：判断哪些"旧 intent 的专属补充规则"可以安全地自动带入到"当前新 intent"。

原则：
1. 只允许选"非常确定仍然适用"的规则。
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
- ${buildIntentScopedHintLabel(null, targetConstraint)}

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

    private fun cleanJson(text: String): String {
        var cleaned = text.trim()

        if (cleaned.startsWith("```")) {
            val firstNewline = cleaned.indexOf('\n')
            if (firstNewline >= 0) {
                cleaned = cleaned.substring(firstNewline + 1)
            }
        }

        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length - 3)
        }

        cleaned = cleaned.trimEnd('`')

        if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(3).trim()
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length - 3).trim()
        }

        val jsonStart = cleaned.indexOf('{')
        if (jsonStart >= 0) {
            var braceCount = 0
            var inString = false
            var escape = false
            for (i in jsonStart until cleaned.length) {
                val c = cleaned[i]
                when {
                    escape -> escape = false
                    c == '\\' -> escape = true
                    c == '"' -> inString = !inString
                    !inString -> when (c) {
                        '{' -> braceCount++
                        '}' -> {
                            braceCount--
                            if (braceCount == 0) {
                                cleaned = cleaned.substring(jsonStart, i + 1)
                                break
                            }
                        }
                    }
                }
            }
        }

        return cleaned.trim()
    }

    private data class ScopedRuleGenerationResult(
        val scopeType: AppHintScopeType,
        val ruleText: String
    )
}
