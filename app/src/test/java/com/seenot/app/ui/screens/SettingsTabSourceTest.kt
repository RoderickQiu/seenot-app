package com.seenot.app.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SettingsTabSourceTest {
    private val settingsSource = File("src/main/java/com/seenot/app/ui/screens/SettingsTab.kt").readText()

    @Test
    fun guardedDimmingIsAGlobalExperienceSetting() {
        val experienceSection = settingsSource.substringAfter("R.string.experience_settings")
            .substringBefore("R.string.judgment_records")

        assertTrue(experienceSection.contains("R.string.guarded_dimming_title"))
        assertTrue(experienceSection.contains("GuardedModePrefs.setDimmingEnabled(context, it)"))
    }

    @Test
    fun guardedDimmingDoesNotBelongToPerAppControls() {
        val appsSource = File("src/main/java/com/seenot/app/ui/screens/AppsTab.kt").readText()
        val intentEditorSource = File("src/main/java/com/seenot/app/ui/screens/AppRulesDialog.kt").readText()

        assertFalse(appsSource.contains("GuardedModePrefs"))
        assertFalse(intentEditorSource.contains("GuardedModePrefs"))
    }
}
