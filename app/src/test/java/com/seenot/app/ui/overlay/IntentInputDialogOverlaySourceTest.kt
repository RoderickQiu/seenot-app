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

    @Test
    fun parseErrorsAreRenderedInStatusInsteadOfVoiceConfigurationFallback() {
        assertTrue(source.contains("lastErrorMessage"))
        assertTrue(source.contains("statusText?.text = lastErrorMessage ?: when"))
        assertTrue(source.contains("lastErrorMessage = manager.error.value ?: context.getString(R.string.voice_err_parse_failed_simple)"))
        assertFalse(source.contains("ToastOverlay.show(context, manager.error.value ?: context.getString(R.string.voice_err_parse_failed_simple))\n                        mode = Mode.IDLE"))
    }

    @Test
    fun realtimeVoiceStartupUsesRecordingStartupState() {
        val handleMicClickBody = source.substringAfter("private fun handleMicClick()")
            .substringBefore("private fun restartVoiceInput()")

        assertTrue(handleMicClickBody.contains("mode = Mode.RECORDING"))
        assertTrue(handleMicClickBody.contains("updateUI()"))
        assertTrue(handleMicClickBody.contains("voiceInputManager?.startRecording()"))
    }
}
