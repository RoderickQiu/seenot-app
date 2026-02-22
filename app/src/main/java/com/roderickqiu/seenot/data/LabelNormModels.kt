package com.roderickqiu.seenot.data

data class ScreenObservation(
    val observationId: String,
    val timestamp: Long,
    val appName: String,
    val packageName: String? = null,
    val screenshotHash: String,
    val rawReason: String,
    val confidence: Double = 0.0,
    val recordIds: List<String> = emptyList(),
    val labelId: String? = null,
    val normalizedAt: Long? = null
)

data class ContentLabel(
    val labelId: String,
    val displayName: String,
    val description: String,
    val createdAt: Long = System.currentTimeMillis(),
    val createdInBatch: Int? = null,
    val mergedInto: String? = null,
    /** Cached translations keyed by BCP-47 language code, e.g. "zh" -> "聊天". */
    val localizedNames: Map<String, String> = emptyMap()
)

data class LabelMergeSuggestion(
    val mergeFrom: String,
    val mergeInto: String,
    val reason: String? = null,
    val confidence: Double? = null,
    val suggestedAt: Long = System.currentTimeMillis()
)
