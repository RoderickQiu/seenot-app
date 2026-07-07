package com.seenot.app.account

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class SeenotAccountApiRefreshSingleFlightTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun setUp() {
        SeenotAccountSession.init(context)
        SeenotAccountSession.clear()
    }

    @Test
    fun concurrentAuthenticatedRequestsShareSingleRefreshAfterExpiredAccessToken() = runBlocking {
        SeenotAccountSession.save(authToken(accessToken = "expired-access", refreshToken = "old-refresh"))
        val oldAccessFailures = CountDownLatch(2)
        val refreshRequests = AtomicInteger(0)

        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    return when {
                        request.path == "/api/v1/managed-ai/session" && request.getHeader("Authorization") == "Bearer expired-access" -> {
                            oldAccessFailures.countDown()
                            oldAccessFailures.await(3, TimeUnit.SECONDS)
                            authFailure()
                        }
                        request.path?.startsWith("/api/v1/sync/changes") == true &&
                            request.getHeader("Authorization") == "Bearer expired-access" -> {
                            oldAccessFailures.countDown()
                            oldAccessFailures.await(3, TimeUnit.SECONDS)
                            authFailure()
                        }
                        request.path == "/api/v1/auth/refresh" -> {
                            refreshRequests.incrementAndGet()
                            json(authTokenJson(accessToken = "fresh-access", refreshToken = "fresh-refresh"))
                        }
                        request.path == "/api/v1/managed-ai/session" && request.getHeader("Authorization") == "Bearer fresh-access" -> {
                            json(
                                """
                                {
                                  "provider": "dashscope",
                                  "region": "beijing",
                                  "base_url": "https://dashscope.example/v1",
                                  "model": "qwen-vl-plus",
                                  "api_key": "fresh-managed-key",
                                  "expires_at": 1900000000,
                                  "expires_in": 600,
                                  "fair_use_state": "normal"
                                }
                                """.trimIndent()
                            )
                        }
                        request.path?.startsWith("/api/v1/sync/changes") == true &&
                            request.getHeader("Authorization") == "Bearer fresh-access" -> {
                            json(
                                """
                                {
                                  "changes": [],
                                  "latest_sequence": 1496,
                                  "has_more": false,
                                  "device_cursor": 1496
                                }
                                """.trimIndent()
                            )
                        }
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            val api = SeenotAccountApi(context, server.url("/api/v1").toString())

            val managedAi = async(Dispatchers.IO) { api.createManagedAiSession() }
            val syncChanges = async(Dispatchers.IO) { api.readSyncChanges(since = 1496) }

            assertEquals("fresh-managed-key", managedAi.await().apiKey)
            assertEquals(1496L, syncChanges.await().latestSequence)
            assertEquals(1, refreshRequests.get())
            assertEquals("fresh-access", SeenotAccountSession.getAccessToken())
            assertEquals("fresh-refresh", SeenotAccountSession.getRefreshToken())
        }
    }

    @Test
    fun concurrentAuthenticatedRequestsAcrossApiInstancesShareSingleRefreshAfterExpiredAccessToken() = runBlocking {
        SeenotAccountSession.save(authToken(accessToken = "expired-access", refreshToken = "old-refresh"))
        val oldAccessFailures = CountDownLatch(2)
        val refreshRequests = AtomicInteger(0)

        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    return when {
                        request.path == "/api/v1/managed-ai/session" && request.getHeader("Authorization") == "Bearer expired-access" -> {
                            oldAccessFailures.countDown()
                            oldAccessFailures.await(3, TimeUnit.SECONDS)
                            authFailure()
                        }
                        request.path?.startsWith("/api/v1/sync/changes") == true &&
                            request.getHeader("Authorization") == "Bearer expired-access" -> {
                            oldAccessFailures.countDown()
                            oldAccessFailures.await(3, TimeUnit.SECONDS)
                            authFailure()
                        }
                        request.path == "/api/v1/auth/refresh" -> {
                            refreshRequests.incrementAndGet()
                            json(authTokenJson(accessToken = "fresh-access", refreshToken = "fresh-refresh"))
                        }
                        request.path == "/api/v1/managed-ai/session" && request.getHeader("Authorization") == "Bearer fresh-access" -> {
                            json(
                                """
                                {
                                  "provider": "dashscope",
                                  "region": "beijing",
                                  "base_url": "https://dashscope.example/v1",
                                  "model": "qwen-vl-plus",
                                  "api_key": "fresh-managed-key",
                                  "expires_at": 1900000000,
                                  "expires_in": 600,
                                  "fair_use_state": "normal"
                                }
                                """.trimIndent()
                            )
                        }
                        request.path?.startsWith("/api/v1/sync/changes") == true &&
                            request.getHeader("Authorization") == "Bearer fresh-access" -> {
                            json(
                                """
                                {
                                  "changes": [],
                                  "latest_sequence": 1496,
                                  "has_more": false,
                                  "device_cursor": 1496
                                }
                                """.trimIndent()
                            )
                        }
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            val baseUrl = server.url("/api/v1").toString()
            val managedAiApi = SeenotAccountApi(context, baseUrl)
            val syncApi = SeenotAccountApi(context, baseUrl)

            val managedAi = async(Dispatchers.IO) { managedAiApi.createManagedAiSession() }
            val syncChanges = async(Dispatchers.IO) { syncApi.readSyncChanges(since = 1496) }

            assertEquals("fresh-managed-key", managedAi.await().apiKey)
            assertEquals(1496L, syncChanges.await().latestSequence)
            assertEquals(1, refreshRequests.get())
            assertEquals("fresh-access", SeenotAccountSession.getAccessToken())
            assertEquals("fresh-refresh", SeenotAccountSession.getRefreshToken())
        }
    }

    @Test
    fun loadAccountKeepsLocalSessionWhenRefreshIsRejected() = runBlocking {
        SeenotAccountSession.save(authToken(accessToken = "expired-access", refreshToken = "old-refresh"))

        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(401)
                    .setBody(
                        """
                        {
                          "detail": {
                            "code": "REFRESH_TOKEN_REVOKED",
                            "message": "Refresh token has been revoked."
                          }
                        }
                        """.trimIndent()
                    )
            )
            val api = SeenotAccountApi(context, server.url("/api/v1").toString())

            val state = api.loadAccount()

            assertTrue(state is SeenotAccountState.Error)
            assertEquals("expired-access", SeenotAccountSession.getAccessToken())
            assertEquals("old-refresh", SeenotAccountSession.getRefreshToken())
        }
    }

    private fun authFailure(): MockResponse {
        return MockResponse()
            .setResponseCode(401)
            .setBody("""{"detail":"Invalid access token."}""")
    }

    private fun json(body: String): MockResponse {
        return MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody(body)
    }

    private fun authTokenJson(accessToken: String, refreshToken: String): String {
        return """
            {
              "access_token": "$accessToken",
              "refresh_token": "$refreshToken",
              "expires_in": 900,
              "user": {
                "user_id": "user_1",
                "email": "user@example.com",
                "status": "active",
                "display_name": null,
                "locale": null,
                "created_at": "2026-05-21T00:00:00+00:00",
                "updated_at": "2026-05-21T00:00:00+00:00"
              },
              "device": {
                "device_id": "dev_1",
                "platform": "android",
                "app_version": "1.0.0",
                "device_name": "Pixel",
                "last_seen_at": "2026-05-21T00:00:00+00:00",
                "revoked_at": null
              }
            }
        """.trimIndent()
    }

    private fun authToken(accessToken: String, refreshToken: String) = SeenotAuthTokenResponse(
        accessToken = accessToken,
        refreshToken = refreshToken,
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
