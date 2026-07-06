package com.seenot.app.ai.stt

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DashScopeSdkSourceTest {
    @Test
    fun usesCurrentDashScopeSdkForRealtimeRecognitionProtocol() {
        val buildGradle = File("build.gradle.kts").readText()

        assertTrue(buildGradle.contains("com.alibaba:dashscope-sdk-java:2.22.24"))
    }
}
