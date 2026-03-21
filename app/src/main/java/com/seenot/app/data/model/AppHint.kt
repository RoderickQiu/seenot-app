package com.seenot.app.data.model

import java.util.Date

/**
 * Domain model for app-specific hints
 */
data class AppHint(
    val id: String = java.util.UUID.randomUUID().toString(),
    val packageName: String,
    val hintText: String,
    val isActive: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    val createdDate: Date
        get() = Date(createdAt)
}
