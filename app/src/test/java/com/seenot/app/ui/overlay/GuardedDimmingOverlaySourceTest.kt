package com.seenot.app.ui.overlay

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GuardedDimmingOverlaySourceTest {
    private val source = File(
        "src/main/java/com/seenot/app/ui/overlay/GuardedDimmingOverlay.kt"
    ).readText()

    @Test
    fun persistentDimLayerPassesInteractionThroughToTheControlledApp() {
        assertTrue(source.contains("WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE"))
        assertTrue(source.contains("WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE"))
        assertTrue(source.contains("WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN"))
    }

    @Test
    fun windowOpacityIsUpdatedAndCappedBelowObscuringLimits() {
        assertTrue(source.contains("dimFraction.coerceIn(0f, 0.4f)"))
        assertTrue(source.contains("params.alpha = fraction"))
        assertTrue(source.contains("windowManager?.updateViewLayout"))
    }
}
