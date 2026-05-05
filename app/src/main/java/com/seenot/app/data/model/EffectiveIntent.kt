package com.seenot.app.data.model

/**
 * Internal semantic representation of a user-visible constraint.
 *
 * This is not displayed or edited directly. The user-facing rule remains
 * SessionConstraint.description / IntentConstraintEntity.contentPattern.
 */
data class EffectiveIntent(
    val raw: String,
    val type: ConstraintType,
    val prohibitedSet: String,
    val allowedSet: String? = null,
    val evaluationScope: String,
    val aggregatePagePolicy: String,
    val decisionRule: String
)
