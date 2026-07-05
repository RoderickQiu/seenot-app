package com.seenot.app.domain

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SessionImprovementLoggingSourceTest {
    @Test
    fun sessionImprovementPipelineLogsEveryInvisibleDecisionPoint() {
        val source = File("src/main/java/com/seenot/app/domain/SessionManager.kt").readText()
        val body = source.substringAfter("private fun scheduleSessionImprovementSuggestion")
            .substringBefore("fun previewSuggestedIntent")

        assertTrue(body.contains("Session improvement: start"))
        assertTrue(body.contains("Session improvement: loaded records"))
        assertTrue(body.contains("Session improvement: no candidate"))
        assertTrue(body.contains("Session improvement: candidate"))
        assertTrue(body.contains("Session improvement: generator returned empty"))
        assertTrue(body.contains("Session improvement: saved suggestion"))
        assertTrue(body.contains("Session improvement: suggestion already exists"))
    }
}
