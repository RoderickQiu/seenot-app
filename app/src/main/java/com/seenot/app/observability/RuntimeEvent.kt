package com.seenot.app.observability

data class RuntimeEvent(
    val eventId: String,
    val eventType: String,
    val timestamp: Long,
    val sessionId: Long?,
    val appPackage: String?,
    val appDisplayName: String?,
    val participantId: String,
    val payload: Map<String, @JvmSuppressWildcards Any?>
)
