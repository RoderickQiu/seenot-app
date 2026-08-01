package com.seenot.app.ui.overlay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GuardedInterventionOverlaySourceTest {
    private val overlaySource = File(
        "src/main/java/com/seenot/app/ui/overlay/GuardedInterventionOverlay.kt"
    ).readText()
    private val sessionSource = File(
        "src/main/java/com/seenot/app/domain/SessionManager.kt"
    ).readText()
    private val serviceSource = File(
        "src/main/java/com/seenot/app/service/SeenotAccessibilityService.kt"
    ).readText()

    @Test
    fun holdCompletesWhileFingerRemainsDownAndShowsProgress() {
        assertTrue(overlaySource.contains("SystemClock.elapsedRealtime() - startedAt"))
        assertTrue(overlaySource.contains("handler.postDelayed(updateProgress, PROGRESS_FRAME_MS)"))
        assertTrue(overlaySource.contains("if (elapsed >= holdMs)"))
        assertTrue(overlaySource.contains("onComplete()"))
        assertTrue(overlaySource.contains("R.string.guarded_hold_progress"))
        assertFalse(overlaySource.contains("ACTION_UP &&"))
    }

    @Test
    fun overlayUsesLocalizedThemeAwareCardAndProvidesAnExit() {
        assertTrue(overlaySource.contains("AppLocalePrefs.createLocalizedContext"))
        assertTrue(overlaySource.contains("UI_MODE_NIGHT_MASK"))
        assertTrue(overlaySource.contains("R.string.guarded_leave_app"))
        assertTrue(overlaySource.contains("onLeave()"))
        assertTrue(overlaySource.contains("20.dp(context)"))
    }

    @Test
    fun elapsedUsageReadsTheLiveSessionClockWhileHoldIsVisible() {
        assertTrue(overlaySource.contains("elapsedMsProvider()"))
        assertTrue(overlaySource.contains("handler.postDelayed(refreshElapsed, ELAPSED_REFRESH_MS)"))
        assertTrue(overlaySource.contains("R.string.guarded_hold_title_seconds"))
        assertTrue(overlaySource.contains("R.string.guarded_hold_title_minutes"))
        assertFalse(overlaySource.contains("elapsedWhileShown"))
    }

    @Test
    fun firstBreathRequiresAnExplicitHoldAndCannotAutoDismiss() {
        val breathBody = overlaySource.substringAfter("fun showBreath(")
            .substringBefore("fun showHold(")

        assertTrue(breathBody.contains("showRequiredHold("))
        assertTrue(breathBody.contains("holdMs: Long = 3_000L"))
        assertFalse(breathBody.contains("postDelayed"))
        assertFalse(breathBody.contains("durationMs"))
    }

    @Test
    fun sessionUsesOneMonotonicClockAndCountsForegroundReprieveUsage() {
        val timerBody = sessionSource.substringAfter("private fun startTimer()")
            .substringBefore("private fun updateGuardedDimming")
        val accountingBody = timerBody.substringAfter("val timing =")
            .substringBefore("val newConsumed")

        assertTrue(timerBody.contains("val now = SystemClock.elapsedRealtime()"))
        assertTrue(accountingBody.contains("GuardedSessionTiming.advance("))
        assertFalse(accountingBody.contains("inReprieve"))
        assertFalse(timerBody.contains("System.currentTimeMillis() + 8 * 60_000L"))
        assertTrue(timerBody.contains("current?.sessionId != session.sessionId"))
    }

    @Test
    fun guardedOverlayIsDismissedAcrossEverySessionAndServiceExitPath() {
        val pauseBody = sessionSource.substringAfter("fun pauseSession()")
            .substringBefore("fun pauseActiveMonitoringForMainActivity()")
        val endBody = sessionSource.substringAfter("private suspend fun endSession(reason: SessionEndReason)")
            .substringBefore("fun getSessionElapsedTime")
        val unbindBody = serviceSource.substringAfter("override fun onUnbind(intent: Intent?)")
            .substringBefore("// ==================== App Detection")

        assertTrue(pauseBody.contains("GuardedInterventionOverlay.dismiss(context)"))
        assertTrue(endBody.contains("GuardedInterventionOverlay.dismiss(context)"))
        assertTrue(unbindBody.contains("dismissAllOverlays()"))
        assertTrue(sessionSource.contains("onLeave = ::leaveGuardedSession"))
    }
}
