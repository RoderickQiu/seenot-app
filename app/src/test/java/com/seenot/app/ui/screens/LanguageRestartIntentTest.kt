package com.seenot.app.ui.screens

import android.app.Activity
import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LanguageRestartIntentTest {
    @Test
    fun languageRestartIntentDropsAuthCallbackData() {
        val authCallbackIntent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("seenot://auth/callback?request_id=req_1&code=used_code")
            setClassName("com.seenot.app", TestActivity::class.java.name)
        }
        val activity = Robolectric.buildActivity(TestActivity::class.java, authCallbackIntent)
            .create()
            .get()

        val restartIntent = buildLanguageRestartIntent(activity)

        assertEquals(TestActivity::class.java.name, restartIntent.component?.className)
        assertNull(restartIntent.data)
        assertNull(restartIntent.action)
    }

    class TestActivity : Activity()
}
