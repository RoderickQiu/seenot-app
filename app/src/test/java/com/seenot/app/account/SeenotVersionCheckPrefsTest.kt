package com.seenot.app.account

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SeenotVersionCheckPrefsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setUp() {
        context.getSharedPreferences("seenot_version_check", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun automaticChecksAreEnabledByDefault() {
        assertTrue(SeenotVersionCheckPrefs.isAutomaticCheckEnabled(context))
    }

    @Test
    fun dueOnlyWhenEnabledAndLastSuccessfulCheckIsOlderThanOneDay() {
        val now = 2_000_000_000L

        assertTrue(SeenotVersionCheckPrefs.isAutomaticCheckDue(context, now))

        SeenotVersionCheckPrefs.saveLastSuccessfulAutomaticCheckAt(context, now)
        assertFalse(SeenotVersionCheckPrefs.isAutomaticCheckDue(context, now + 23L * 60L * 60L * 1000L))
        assertTrue(SeenotVersionCheckPrefs.isAutomaticCheckDue(context, now + 24L * 60L * 60L * 1000L))

        SeenotVersionCheckPrefs.setAutomaticCheckEnabled(context, false)
        assertFalse(SeenotVersionCheckPrefs.isAutomaticCheckDue(context, now + 48L * 60L * 60L * 1000L))
    }

    @Test
    fun savesLastVersionCheckResponseForAboutSection() {
        val response = SeenotVersionCheckResponse(
            updateAvailable = true,
            latestVersion = "1.2.0",
            minimumSupportedVersion = "1.0.0",
            downloadUrl = "https://seenot.site/download/",
            publishedAt = "2026-06-01T00:00:00+00:00",
            message = "新版本已发布。"
        )

        assertNull(SeenotVersionCheckPrefs.getLastVersionCheckResponse(context))

        SeenotVersionCheckPrefs.saveLastVersionCheckResponse(context, response)
        val saved = SeenotVersionCheckPrefs.getLastVersionCheckResponse(context)

        assertEquals(response, saved)
    }

    @Test
    fun promptsEachUpdateVersionOnlyOnce() {
        val response = SeenotVersionCheckResponse(
            updateAvailable = true,
            latestVersion = "1.2.0",
            minimumSupportedVersion = null,
            downloadUrl = null,
            publishedAt = null,
            message = null
        )
        val sameVersion = response.copy(message = "更新说明可变。")
        val nextVersion = response.copy(latestVersion = "1.3.0")

        assertTrue(SeenotVersionCheckPrefs.shouldPromptForUpdate(context, response))

        SeenotVersionCheckPrefs.markUpdatePrompted(context, response)

        assertFalse(SeenotVersionCheckPrefs.shouldPromptForUpdate(context, sameVersion))
        assertTrue(SeenotVersionCheckPrefs.shouldPromptForUpdate(context, nextVersion))
        assertFalse(SeenotVersionCheckPrefs.shouldPromptForUpdate(context, response.copy(updateAvailable = false)))
    }
}
