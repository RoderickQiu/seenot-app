package com.seenot.app.config

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NoMonitorReminderPrefsTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun clearPrefs() {
        context.getSharedPreferences("seenot_no_monitor_reminder", android.content.Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun enabledByDefault() {
        assertTrue(NoMonitorReminderPrefs.isEnabled(context))
    }

    @Test
    fun canBeDisabled() {
        NoMonitorReminderPrefs.setEnabled(context, false)

        assertFalse(NoMonitorReminderPrefs.isEnabled(context))
    }
}
