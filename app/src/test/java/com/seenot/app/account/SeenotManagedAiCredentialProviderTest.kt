package com.seenot.app.account

import androidx.test.core.app.ApplicationProvider
import com.seenot.app.config.AiProvider
import com.seenot.app.config.AiSource
import com.seenot.app.config.ApiConfig
import com.seenot.app.config.ApiSettings
import com.seenot.app.config.QwenRegion
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SeenotManagedAiCredentialProviderTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun setUp() {
        ApiConfig.init(context)
        ApiConfig.clearForTest()
        SeenotAccountSession.init(context)
        SeenotAccountSession.clear()
    }

    @Test
    fun refreshesExpiredManagedCredentialWithoutMainActivity() = runBlocking {
        SeenotAccountSession.save(authToken())
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
            apiKey = "expired-managed-key",
            baseUrl = "https://old.example/v1",
            model = "old-model",
            expiresAtEpochSeconds = 1_700_000_000L
        )

        val api = FakeManagedAiApi(context)
        val settings = SeenotManagedAiCredentialProvider(context, api)
            .getSettingsWithFreshManagedCredential()

        assertEquals(1, api.createManagedAiSessionCalls)
        assertEquals(AiSource.SEENOT_AI, ApiConfig.getAiSource())
        assertTrue(ApiConfig.isManagedAiActive(nowEpochMs = 1_800_000_000_000L))
        assertEquals("fresh-managed-key", settings.apiKey)
        assertEquals("fresh-model", settings.model)
    }

    @Test
    fun createManagedAiSessionThrowsQuotaExceptionForManagedAiQuotaCode() = runBlocking {
        SeenotAccountSession.save(authToken())

        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(429)
                    .setBody(
                        """
                        {
                          "detail": {
                            "code": "MANAGED_AI_TOKEN_QUOTA_EXCEEDED",
                            "message": "Managed AI token quota exceeded."
                          }
                        }
                        """.trimIndent()
                    )
            )
            val api = SeenotAccountApi(context, server.url("/api/v1").toString())

            val error = assertThrows(SeenotManagedAiQuotaExceededException::class.java) {
                runBlocking {
                    api.createManagedAiSession()
                }
            }

            assertEquals("Managed AI token quota exceeded.", error.message)
        }
    }

    private class FakeManagedAiApi(context: android.content.Context) : SeenotAccountApi(context) {
        var createManagedAiSessionCalls = 0

        override suspend fun createManagedAiSession(): SeenotManagedAiSessionResponse {
            createManagedAiSessionCalls++
            return SeenotManagedAiSessionResponse(
                provider = "dashscope",
                region = "beijing",
                baseUrl = "https://fresh.example/v1",
                model = "fresh-model",
                apiKey = "fresh-managed-key",
                expiresAt = 1_900_000_000L,
                expiresIn = 3600,
                fairUseState = "normal"
            )
        }
    }

    private fun authToken() = SeenotAuthTokenResponse(
        accessToken = "access",
        refreshToken = "refresh",
        expiresIn = 3600,
        user = SeenotUserResponse(
            userId = "user_1",
            email = "user@example.com",
            status = "active",
            displayName = null,
            locale = null,
            createdAt = "2026-05-21T00:00:00+00:00",
            updatedAt = "2026-05-21T00:00:00+00:00"
        ),
        device = SeenotDeviceResponse(
            deviceId = "dev_1",
            platform = "android",
            appVersion = "1.0.0",
            deviceName = "Pixel",
            lastSeenAt = "2026-05-21T00:00:00+00:00",
            revokedAt = null
        )
    )
}
