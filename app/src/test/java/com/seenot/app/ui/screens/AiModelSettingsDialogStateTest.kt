package com.seenot.app.ui.screens

import com.seenot.app.config.AiSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiModelSettingsDialogStateTest {
    @Test
    fun plusUserCanChooseSeenotAiBeforeAddingOwnApiKey() {
        assertTrue(
            shouldShowAiSourceSelector(
                hasPlus = true
            )
        )

        assertTrue(
            canSaveAiModelSettings(
                selectedSource = AiSource.SEENOT_AI,
                showAiSourceSelector = true,
                apiKey = "",
                baseUrl = "",
                model = "",
                feedbackModel = ""
            )
        )
    }

    @Test
    fun ownKeySelectionStillRequiresCompleteOwnKeySettings() {
        assertFalse(
            canSaveAiModelSettings(
                selectedSource = AiSource.BRING_YOUR_OWN_KEY,
                showAiSourceSelector = true,
                apiKey = "",
                baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
                model = "qwen-vl-plus",
                feedbackModel = "qwen-plus"
            )
        )
    }

    @Test
    fun signedOutUserDoesNotSeeAiSourceSelector() {
        assertFalse(
            shouldShowAiSourceSelector(
                hasPlus = false
            )
        )
    }
}
