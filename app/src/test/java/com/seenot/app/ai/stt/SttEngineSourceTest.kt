package com.seenot.app.ai.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SttEngineSourceTest {
    @Test
    fun derivesDashScopeRealtimeEndpointFromManagedBaseUrl() {
        assertEquals(
            "wss://dashscope.aliyuncs.com/api-ws/v1/inference",
            SttEngine.dashScopeWebsocketUrlForBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1")
        )
        assertEquals(
            "wss://dashscope-intl.aliyuncs.com/api-ws/v1/inference",
            SttEngine.dashScopeWebsocketUrlForBaseUrl("https://dashscope-intl.aliyuncs.com/compatible-mode/v1/")
        )
        assertEquals(
            "wss://dashscope-us.aliyuncs.com/api-ws/v1/inference",
            SttEngine.dashScopeWebsocketUrlForBaseUrl("https://dashscope-us.aliyuncs.com/compatible-mode/v1")
        )
    }

    @Test
    fun realtimeDashScopeSttLogsModelEndpointAndCredentialSource() {
        val source = File("src/main/java/com/seenot/app/ai/stt/SttEngine.kt").readText()
        val startRecordingBody = source.substringAfter("fun startRecording(): Boolean")
            .substringBefore("private fun sendAudioData()")
        val endpointResolverBody = source.substringAfter("private fun resolveDashScopeWebsocketUrl(): String")
            .substringBefore("private fun cleanup()")

        assertTrue(source.contains("private const val REALTIME_STT_MODEL = \"fun-asr-realtime\""))
        assertTrue(startRecordingBody.contains("model=${'$'}REALTIME_STT_MODEL"))
        assertTrue(startRecordingBody.contains("endpoint=${'$'}websocketUrl"))
        assertTrue(startRecordingBody.contains("keySource=${'$'}{resolvedCredential.source}"))
        assertTrue(startRecordingBody.contains(".model(REALTIME_STT_MODEL)"))
        assertTrue(endpointResolverBody.contains("sessionApiBaseUrlOverride"))
        assertTrue(endpointResolverBody.contains("ApiConfig.getSttBaseUrl(AiProvider.DASHSCOPE)"))
    }
}
