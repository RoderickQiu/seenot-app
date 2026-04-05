package com.seenot.app.data.model

import com.seenot.app.domain.SessionConstraint
import java.security.MessageDigest

const val APP_HINT_SOURCE_MANUAL = "manual"
const val APP_HINT_SOURCE_FEEDBACK_GENERATED = "feedback_generated"
const val APP_HINT_SOURCE_INTENT_CARRY_OVER = "intent_carry_over"

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

fun buildIntentScopedHintLabel(constraint: SessionConstraint): String {
    val typeLabel = when (constraint.type) {
        ConstraintType.DENY -> "禁止"
        ConstraintType.TIME_CAP -> "时间限制"
    }
    val extras = buildList {
        constraint.timeLimitMs?.let { add("${it / 60000} 分钟") }
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
