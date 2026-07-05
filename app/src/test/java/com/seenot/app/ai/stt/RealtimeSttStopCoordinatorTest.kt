package com.seenot.app.ai.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RealtimeSttStopCoordinatorTest {
    @Test
    fun stopWaitsForFinalResultBeforeReportingEmptyRecognition() {
        val coordinator = RealtimeSttStopCoordinator()

        assertNull(coordinator.onStopRequested())
        assertEquals(
            RealtimeSttStopCoordinator.Decision.UseText("限制刷短视频十分钟"),
            coordinator.onFinalResult("限制刷短视频十分钟")
        )
    }

    @Test
    fun stopUsesLastIntermediateResultWhenCompletionArrivesWithoutFinalText() {
        val coordinator = RealtimeSttStopCoordinator()

        coordinator.onIntermediateResult("刷短视频")

        assertNull(coordinator.onStopRequested())
        assertEquals(
            RealtimeSttStopCoordinator.Decision.UseText("刷短视频"),
            coordinator.onComplete()
        )
    }

    @Test
    fun stopReportsEmptyOnlyAfterCompletionWithoutAnyRecognizedText() {
        val coordinator = RealtimeSttStopCoordinator()

        assertNull(coordinator.onStopRequested())

        assertEquals(
            RealtimeSttStopCoordinator.Decision.EmptyRecognition,
            coordinator.onComplete()
        )
    }
}
