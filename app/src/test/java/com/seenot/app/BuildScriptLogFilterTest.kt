package com.seenot.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BuildScriptLogFilterTest {
    @Test
    fun releaseAndDebugLogFiltersIncludeVoiceAndSttTags() {
        val requiredTags = listOf(
            "VoiceInputManager",
            "SttEngine",
            "DashScopeSTT",
            "VoiceInputOverlay",
            "IntentInputDialog"
        )

        listOf("../build-and-release.sh", "../build-and-debug.sh").forEach { path ->
            val script = File(path).readText()
            requiredTags.forEach { tag ->
                assertTrue("$path should include $tag in logcat filter", script.contains(tag))
            }
        }
    }
}
