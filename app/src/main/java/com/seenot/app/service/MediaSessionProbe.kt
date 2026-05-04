package com.seenot.app.service

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.provider.Settings
import com.seenot.app.data.model.MediaContentContext
import com.seenot.app.data.model.MediaContentStatus

object MediaSessionProbe {
    sealed class Result {
        data object PermissionMissing : Result()
        data class ActiveSessions(val sessions: List<SessionSnapshot>) : Result()
        data class Error(val message: String) : Result()
    }

    data class SessionSnapshot(
        val packageName: String,
        val playbackState: String?,
        val title: String?,
        val artist: String?,
        val album: String?,
        val durationMs: Long?
    )

    fun inspect(context: Context): Result {
        if (!hasNotificationListenerAccess(context)) {
            return Result.PermissionMissing
        }

        return try {
            val manager = context.getSystemService(MediaSessionManager::class.java)
                ?: return Result.Error("MediaSessionManager unavailable")
            val sessions = manager.getActiveSessions(notificationListenerComponentName(context))
                .map(::snapshot)
            Result.ActiveSessions(sessions)
        } catch (security: SecurityException) {
            Result.Error("SecurityException: ${security.message ?: "missing media session access"}")
        } catch (e: Exception) {
            Result.Error("${e::class.java.simpleName}: ${e.message ?: "unknown"}")
        }
    }

    fun currentAppMediaContext(context: Context, foregroundPackage: String?): MediaContentContext {
        return selectCurrentAppMediaContext(
            foregroundPackage = foregroundPackage,
            result = inspect(context)
        )
    }

    fun selectCurrentAppMediaContext(
        foregroundPackage: String?,
        result: Result
    ): MediaContentContext {
        val normalizedForeground = foregroundPackage?.takeIf { it.isNotBlank() }
        return when (result) {
            Result.PermissionMissing -> MediaContentContext(status = MediaContentStatus.PERMISSION_MISSING)
            is Result.Error -> MediaContentContext(status = MediaContentStatus.ERROR)
            is Result.ActiveSessions -> {
                val matching = result.sessions
                    .filter { session -> normalizedForeground != null && session.packageName == normalizedForeground }
                val selected = matching.maxWithOrNull(
                    compareBy<SessionSnapshot> { it.playbackState == "STATE_PLAYING" }
                        .thenBy { it.metadataCompletenessScore() }
                )

                if (selected == null) {
                    MediaContentContext(status = MediaContentStatus.NO_MATCHING_SESSION)
                } else {
                    MediaContentContext(
                        status = MediaContentStatus.MATCHED_CURRENT_APP,
                        packageName = selected.packageName,
                        playbackState = selected.playbackState,
                        title = selected.title,
                        artist = selected.artist,
                        album = selected.album,
                        durationMs = selected.durationMs
                    )
                }
            }
        }
    }

    fun formatSummary(foregroundPackage: String?, result: Result): String {
        val foreground = foregroundPackage?.takeIf { it.isNotBlank() } ?: "<unknown>"
        val detail = when (result) {
            Result.PermissionMissing -> "notification listener access is not enabled"
            is Result.Error -> "error=${result.message}"
            is Result.ActiveSessions -> {
                if (result.sessions.isEmpty()) {
                    "activeSessions=0"
                } else {
                    buildString {
                        append("activeSessions=${result.sessions.size}")
                        result.sessions.forEachIndexed { index, session ->
                            append(" | #${index + 1} ")
                            append("package=${session.packageName}")
                            append(", state=${session.playbackState.orNullLabel()}")
                            append(", title=${session.title.orNullLabel()}")
                            append(", artist=${session.artist.orNullLabel()}")
                            append(", album=${session.album.orNullLabel()}")
                            append(", durationMs=${session.durationMs?.toString() ?: "<null>"}")
                        }
                    }
                }
            }
        }
        return "MediaSession probe for foreground=$foreground: $detail"
    }

    fun signature(result: Result): String = when (result) {
        Result.PermissionMissing -> "permission_missing"
        is Result.Error -> "error:${result.message}"
        is Result.ActiveSessions -> result.sessions.joinToString(separator = "|") { session ->
            listOf(
                session.packageName,
                session.playbackState.orEmpty(),
                session.title.orEmpty(),
                session.artist.orEmpty(),
                session.album.orEmpty(),
                session.durationMs?.toString().orEmpty()
            ).joinToString(separator = "\u001F")
        }
    }

    private fun snapshot(controller: MediaController): SessionSnapshot {
        val metadata = controller.metadata
        return SessionSnapshot(
            packageName = controller.packageName,
            playbackState = controller.playbackState?.state?.let(::playbackStateName),
            title = metadata?.getText(MediaMetadata.METADATA_KEY_TITLE)?.toString(),
            artist = metadata?.getText(MediaMetadata.METADATA_KEY_ARTIST)?.toString(),
            album = metadata?.getText(MediaMetadata.METADATA_KEY_ALBUM)?.toString(),
            durationMs = metadata
                ?.getLong(MediaMetadata.METADATA_KEY_DURATION)
                ?.takeIf { it > 0L }
        )
    }

    private fun SessionSnapshot.metadataCompletenessScore(): Int {
        return listOf(title, artist, album).count { !it.isNullOrBlank() } +
            if (durationMs != null) 1 else 0
    }

    fun hasNotificationListenerAccess(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        val expected = notificationListenerComponentName(context).flattenToString()
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    fun notificationListenerComponentName(context: Context): ComponentName {
        return ComponentName(context, SeenotNotificationListenerService::class.java)
    }

    private fun playbackStateName(state: Int): String = when (state) {
        android.media.session.PlaybackState.STATE_NONE -> "STATE_NONE"
        android.media.session.PlaybackState.STATE_STOPPED -> "STATE_STOPPED"
        android.media.session.PlaybackState.STATE_PAUSED -> "STATE_PAUSED"
        android.media.session.PlaybackState.STATE_PLAYING -> "STATE_PLAYING"
        android.media.session.PlaybackState.STATE_FAST_FORWARDING -> "STATE_FAST_FORWARDING"
        android.media.session.PlaybackState.STATE_REWINDING -> "STATE_REWINDING"
        android.media.session.PlaybackState.STATE_BUFFERING -> "STATE_BUFFERING"
        android.media.session.PlaybackState.STATE_ERROR -> "STATE_ERROR"
        android.media.session.PlaybackState.STATE_CONNECTING -> "STATE_CONNECTING"
        android.media.session.PlaybackState.STATE_SKIPPING_TO_PREVIOUS -> "STATE_SKIPPING_TO_PREVIOUS"
        android.media.session.PlaybackState.STATE_SKIPPING_TO_NEXT -> "STATE_SKIPPING_TO_NEXT"
        android.media.session.PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> "STATE_SKIPPING_TO_QUEUE_ITEM"
        else -> "STATE_$state"
    }

    private fun String?.orNullLabel(): String = this?.takeIf { it.isNotBlank() } ?: "<null>"
}
