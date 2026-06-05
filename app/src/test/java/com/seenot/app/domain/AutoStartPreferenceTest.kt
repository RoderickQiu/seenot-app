package com.seenot.app.domain

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AutoStartPreferenceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun clearPrefs() {
        context.getSharedPreferences("seenot_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun autoStartEnabledByDefault() {
        assertTrue(SessionManager(context).isAutoStartEnabled())
    }

    @Test
    fun autoStartCanBeDisabled() {
        val sessionManager = SessionManager(context)

        sessionManager.setAutoStartEnabled(false)

        assertFalse(sessionManager.isAutoStartEnabled())
    }
}
