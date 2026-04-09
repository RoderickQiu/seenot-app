package com.seenot.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for app-specific hints
 * 
 * Each app can have user-defined hints that provide context to AI
 * to reduce misclassification errors.
 * 
 * Example: For QQ, user might add hint "我只用QQ聊天，不看QQ空间"
 * This helps AI distinguish between QQ chat and QQ space.
 */
@Entity(
    tableName = "app_hints",
    indices = [
        Index("packageName"),
        Index("isActive")
    ]
)
data class AppHintEntity(
    @PrimaryKey
    val id: String,
    val packageName: String,
    val scopeType: String = "INTENT_SPECIFIC",
    val scopeKey: String = "",
    val intentId: String,
    val intentLabel: String,
    val hintText: String,
    val source: String = "manual",
    val sourceHintId: String? = null,
    val isActive: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
