package com.seenot.app.account

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SeenotVersionCheckApiTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setUp() {
        SeenotAccountSession.init(context)
        SeenotAccountSession.clear()
    }

    @Test
    fun postsVersionCheckWithInstallationIdAndParsesResponse() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        """
                        {
                          "update_available": true,
                          "latest_version": "0.2.0",
                          "minimum_supported_version": "0.1.0",
                          "download_url": "https://seenot.site/download",
                          "published_at": "2026-05-27T00:00:00+00:00",
                          "message": "New build"
                        }
                        """.trimIndent()
                    )
            )
            server.start()

            val api = SeenotAccountApi(context, server.url("/api/v1").toString())
            val response = api.checkVersion()

            assertTrue(response.updateAvailable)
            assertEquals("0.2.0", response.latestVersion)
            assertEquals("0.1.0", response.minimumSupportedVersion)
            assertEquals("https://seenot.site/download", response.downloadUrl)
            assertEquals("2026-05-27T00:00:00+00:00", response.publishedAt)
            assertEquals("New build", response.message)

            val request = server.takeRequest()
            assertEquals("/api/v1/app/version-check", request.path)
            val body = request.body.readUtf8()
            assertTrue(body.contains("\"platform\":\"android\""))
            assertTrue(body.contains("\"app_version\":\""))
            assertTrue(body.contains("\"locale\":\""))
            assertTrue(body.contains("\"installation_id\":\"${SeenotAccountSession.getInstallationId()}\""))
        }
    }
}
