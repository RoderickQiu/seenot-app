package com.seenot.app.service

import android.view.accessibility.AccessibilityEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class AccessibilityWindowEventDebugFormatterTest {
    @Test
    fun formatsKnownWindowChangeBits() {
        val changes =
            AccessibilityEvent.WINDOWS_CHANGE_ADDED or
                AccessibilityEvent.WINDOWS_CHANGE_ACTIVE or
                AccessibilityEvent.WINDOWS_CHANGE_FOCUSED

        assertEquals(
            "added|active|focused",
            AccessibilityWindowEventDebugFormatter.formatWindowChanges(changes)
        )
    }

    @Test
    fun includesUnknownWindowChangeBits() {
        val changes = AccessibilityEvent.WINDOWS_CHANGE_REMOVED or 0x40000000

        assertEquals(
            "removed|unknown(0x40000000)",
            AccessibilityWindowEventDebugFormatter.formatWindowChanges(changes)
        )
    }

    @Test
    fun formatsWindowSnapshotWithoutReadingNodeText() {
        val summary = AccessibilityWindowEventDebugFormatter.formatWindowSummary(
            listOf(
                AccessibilityWindowEventDebugFormatter.WindowSnapshot(
                    id = 12,
                    type = android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION,
                    layer = 3,
                    isActive = true,
                    isFocused = false,
                    rootPackageName = "com.tencent.mm"
                ),
                AccessibilityWindowEventDebugFormatter.WindowSnapshot(
                    id = 18,
                    type = android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD,
                    layer = 7,
                    isActive = false,
                    isFocused = true,
                    rootPackageName = "com.sohu.inputmethod.sogou"
                )
            )
        )

        assertEquals(
            "#1{id=12,type=application,layer=3,active=true,focused=false,pkg=com.tencent.mm}; " +
                "#2{id=18,type=input_method,layer=7,active=false,focused=true,pkg=com.sohu.inputmethod.sogou}",
            summary
        )
    }
}
