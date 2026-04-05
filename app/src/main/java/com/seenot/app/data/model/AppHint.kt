package com.seenot.app.data.model

import java.util.Date

/**
 * Domain model for app-specific hints
 */
data class AppHint(
    val id: String = java.util.UUID.randomUUID().toString(),
    val packageName: String,
    val intentId: String,
    val intentLabel: String,
    val hintText: String,
    val source: String = APP_HINT_SOURCE_MANUAL,
    val sourceHintId: String? = null,
    val isActive: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    val createdDate: Date
        get() = Date(createdAt)
}
