package com.seenot.app.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RestrictedSettingsHelpSourceTest {
    @Test
    fun overlayAndAccessibilityShareOneRestrictedSettingsHelpSurface() {
        val source = File("src/main/java/com/seenot/app/ui/screens/MainScreen.kt").readText()

        assertTrue(source.contains("restrictedSettingsHelpTarget = RestrictedSettingsHelpTarget.OVERLAY"))
        assertTrue(source.contains("restrictedSettingsHelpTarget = RestrictedSettingsHelpTarget.ACCESSIBILITY"))
        assertTrue(source.contains("fun PermissionGuideType.restrictedSettingsHelpTarget()"))
        assertEquals(1, Regex("private fun RestrictedSettingsHelpSheet\\(").findAll(source).count())
        assertEquals(1, Regex("R\\.drawable\\.restricted_settings_denied").findAll(source).count())
        assertEquals(1, Regex("R\\.drawable\\.restricted_settings_permission").findAll(source).count())
        assertEquals(1, Regex("R\\.drawable\\.restricted_settings_app_info").findAll(source).count())
    }
}
