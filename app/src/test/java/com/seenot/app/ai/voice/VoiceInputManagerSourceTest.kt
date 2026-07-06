package com.seenot.app.ai.voice

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VoiceInputManagerSourceTest {
    @Test
    fun realtimeDashScopeSttRefreshesManagedAiCredentialBeforeStarting() {
        val source = File("src/main/java/com/seenot/app/ai/voice/VoiceInputManager.kt").readText()
        val startRealtimeBody = source.substringAfter("private suspend fun startRealtimeDashScopeRecording()")
            .substringBefore("private fun scheduleRealtimeStopTimeout()")

        assertTrue(startRealtimeBody.contains("SeenotManagedAiCredentialProvider(context).getSettingsWithFreshManagedCredential()"))
        assertTrue(startRealtimeBody.contains("sttEngine?.setSessionApiKeyOverride"))
        assertTrue(startRealtimeBody.contains("sttEngine?.setSessionApiBaseUrlOverride"))
        assertTrue(startRealtimeBody.contains("AiSource.SEENOT_AI"))
    }

    @Test
    fun realtimeDashScopeSttLocalizesAccessDeniedSdkErrors() {
        val source = File("src/main/java/com/seenot/app/ai/voice/VoiceInputManager.kt").readText()
        val localizeBody = source.substringAfter("private fun localizeSttError(message: String): String")
            .substringBefore("/**\n     * Start voice recording")
        val onErrorBody = source.substringAfter("override fun onError(error: String)")
            .substringBefore("override fun onComplete()")

        assertTrue(localizeBody.contains("Model.AccessDenied"))
        assertTrue(localizeBody.contains("stt_dashscope_model_access_denied"))
        assertTrue(onErrorBody.contains("localizeSttError(error)"))
    }
}
