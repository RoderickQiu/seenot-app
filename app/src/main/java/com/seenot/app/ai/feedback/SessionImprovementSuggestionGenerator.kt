package com.seenot.app.ai.feedback

import android.content.Context
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.seenot.app.ai.OpenAiCompatibleClient
import com.seenot.app.config.ApiConfig
import com.seenot.app.config.AppLocalePrefs
import com.seenot.app.data.model.AppHintScopeType
import com.seenot.app.data.model.SessionImprovementRuleDecision
import com.seenot.app.data.model.SessionImprovementSuggestion
import com.seenot.app.domain.SessionImprovementCandidate
import com.seenot.app.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

class SessionImprovementSuggestionGenerator(
    private val context: Context,
    private val client: OpenAiCompatibleClient = OpenAiCompatibleClient(context)
) {
    suspend fun generate(candidate: SessionImprovementCandidate): SessionImprovementSuggestion? {
        if (!candidate.shouldGenerate) return null

        return withContext(Dispatchers.IO) {
            try {
                val outputLanguageName = AppLocalePrefs.getAiOutputLanguageName(context)
                val response = client.completeText(
                    systemPrompt = "You generate concise, user-confirmed SeeNot session improvement suggestions. Output all user-facing suggestion text in the current SeeNot UI language: $outputLanguageName.",
                    userPrompt = buildPrompt(candidate),
                    temperature = 0.2,
                    maxTokens = 700,
                    modelOverride = ApiConfig.getSettings().feedbackModel.takeIf { it.isNotBlank() }
                )
                parseResponse(candidate, response)
            } catch (error: Exception) {
                Logger.w(TAG, "Session improvement generation failed", error)
                null
            }
        }
    }

    private fun buildPrompt(candidate: SessionImprovementCandidate): String {
        val outputLanguageName = AppLocalePrefs.getAiOutputLanguageName(context)
        val recordsText = candidate.evidenceRecords.joinToString("\n\n") { record ->
            buildString {
                appendLine("- record_id: ${record.id}")
                appendLine("  constraint_type: ${record.constraintType?.name.orEmpty()}")
                appendLine("  constraint: ${record.constraintContent.orEmpty()}")
                appendLine("  matched: ${record.isConditionMatched}")
                appendLine("  action: ${record.actionType.orEmpty()} ${record.actionReason.orEmpty()}".trim())
                appendLine("  confidence: ${record.confidence?.let { String.format(Locale.US, "%.2f", it) }.orEmpty()}")
                record.aiResult?.takeIf { it.isNotBlank() }?.let { appendLine("  judgment_reason: $it") }
                record.mediaContext?.let { media ->
                    appendLine("  media:")
                    appendLine("    status: ${media.status}")
                    media.title?.takeIf { it.isNotBlank() }?.let { appendLine("    标题：$it") }
                    media.artist?.takeIf { it.isNotBlank() }?.let { appendLine("    频道/作者：$it") }
                    media.playbackState?.takeIf { it.isNotBlank() }?.let { appendLine("    playback: $it") }
                }
            }
        }

        return """
你是 SeeNot 的会话改进建议模块。SeeNot 不是屏幕时间统计工具，它帮助用户在进入受控 app 后守住本次意图。

任务：
1. 不要写会话总结文章，不要复述流水账。
2. 只给一条“下次更容易守住”的短建议：要么改写 next intent，要么给一条可由用户确认保存的补充边界规则。
3. 如果只能说泛泛鼓励或保持专注，返回 no_suggestion。
4. 媒体信息只能作为辅助证据，不能把标题、频道或播放状态当作绝对事实。
5. 不要自动改变规则；你只输出候选，用户确认后才会生效。

**输出语言规则（最高优先级）：**
- `session_pattern`、`next_intent_suggestion`、`rule_text` 和 `reason` 必须使用 $outputLanguageName
- 不要因为受控 app、证据记录、媒体标题或页面内容是中文就输出中文；也不要因为它们是英文就输出英文。始终跟随 SeeNot 当前界面语言
- 不要输出中英混杂的 session_pattern / next_intent_suggestion / rule_text / reason

输出 JSON，禁止 Markdown：
{
  "decision": "create_suggestion" | "no_suggestion",
  "session_pattern": "一句话说明这次主要问题",
  "next_intent_suggestion": "下次可直接使用的自然语言目标",
  "supplemental_rule_candidate": {
    "decision": "create_rule" | "no_rule",
    "scope_type": "APP_GENERAL" | "INTENT_SPECIFIC",
    "rule_text": "可保存为 AppHint 的边界规则",
    "reason": "为什么建议保存"
  },
  "confidence": "high" | "medium" | "low",
  "evidence_record_ids": ["..."]
}

会话：
- app: ${candidate.appDisplayName}
- package: ${candidate.appPackageName}
- 当前 SeeNot 软件语言: $outputLanguageName
- trigger: ${candidate.primaryTrigger}
- local_confidence: ${candidate.confidence}

证据记录：
$recordsText
        """.trimIndent()
    }

    private fun parseResponse(
        candidate: SessionImprovementCandidate,
        response: String
    ): SessionImprovementSuggestion? {
        val jsonText = extractJsonText(response)
        val root = runCatching { JsonParser.parseString(jsonText).asJsonObject }.getOrNull() ?: return null
        if (root.string("decision") != "create_suggestion") return null

        val nextIntent = root.string("next_intent_suggestion").orEmpty().trim()
        val pattern = root.string("session_pattern").orEmpty().trim()
        if (nextIntent.isBlank() && pattern.isBlank()) return null

        val rule = root.getAsJsonObject("supplemental_rule_candidate")
        val ruleDecision = if (rule?.string("decision") == "create_rule") {
            SessionImprovementRuleDecision.CREATE_RULE
        } else {
            SessionImprovementRuleDecision.NO_RULE
        }
        val scopeType = rule?.string("scope_type")?.let {
            runCatching { AppHintScopeType.valueOf(it) }.getOrNull()
        }

        return SessionImprovementSuggestion(
            sessionId = candidate.sessionId,
            packageName = candidate.appPackageName,
            appName = candidate.appDisplayName,
            sessionPattern = pattern.ifBlank { nextIntent },
            nextIntentSuggestion = nextIntent,
            ruleDecision = ruleDecision,
            ruleText = rule?.string("rule_text")?.takeIf { it.isNotBlank() },
            ruleScopeType = scopeType,
            ruleReason = rule?.string("reason")?.takeIf { it.isNotBlank() },
            confidence = root.string("confidence"),
            evidenceRecordIds = root.getAsJsonArray("evidence_record_ids")
                ?.mapNotNull { it.asString?.takeIf { id -> id.isNotBlank() } }
                ?.ifEmpty { candidate.evidenceRecords.map { it.id } }
                ?: candidate.evidenceRecords.map { it.id }
        )
    }

    private fun JsonObject.string(name: String): String? {
        return get(name)?.takeIf { !it.isJsonNull }?.asString
    }

    private fun extractJsonText(response: String): String {
        val trimmed = response.trim()
        if (!trimmed.startsWith("```")) return trimmed
        return trimmed
            .lineSequence()
            .drop(1)
            .joinToString("\n")
            .substringBeforeLast("```")
            .trim()
    }

    private companion object {
        private const val TAG = "SessionImprovementSuggestionGenerator"
    }
}
