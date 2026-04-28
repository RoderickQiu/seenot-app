package com.seenot.app.config

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class InterventionDialogPrefsTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun clearPrefs() {
        context.getSharedPreferences("seenot_intervention_dialog", android.content.Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun nonGentleIgnoreOnceDisabledByDefault() {
        assertFalse(InterventionDialogPrefs.isNonGentleAllowIgnoreOnceEnabled(context))
    }

    @Test
    fun nonGentleIgnoreOnceCanBeEnabled() {
        InterventionDialogPrefs.setNonGentleAllowIgnoreOnceEnabled(context, true)

        assertTrue(InterventionDialogPrefs.isNonGentleAllowIgnoreOnceEnabled(context))
    }
}
