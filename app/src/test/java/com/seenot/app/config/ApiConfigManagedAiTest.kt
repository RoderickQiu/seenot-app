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
class ApiConfigManagedAiTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun setUp() {
        ApiConfig.init(context)
        ApiConfig.clearForTest()
    }

    @Test
    fun managedAiTemporarilyOverridesRuntimeSettingsWithoutDeletingOwnKeyConfig() {
        ApiConfig.saveSettings(
            ApiSettings(
                provider = AiProvider.OPENAI,
                apiKey = "own-openai-key",
                baseUrl = "https://api.openai.com/v1",
                model = "gpt-5.4-mini",
                feedbackModel = "gpt-5.5",
                qwenRegion = QwenRegion.BEIJING
            )
        )

        ApiConfig.saveManagedAiSession(
            apiKey = "st-managed-key",
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            model = "qwen-vl-plus",
            expiresAtEpochSeconds = 1_900_000_000L
        )

        assertEquals(AiSource.SEENOT_AI, ApiConfig.getAiSource())
        assertTrue(ApiConfig.isManagedAiActive(nowEpochMs = 1_800_000_000_000L))
        assertEquals("st-managed-key", ApiConfig.getSettings().apiKey)
        assertEquals("qwen-vl-plus", ApiConfig.getSettings().model)

        ApiConfig.preferBringYourOwnKey()

        assertEquals(AiSource.BRING_YOUR_OWN_KEY, ApiConfig.getAiSource())
        assertFalse(ApiConfig.isManagedAiActive(nowEpochMs = 1_800_000_000_000L))
        assertEquals(AiProvider.OPENAI, ApiConfig.getSettings().provider)
        assertEquals("own-openai-key", ApiConfig.getSettings().apiKey)
        assertEquals("gpt-5.4-mini", ApiConfig.getSettings().model)
    }

    @Test
    fun expiredManagedAiSessionFallsBackToOwnKeyConfig() {
        ApiConfig.saveSettings(
            ApiSettings.defaults(AiProvider.DASHSCOPE).copy(
                apiKey = "own-dashscope-key"
            )
        )
        ApiConfig.saveManagedAiSession(
            apiKey = "expired-managed-key",
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            model = "qwen-vl-plus",
            expiresAtEpochSeconds = 1_700_000_000L
        )

        assertFalse(ApiConfig.isManagedAiActive(nowEpochMs = 1_800_000_000_000L))
        assertEquals("own-dashscope-key", ApiConfig.getSettings().apiKey)
        assertEquals(AiSource.BRING_YOUR_OWN_KEY, ApiConfig.getAiSource())
    }

    @Test
    fun ownKeySettingsRemainReadableWhileManagedAiIsActive() {
        ApiConfig.saveSettings(
            ApiSettings(
                provider = AiProvider.OPENAI,
                apiKey = "own-openai-key",
                baseUrl = "https://api.openai.com/v1",
                model = "gpt-5.4-mini",
                feedbackModel = "gpt-5.5",
                qwenRegion = QwenRegion.BEIJING
            )
        )
        ApiConfig.saveManagedAiSession(
            apiKey = "st-managed-key",
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            model = "qwen-vl-plus",
            expiresAtEpochSeconds = 1_900_000_000L
        )

        val ownKeySettings = ApiConfig.getOwnKeySettings()

        assertEquals(AiProvider.OPENAI, ownKeySettings.provider)
        assertEquals("own-openai-key", ownKeySettings.apiKey)
        assertEquals("https://api.openai.com/v1", ownKeySettings.baseUrl)
        assertEquals("gpt-5.4-mini", ownKeySettings.model)
    }

    @Test
    fun providerSpecificOwnKeyValuesDoNotExposeManagedAiCredentials() {
        ApiConfig.saveSettings(
            ApiSettings.defaults(AiProvider.DASHSCOPE).copy(
                apiKey = "own-dashscope-key",
                baseUrl = "https://own-dashscope.example/v1"
            )
        )
        ApiConfig.saveManagedAiSession(
            apiKey = "st-managed-key",
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            model = "qwen-vl-plus",
            expiresAtEpochSeconds = 1_900_000_000L
        )

        assertEquals("own-dashscope-key", ApiConfig.getOwnApiKey(AiProvider.DASHSCOPE))
        assertEquals("https://own-dashscope.example/v1", ApiConfig.getOwnBaseUrl(AiProvider.DASHSCOPE))
    }
}
