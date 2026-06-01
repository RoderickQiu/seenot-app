package com.seenot.app.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HomeTimelineSectionSourceTest {
    @Test
    fun timelineDoesNotCollectAllRuleRecordRowsForOldestTimestamp() {
        val source = File("src/main/java/com/seenot/app/ui/screens/HomeTimelineSection.kt").readText()

        assertTrue(source.contains("getOldestRecordTimestampFlow()"))
        assertTrue(source.contains("getTimelineRecordsInRangeFlow("))
        assertFalse(source.contains("getAllRecordsFlow()"))
        assertFalse(source.contains("getRecordsInRangeFlow("))
        assertTrue(source.contains("collectAsState(initial ="))
        assertFalse(source.contains("collectAsStateWithLifecycle"))
    }
}
