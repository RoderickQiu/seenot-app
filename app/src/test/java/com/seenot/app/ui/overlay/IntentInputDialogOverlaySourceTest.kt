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
    fun dialogDoesNotOfferNoMonitorRestOrLegacyNoMonitorChoices() {
        assertFalse(source.contains("buildNoMonitorRestRow"))
        assertFalse(source.contains("showTimedRestChoices"))
        assertTrue(source.contains("filter { it.type != ConstraintType.NO_MONITOR }"))
        assertTrue(source.contains("historyEntry.none { it.type == ConstraintType.NO_MONITOR }"))
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

        assertTrue(handleMicClickBody.contains("mode = Mode.PROCESSING"))
        assertTrue(handleMicClickBody.contains("updateUI()"))
        assertTrue(handleMicClickBody.contains("voiceInputManager?.startRecording()"))
    }

    @Test
    fun guardedEntryIsCompactAndHasNoRedundantHelperCopy() {
        val guardedEntryBody = source.substringAfter("val guardedButton = TextView(context).apply")
            .substringBefore("cardContent.addView(guardedButton)")

        assertTrue(guardedEntryBody.contains("LinearLayout.LayoutParams.WRAP_CONTENT"))
        assertTrue(guardedEntryBody.contains("gravity = Gravity.START"))
        assertFalse(source.contains("R.string.guarded_entry_explanation"))
        assertFalse(source.contains("R.string.guarded_entry_detail"))
        assertFalse(source.contains("R.string.guarded_skip_action"))
        assertFalse(source.contains("R.string.intent_tap_to_start_recording"))
    }

    @Test
    fun guardedAndFocusChoicesEmitExplicitSessionModes() {
        assertTrue(source.contains("SessionMode.GUARDED"))
        assertTrue(source.contains("onIntentConfirmed(constraints, SessionMode.FOCUS)"))
    }

    @Test
    fun voiceAvailabilityUsesCentralConfigSoSeenotAiCanFetchFirstCredentialOnClick() {
        val hasUsableVoiceConfigBody = source.substringAfter("private fun hasUsableVoiceConfig(): Boolean")
            .substringBefore("private fun dismissInternal()")

        assertTrue(hasUsableVoiceConfigBody.contains("ApiConfig.isVoiceConfigured()"))
        assertFalse(hasUsableVoiceConfigBody.contains("settings.apiKey.isBlank()"))
        assertFalse(hasUsableVoiceConfigBody.contains("settings.baseUrl.isNotBlank()"))
    }
}
