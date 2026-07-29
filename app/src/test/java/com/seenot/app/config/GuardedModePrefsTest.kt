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
class GuardedModePrefsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun clearPrefs() {
        context.getSharedPreferences("guarded_mode", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun dimmingIsEnabledByDefaultForEveryGuardedSession() {
        assertTrue(GuardedModePrefs.isDimmingEnabled(context))
    }

    @Test
    fun oneExperienceSettingControlsDimmingGlobally() {
        GuardedModePrefs.setDimmingEnabled(context, false)

        assertFalse(GuardedModePrefs.isDimmingEnabled(context))
    }
}
