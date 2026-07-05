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
        assertTrue(startRealtimeBody.contains("AiSource.SEENOT_AI"))
    }
}
