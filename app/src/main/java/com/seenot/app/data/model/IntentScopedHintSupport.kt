package com.seenot.app.data.model

import android.content.Context
import com.seenot.app.R
import com.seenot.app.domain.SessionConstraint
import java.security.MessageDigest

const val APP_HINT_SOURCE_MANUAL = "manual"
const val APP_HINT_SOURCE_FEEDBACK_GENERATED = "feedback_generated"
const val APP_HINT_SOURCE_INTENT_CARRY_OVER = "intent_carry_over"

enum class AppHintScopeType {
    APP_GENERAL,
    INTENT_SPECIFIC
}

fun buildAppGeneralScopeKey(packageName: String): String = "app::$packageName"

fun buildIntentScopedHintId(constraint: SessionConstraint): String {
    val normalizedDescription = constraint.description
        .trim()
        .replace(Regex("\\s+"), " ")
        .lowercase()
    val raw = listOf(
        constraint.type.name,
        normalizedDescription,
        constraint.timeLimitMs?.toString().orEmpty(),
        constraint.timeScope?.name.orEmpty(),
        constraint.interventionLevel.name
    ).joinToString("|")
    return "intent_" + sha256(raw).take(16)
}

/**
 * Returns the scope label for APP_GENERAL hints.
 * @param context Android context for string resource resolution. If null, returns the default Chinese string
 *                (suitable for AI prompts where the prompt language is Chinese).
 */
fun buildAppGeneralScopeLabel(context: Context?): String =
    context?.getString(R.string.scope_app_general) ?: "整个 app 都适用"

/**
 * Returns a human-readable label for a constraint, suitable for display in UI or AI prompts.
 * @param context Android context for string resource resolution. If null, uses default Chinese strings
 *                (suitable for AI prompts where the prompt language is Chinese).
 */
fun buildIntentScopedHintLabel(context: Context?, constraint: SessionConstraint): String {
    val typeLabel = when (constraint.type) {
        ConstraintType.DENY -> context?.getString(R.string.rule_label_deny) ?: "禁止"
        ConstraintType.TIME_CAP -> context?.getString(R.string.rule_label_time_cap) ?: "时间限制"
    }
    val extras = buildList {
        constraint.timeLimitMs?.let { add(context?.getString(R.string.duration_minutes, it / 60000) ?: "${it / 60000}分钟") }
        constraint.timeScope?.let { add(it.name) }
    }.joinToString(" / ")

    return if (extras.isBlank()) {
        "[$typeLabel] ${constraint.description}"
    } else {
        "[$typeLabel] ${constraint.description} ($extras)"
    }
}

private fun sha256(text: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
}
