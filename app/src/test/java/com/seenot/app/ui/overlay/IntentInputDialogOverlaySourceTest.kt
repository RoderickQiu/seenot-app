package com.seenot.app.ui.overlay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class IntentInputDialogOverlaySourceTest {
    private val source = File(
        "src/main/java/com/seenot/app/ui/overlay/IntentInputDialogOverlay.kt"
    ).readText()

    @Test
    fun presetListDoesNotExposeInfiniteNoMonitorEntry() {
        val populatePresetsBody = source.substringAfter("private fun populatePresets()")
            .substringBefore("private fun buildNoMonitorRestRow()")

        assertFalse(populatePresetsBody.contains("buildNoMonitorRow"))
        assertFalse(source.contains("private fun buildNoMonitorRow()"))
    }

    @Test
    fun restEntryUsesNeutralPresetRowStyle() {
        val restRowBody = source.substringAfter("private fun buildNoMonitorRestRow()")
            .substringBefore("private fun showTimedRestChoices()")

        assertTrue(restRowBody.contains("setColor(historyBgColor)"))
        assertFalse(restRowBody.contains("setStroke(1, adjustAlpha(primaryColor"))
        assertFalse(restRowBody.contains("typeface = Typeface.DEFAULT_BOLD"))
    }

    @Test
    fun dialogShowsPendingSameAppSuggestionWithoutAutoApplyingIt() {
        assertTrue(source.contains("getPendingSuggestionForPackage(packageName)"))
        assertTrue(source.contains("buildSuggestionRow"))
        assertTrue(source.contains("sessionManager.previewSuggestedIntent"))
        assertTrue(source.contains("suggestion.intentText"))
        assertTrue(source.contains("confirmAndTransition(ApplyFeedback.SUGGESTION)"))
    }
}
