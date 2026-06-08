package com.seenot.app.config

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GitHubStarPromptPrefsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun clearPrefs() {
        context.getSharedPreferences("seenot_github_star_prompt", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun existingThreeOrMoreControlledAppsSchedulesPrompt() {
        GitHubStarPromptPrefs.onControlledAppCountSeen(context, currentCount = 5)

        assertTrue(GitHubStarPromptPrefs.shouldShowOnHome(context))
    }

    @Test
    fun enteringThreeOrMoreControlledAppsSchedulesPromptOnce() {
        assertFalse(GitHubStarPromptPrefs.shouldShowOnHome(context))

        GitHubStarPromptPrefs.onControlledAppCountChanged(context, previousCount = 1, currentCount = 4)

        assertTrue(GitHubStarPromptPrefs.shouldShowOnHome(context))

        GitHubStarPromptPrefs.markShown(context)

        assertFalse(GitHubStarPromptPrefs.shouldShowOnHome(context))
    }

    @Test
    fun stayingAtThreeOrMoreControlledAppsDoesNotSchedulePrompt() {
        GitHubStarPromptPrefs.onControlledAppCountChanged(context, previousCount = 4, currentCount = 5)

        assertFalse(GitHubStarPromptPrefs.shouldShowOnHome(context))
    }

    @Test
    fun shownPromptDoesNotRescheduleAfterDeletingAndAddingApp() {
        GitHubStarPromptPrefs.onControlledAppCountChanged(context, previousCount = 2, currentCount = 3)
        GitHubStarPromptPrefs.markShown(context)

        GitHubStarPromptPrefs.onControlledAppCountChanged(context, previousCount = 2, currentCount = 3)

        assertFalse(GitHubStarPromptPrefs.shouldShowOnHome(context))
    }

    @Test
    fun dismissForeverPreventsSchedulingAndShowing() {
        GitHubStarPromptPrefs.dismissForever(context)

        GitHubStarPromptPrefs.onControlledAppCountChanged(context, previousCount = 1, currentCount = 4)

        assertFalse(GitHubStarPromptPrefs.shouldShowOnHome(context))
    }
}
