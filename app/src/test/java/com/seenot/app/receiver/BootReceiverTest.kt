package com.seenot.app.receiver

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.seenot.app.domain.SessionManager
import java.lang.reflect.Field
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BootReceiverTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setUp() {
        context.getSharedPreferences("seenot_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        sessionManagerInstanceField().set(null, null)
    }

    @Test
    fun bootCompletedDoesNotInitializeSessionManagerWhenAutoStartDisabled() {
        context.getSharedPreferences("seenot_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("auto_start", false)
            .commit()

        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertFalse(
            context.getSharedPreferences("seenot_prefs", Context.MODE_PRIVATE)
                .getBoolean("auto_start", true)
        )
        assertNull(sessionManagerInstanceField().get(null))
    }

    private fun sessionManagerInstanceField(): Field {
        return SessionManager::class.java.getDeclaredField("INSTANCE").apply {
            isAccessible = true
        }
    }
}
