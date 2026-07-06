package com.seenot.app.ai.stt

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DashScopeReleaseKeepRulesTest {
    @Test
    fun releaseKeepsDashScopeRealtimeProtocolModelsForGson() {
        val rules = File("proguard-rules.pro").readText()

        assertTrue(rules.contains("-keep class com.alibaba.dashscope.protocol.**"))
        assertTrue(rules.contains("-keep class com.alibaba.dashscope.audio.asr.recognition.**"))
        assertTrue(rules.contains("@com.google.gson.annotations.SerializedName <fields>;"))
    }
}
