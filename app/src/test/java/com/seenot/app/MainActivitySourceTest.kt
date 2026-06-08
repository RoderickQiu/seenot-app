package com.seenot.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MainActivitySourceTest {
    @Test
    fun mainActivityPausesMonitoringFromItsOwnLifecycle() {
        val source = File("src/main/java/com/seenot/app/MainActivity.kt").readText()

        assertTrue(
            "MainActivity should pause active monitoring from onResume, not accessibility own-package events.",
            source.contains("override fun onResume()") &&
                source.contains("SeenotAccessibilityService.instance?.onMainActivityResumed()") &&
                source.contains("pauseActiveMonitoringForMainActivity()")
        )
    }
}
