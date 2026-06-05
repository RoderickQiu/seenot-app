package com.seenot.app.ui.screens

import com.seenot.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SetupSecondaryHelpTest {
    @Test
    fun restrictedSettingsHelpKeepsItsOwnEntryLabelAndTarget() {
        assertEquals(
            R.string.restricted_settings_help_entry,
            SetupSecondaryHelp.RESTRICTED_SETTINGS_OVERLAY.labelRes
        )
        assertEquals(
            RestrictedSettingsHelpTarget.OVERLAY,
            SetupSecondaryHelp.RESTRICTED_SETTINGS_OVERLAY.restrictedSettingsTarget
        )
    }

    @Test
    fun backgroundLimitsHelpUsesBatterySpecificEntryAndNoRestrictedTarget() {
        assertEquals(
            R.string.background_limits_help_entry,
            SetupSecondaryHelp.BACKGROUND_LIMITS.labelRes
        )
        assertNull(SetupSecondaryHelp.BACKGROUND_LIMITS.restrictedSettingsTarget)
    }
}
