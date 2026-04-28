package com.seenot.app.config

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RuleRecordingPrefsTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun clearPrefs() {
        context.getSharedPreferences("seenot_rule_recording", android.content.Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun compactHudTextHiddenDefaultsToFalse() {
        assertFalse(RuleRecordingPrefs.isCompactHudTextHidden(context))
    }

    @Test
    fun compactHudTextHiddenCanBeEnabled() {
        RuleRecordingPrefs.setCompactHudTextHidden(context, true)

        assertTrue(RuleRecordingPrefs.isCompactHudTextHidden(context))
    }
}
