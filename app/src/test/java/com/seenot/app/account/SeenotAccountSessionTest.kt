package com.seenot.app.account

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SeenotAccountSessionTest {
    @Test
    fun clearAccountKeepsStableInstallationId() {
        SeenotAccountSession.init(ApplicationProvider.getApplicationContext())
        SeenotAccountSession.clear()
        val installationId = SeenotAccountSession.getInstallationId()
        SeenotAccountSession.save(
            SeenotAuthTokenResponse(
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
        )

        SeenotAccountSession.clearAccount()

        assertFalse(SeenotAccountSession.hasSession())
        assertEquals("", SeenotAccountSession.getDeviceId())
        assertEquals(installationId, SeenotAccountSession.getInstallationId())
    }

    @Test
    fun savingLastSyncedAtRecordsLastSyncTimeAndClearRemovesIt() {
        SeenotAccountSession.init(ApplicationProvider.getApplicationContext())
        SeenotAccountSession.clear()

        SeenotAccountSession.saveLastSyncedAtNow()

        assertTrue(SeenotAccountSession.getLastSyncedAtMs() > 0L)

        SeenotAccountSession.clearAccount()

        assertEquals(0L, SeenotAccountSession.getLastSyncedAtMs())
    }
}
