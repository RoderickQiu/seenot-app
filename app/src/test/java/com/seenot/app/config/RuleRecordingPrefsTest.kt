package com.seenot.app.config

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
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
    fun compactHudTextHiddenDefaultsToTrue() {
        assertTrue(RuleRecordingPrefs.isCompactHudTextHidden(context))
    }

    @Test
    fun compactHudTextHiddenStaysEnabledWhenLegacySetterWritesFalse() {
        RuleRecordingPrefs.setCompactHudTextHidden(context, false)

        assertTrue(RuleRecordingPrefs.isCompactHudTextHidden(context))
    }

    @Test
    fun screenshotModeDefaultsToMatchedOnly() {
        assertEquals(
            RuleRecordingPrefs.ScreenshotMode.MATCHED_ONLY,
            RuleRecordingPrefs.getScreenshotMode(context)
        )
    }

    @Test
    fun shouldOnlySaveMatchedScreenshotsByDefault() {
        RuleRecordingPrefs.setEnabled(context, true)

        assertTrue(RuleRecordingPrefs.shouldSaveScreenshot(context, isMatched = true))
        assertFalse(RuleRecordingPrefs.shouldSaveScreenshot(context, isMatched = false))
    }

    @Test
    fun defaultScreenshotModeOnlySavesWhenAnalysisHasViolation() {
        RuleRecordingPrefs.setEnabled(context, true)

        assertTrue(RuleRecordingPrefs.shouldSaveScreenshotForAnalysis(context, hasViolation = true))
        assertFalse(RuleRecordingPrefs.shouldSaveScreenshotForAnalysis(context, hasViolation = false))
    }

    @Test
    fun screenshotModeCanBeChanged() {
        RuleRecordingPrefs.setScreenshotMode(context, RuleRecordingPrefs.ScreenshotMode.ALL)

        assertEquals(
            RuleRecordingPrefs.ScreenshotMode.ALL,
            RuleRecordingPrefs.getScreenshotMode(context)
        )
    }
}
