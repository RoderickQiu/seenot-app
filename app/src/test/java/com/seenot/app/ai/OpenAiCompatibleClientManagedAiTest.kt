package com.seenot.app.ai

import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonObject
import com.seenot.app.account.SeenotAccountSession
import com.seenot.app.config.AiProvider
import com.seenot.app.config.ApiConfig
import com.seenot.app.config.ApiSettings
import com.seenot.app.config.QwenRegion
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OpenAiCompatibleClientManagedAiTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun setUp() {
        ApiConfig.init(context)
        ApiConfig.clearForTest()
        SeenotAccountSession.init(context)
        SeenotAccountSession.clear()
    }

    @Test
    fun refreshesManagedCredentialWhenProviderRejectsStillLocallyActiveKey() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"expired"}"""))
            server.enqueue(MockResponse().setResponseCode(200).setBody(chatCompletion("ok after refresh")))
            server.start()

            ApiConfig.saveSettings(ApiSettings.defaults(AiProvider.DASHSCOPE))
            ApiConfig.saveManagedAiSession(
                apiKey = "stale-managed-key",
                baseUrl = server.url("/v1").toString().trimEnd('/'),
                model = "qwen-vl-plus",
                expiresAtEpochSeconds = 1_900_000_000L
            )

            var callCount = 0
            val client = OpenAiCompatibleClient(
                settingsProvider = {
                    callCount++
                    if (callCount == 1) {
                        ApiConfig.getSettings()
                    } else {
                        ApiConfig.saveManagedAiSession(
                            apiKey = "fresh-managed-key",
                            baseUrl = server.url("/v1").toString().trimEnd('/'),
                            model = "qwen-vl-plus",
                            expiresAtEpochSeconds = 1_900_000_000L
                        )
                        ApiConfig.getSettings()
                    }
                }
            )

            assertEquals("ok after refresh", client.completeText(userPrompt = "hello"))

            assertEquals("Bearer stale-managed-key", server.takeRequest().getHeader("Authorization"))
            assertEquals("Bearer fresh-managed-key", server.takeRequest().getHeader("Authorization"))
            assertEquals("fresh-managed-key", ApiConfig.getSettings().apiKey)
        }
    }

    @Test
    fun refreshesManagedCredentialWhenDashScopeReturnsTaskFailedAccessDeniedBody() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(dashScopeTaskFailedAccessDenied()))
            server.enqueue(MockResponse().setResponseCode(200).setBody(chatCompletion("ok after refresh")))
            server.start()

            ApiConfig.saveSettings(ApiSettings.defaults(AiProvider.DASHSCOPE))
            ApiConfig.saveManagedAiSession(
                apiKey = "stale-managed-key",
                baseUrl = server.url("/v1").toString().trimEnd('/'),
                model = "qwen-vl-plus",
                expiresAtEpochSeconds = 1_900_000_000L
            )

            var callCount = 0
            val client = OpenAiCompatibleClient(
                settingsProvider = {
                    callCount++
                    if (callCount == 1) {
                        ApiConfig.getSettings()
                    } else {
                        ApiConfig.saveManagedAiSession(
                            apiKey = "fresh-managed-key",
                            baseUrl = server.url("/v1").toString().trimEnd('/'),
                            model = "qwen-vl-plus",
                            expiresAtEpochSeconds = 1_900_000_000L
                        )
                        ApiConfig.getSettings()
                    }
                }
            )

            assertEquals("ok after refresh", client.completeText(userPrompt = "hello"))

            assertEquals("Bearer stale-managed-key", server.takeRequest().getHeader("Authorization"))
            assertEquals("Bearer fresh-managed-key", server.takeRequest().getHeader("Authorization"))
        }
    }

    private fun chatCompletion(content: String): String {
        return JsonObject().apply {
            add("choices", com.google.gson.JsonArray().apply {
                add(JsonObject().apply {
                    add("message", JsonObject().apply {
                        addProperty("content", content)
                    })
                })
            })
        }.toString()
    }

    private fun dashScopeTaskFailedAccessDenied(): String {
        return JsonObject().apply {
            add("header", JsonObject().apply {
                addProperty("task_id", "task")
                addProperty("event", "task-failed")
                addProperty("error_code", "Model.AccessDenied")
                addProperty("error_message", "Model access denied.")
            })
            add("payload", JsonObject())
        }.toString()
    }
}
