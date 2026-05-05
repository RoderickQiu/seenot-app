package com.seenot.app.ai.parser

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.seenot.app.ai.OpenAiCompatibleClient
import com.seenot.app.config.AppLocalePrefs
import com.seenot.app.data.model.ConstraintType
import com.seenot.app.data.model.EffectiveIntent
import com.seenot.app.data.model.TimeScope
import com.seenot.app.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

open class EffectiveIntentMigrator(
    private val contextRef: () -> Context,
    private val llmClient: OpenAiCompatibleClient = OpenAiCompatibleClient()
) {
    companion object {
        private const val TAG = "EffectiveIntentMigrator"
    }

    private val gson = Gson()

    open suspend fun migrate(
        type: ConstraintType,
        description: String,
        timeLimitMs: Long?,
        timeScope: TimeScope?,
        existing: EffectiveIntent? = null
    ): EffectiveIntent = withContext(Dispatchers.IO) {
        if (existing != null) return@withContext existing

        val fallback = buildFallback(
            type = type,
            description = description,
            timeScope = timeScope
        )

        if (type == ConstraintType.NO_MONITOR) return@withContext fallback

        for (attempt in 1..2) {
            try {
                return@withContext parseEffectiveIntent(
                    response = llmClient.completeText(
                        systemPrompt = buildSystemPrompt(),
                        userPrompt = buildMigrationPrompt(
                            type = type,
                            description = description,
                            timeLimitMs = timeLimitMs,
                            timeScope = timeScope
                        ),
                        temperature = 0.1,
                        maxTokens = 700
                    ),
                    fallback = fallback
                )
            } catch (e: Exception) {
                Logger.w(TAG, "Effective intent migration failed on attempt $attempt: ${e.message}")
            }
        }

        fallback
    }

    open fun buildFallback(
        type: ConstraintType,
        description: String,
        timeScope: TimeScope? = null
    ): EffectiveIntent {
        val raw = description.trim()
        return when (type) {
            ConstraintType.DENY -> EffectiveIntent(
                raw = raw,
                type = type,
                prohibitedSet = raw,
                allowedSet = null,
                evaluationScope = "constraint_defined_scope",
                aggregatePagePolicy = "follow_constraint_target",
                decisionRule = "Return violates only when the current actively used feature, content type, topic, or behavior clearly falls inside prohibited_set. Do not infer violation from missing allowed evidence alone."
            )

            ConstraintType.TIME_CAP -> EffectiveIntent(
                raw = raw,
                type = type,
                prohibitedSet = raw,
                allowedSet = null,
                evaluationScope = timeScope?.name ?: TimeScope.SESSION.name,
                aggregatePagePolicy = "not_applicable",
                decisionRule = "Return in_scope when the current screen is within the time-limited scope; return out_of_scope otherwise. Never return violates or safe for TIME_CAP."
            )

            ConstraintType.NO_MONITOR -> EffectiveIntent(
                raw = raw,
                type = type,
                prohibitedSet = "",
                allowedSet = null,
                evaluationScope = "not_monitored",
                aggregatePagePolicy = "not_applicable",
                decisionRule = "Do not analyze or intervene for this session intent."
            )
        }
    }

    fun toJson(effectiveIntent: EffectiveIntent?): String? {
        return effectiveIntent?.let(gson::toJson)
    }

    fun fromJson(json: String?): EffectiveIntent? {
        if (json.isNullOrBlank()) return null
        return runCatching {
            gson.fromJson(json, EffectiveIntent::class.java)
        }.onFailure { e ->
            Logger.w(TAG, "Failed to parse stored effective intent: ${e.message}")
        }.getOrNull()
    }

    private fun buildSystemPrompt(): String {
        val outputLanguageName = AppLocalePrefs.getAiOutputLanguageName(contextRef())
        return "You migrate user-visible SeeNot rules into an internal semantic representation. Preserve user-facing fields exactly. Output strict JSON only. Use $outputLanguageName for natural-language field values when possible."
    }

    private fun buildMigrationPrompt(
        type: ConstraintType,
        description: String,
        timeLimitMs: Long?,
        timeScope: TimeScope?
    ): String {
        return """
Migrate this existing user-visible constraint into an internal effective_intent.

Input constraint:
{
  "type": "${type.name}",
  "description": ${gson.toJson(description)},
  "timeLimitMs": $timeLimitMs,
  "timeScope": ${gson.toJson(timeScope?.name ?: TimeScope.SESSION.name)}
}

Do not rewrite or correct the user-visible description. Do not change type, timeLimitMs, timeScope, intervention level, active/default state, or any user-facing rule.

Return only:
{
  "effectiveIntent": {
    "raw": "same user-visible description",
    "type": "DENY|TIME_CAP|NO_MONITOR",
    "prohibitedSet": "what exactly triggers intervention or is counted",
    "allowedSet": "what is allowed, or null",
    "evaluationScope": "single_content_only|aggregate_container|feature_or_behavior|session|not_monitored|...",
    "aggregatePagePolicy": "candidate_exposure_is_safe|aggregate_container_can_violate|follow_constraint_target|not_applicable",
    "decisionRule": "concise operational rule for the screen analyzer"
  }
}

Semantic rules:
- The effective intent is internal and must be general across apps, languages, and content domains.
- For DENY constraints that prohibit candidate content/topic/type, aggregate pages that merely expose candidate cards should be safe unless the user is actively consuming a single item that clearly falls into prohibitedSet.
- For DENY constraints that prohibit the aggregate container or feed/list/recommendation surface itself, the aggregate page may violate.
- For complement-style DENY constraints that mean "everything except X", allowedSet should describe X and decisionRule must be conservative: missing evidence for X is not enough to infer violation.
- For TIME_CAP, use in_scope/out_of_scope semantics only; never encode violation semantics.
        """.trimIndent()
    }

    private fun parseEffectiveIntent(response: String, fallback: EffectiveIntent): EffectiveIntent {
        val json = extractJsonObject(response)
        val root = JsonParser.parseString(json).asJsonObject
        val obj = root.getAsJsonObject("effectiveIntent")
            ?: root.getAsJsonObject("effective_intent")
            ?: root.takeIf { hasEffectiveIntentFields(it) }
            ?: return fallback

        val type = obj.getString("type")
            ?.let { runCatching { ConstraintType.valueOf(it.uppercase()) }.getOrNull() }
            ?: fallback.type

        return EffectiveIntent(
            raw = obj.getString("raw") ?: fallback.raw,
            type = type,
            prohibitedSet = obj.getString("prohibitedSet")
                ?: obj.getString("prohibited_set")
                ?: fallback.prohibitedSet,
            allowedSet = obj.getNullableString("allowedSet")
                ?: obj.getNullableString("allowed_set")
                ?: fallback.allowedSet,
            evaluationScope = obj.getString("evaluationScope")
                ?: obj.getString("evaluation_scope")
                ?: fallback.evaluationScope,
            aggregatePagePolicy = obj.getString("aggregatePagePolicy")
                ?: obj.getString("aggregate_page_policy")
                ?: fallback.aggregatePagePolicy,
            decisionRule = obj.getString("decisionRule")
                ?: obj.getString("decision_rule")
                ?: fallback.decisionRule
        )
    }

    private fun hasEffectiveIntentFields(obj: JsonObject): Boolean {
        return obj.has("raw") && (obj.has("prohibitedSet") || obj.has("prohibited_set"))
    }

    private fun JsonObject.getString(name: String): String? {
        val value = get(name) ?: return null
        if (value.isJsonNull) return null
        return value.asString?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun JsonObject.getNullableString(name: String): String? {
        val value = get(name) ?: return null
        if (value.isJsonNull) return null
        return value.asString.trim().takeIf { it.isNotBlank() }
    }

    private fun extractJsonObject(response: String): String {
        val direct = response.trim()
        if (direct.startsWith("{") && direct.endsWith("}")) return direct

        val start = response.indexOf('{')
        val end = response.lastIndexOf('}')
        if (start >= 0 && end > start) {
            return response.substring(start, end + 1)
        }

        throw IllegalArgumentException("Migration response did not contain a JSON object")
    }
}
