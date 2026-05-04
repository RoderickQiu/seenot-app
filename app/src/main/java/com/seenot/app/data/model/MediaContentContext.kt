package com.seenot.app.data.model

data class MediaContentContext(
    val status: MediaContentStatus,
    val packageName: String? = null,
    val playbackState: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long? = null
) {
    fun hasUsableMetadata(): Boolean {
        return status == MediaContentStatus.MATCHED_CURRENT_APP &&
            listOf(title, artist, album).any { !it.isNullOrBlank() }
    }
}

enum class MediaContentStatus {
    MATCHED_CURRENT_APP,
    NO_MATCHING_SESSION,
    PERMISSION_MISSING,
    ERROR
}
