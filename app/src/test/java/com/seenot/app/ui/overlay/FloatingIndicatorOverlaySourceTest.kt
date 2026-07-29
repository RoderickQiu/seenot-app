package com.seenot.app.ui.overlay

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FloatingIndicatorOverlaySourceTest {
    private val source = File(
        "src/main/java/com/seenot/app/ui/overlay/FloatingIndicatorOverlay.kt"
    ).readText()

    @Test
    fun guardedStatusDoesNotAlsoShowTheMissingIntentEmptyState() {
        assertTrue(source.contains("if (!isGuarded && statuses.isEmpty())"))
    }
}
