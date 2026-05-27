package com.seenot.app.account

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
}
