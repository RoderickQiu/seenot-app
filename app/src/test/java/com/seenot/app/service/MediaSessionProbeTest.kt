package com.seenot.app.service

import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.seenot.app.data.model.MediaContentStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MediaSessionProbeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun activeSessionsSummaryIncludesSessionMetadata() {
        val summary = MediaSessionProbe.formatSummary(
            foregroundPackage = "tv.danmaku.bili",
            result = MediaSessionProbe.Result.ActiveSessions(
                sessions = listOf(
                    MediaSessionProbe.SessionSnapshot(
                        packageName = "tv.danmaku.bili",
                        playbackState = "STATE_PLAYING",
                        title = "Video title",
                        artist = "Uploader",
                        album = null,
                        durationMs = 123_000L
                    )
                )
            )
        )

        assertEquals(
            "MediaSession probe for foreground=tv.danmaku.bili: " +
                "activeSessions=1 | #1 package=tv.danmaku.bili, state=STATE_PLAYING, " +
                "title=Video title, artist=Uploader, album=<null>, durationMs=123000",
            summary
        )
    }

    @Test
    fun permissionMissingSummaryIsExplicit() {
        val summary = MediaSessionProbe.formatSummary(
            foregroundPackage = "tv.danmaku.bili",
            result = MediaSessionProbe.Result.PermissionMissing
        )

        assertEquals(
            "MediaSession probe for foreground=tv.danmaku.bili: " +
                "notification listener access is not enabled",
            summary
        )
    }

    @Test
    fun signatureChangesWhenMetadataChanges() {
        val first = MediaSessionProbe.Result.ActiveSessions(
            sessions = listOf(
                MediaSessionProbe.SessionSnapshot(
                    packageName = "tv.danmaku.bili",
                    playbackState = "STATE_PLAYING",
                    title = "First video",
                    artist = "Uploader",
                    album = null,
                    durationMs = 100_000L
                )
            )
        )
        val second = MediaSessionProbe.Result.ActiveSessions(
            sessions = listOf(
                MediaSessionProbe.SessionSnapshot(
                    packageName = "tv.danmaku.bili",
                    playbackState = "STATE_PLAYING",
                    title = "Second video",
                    artist = "Uploader",
                    album = null,
                    durationMs = 100_000L
                )
            )
        )

        assertEquals(false, MediaSessionProbe.signature(first) == MediaSessionProbe.signature(second))
    }

    @Test
    fun currentAppMediaContextIgnoresOtherPackages() {
        val context = MediaSessionProbe.selectCurrentAppMediaContext(
            foregroundPackage = "tv.danmaku.bili",
            result = MediaSessionProbe.Result.ActiveSessions(
                sessions = listOf(
                    MediaSessionProbe.SessionSnapshot(
                        packageName = "com.netease.cloudmusic",
                        playbackState = "STATE_PLAYING",
                        title = "Background song",
                        artist = "Singer",
                        album = "Album",
                        durationMs = 180_000L
                    ),
                    MediaSessionProbe.SessionSnapshot(
                        packageName = "tv.danmaku.bili",
                        playbackState = "STATE_PLAYING",
                        title = "Bili video",
                        artist = "Creator",
                        album = null,
                        durationMs = 300_000L
                    )
                )
            )
        )

        assertEquals(MediaContentStatus.MATCHED_CURRENT_APP, context.status)
        assertEquals("tv.danmaku.bili", context.packageName)
        assertEquals("Bili video", context.title)
        assertEquals("Creator", context.artist)
    }

    @Test
    fun currentAppMediaContextPrefersPlayingSessionForSamePackage() {
        val context = MediaSessionProbe.selectCurrentAppMediaContext(
            foregroundPackage = "com.google.android.youtube",
            result = MediaSessionProbe.Result.ActiveSessions(
                sessions = listOf(
                    MediaSessionProbe.SessionSnapshot(
                        packageName = "com.google.android.youtube",
                        playbackState = "STATE_PAUSED",
                        title = "Old video",
                        artist = "Old channel",
                        album = null,
                        durationMs = 200_000L
                    ),
                    MediaSessionProbe.SessionSnapshot(
                        packageName = "com.google.android.youtube",
                        playbackState = "STATE_PLAYING",
                        title = "Current video",
                        artist = "Current channel",
                        album = null,
                        durationMs = 400_000L
                    )
                )
            )
        )

        assertEquals(MediaContentStatus.MATCHED_CURRENT_APP, context.status)
        assertEquals("Current video", context.title)
        assertEquals("STATE_PLAYING", context.playbackState)
    }

    @Test
    fun currentAppMediaContextReportsNoMatchingSession() {
        val context = MediaSessionProbe.selectCurrentAppMediaContext(
            foregroundPackage = "com.instagram.android",
            result = MediaSessionProbe.Result.ActiveSessions(
                sessions = listOf(
                    MediaSessionProbe.SessionSnapshot(
                        packageName = "com.netease.cloudmusic",
                        playbackState = "STATE_PLAYING",
                        title = "Background song",
                        artist = "Singer",
                        album = "Album",
                        durationMs = 180_000L
                    )
                )
            )
        )

        assertEquals(MediaContentStatus.NO_MATCHING_SESSION, context.status)
    }

    @Test
    fun notificationListenerAccessReflectsSecureSetting() {
        Settings.Secure.putString(context.contentResolver, "enabled_notification_listeners", "")

        assertFalse(MediaSessionProbe.hasNotificationListenerAccess(context))

        Settings.Secure.putString(
            context.contentResolver,
            "enabled_notification_listeners",
            MediaSessionProbe.notificationListenerComponentName(context).flattenToString()
        )

        assertTrue(MediaSessionProbe.hasNotificationListenerAccess(context))
    }
}
