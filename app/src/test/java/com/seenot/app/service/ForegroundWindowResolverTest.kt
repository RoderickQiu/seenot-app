package com.seenot.app.service

import android.view.accessibility.AccessibilityWindowInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForegroundWindowResolverTest {
    @Test
    fun resolvesFocusedApplicationWindowAsForegroundCandidate() {
        val candidate = ForegroundWindowResolver.resolveForegroundPackage(
            windows = listOf(
                AccessibilityWindowEventDebugFormatter.WindowSnapshot(
                    id = 1,
                    type = AccessibilityWindowInfo.TYPE_SYSTEM,
                    layer = 3,
                    isActive = false,
                    isFocused = false,
                    rootPackageName = "com.android.systemui"
                ),
                AccessibilityWindowEventDebugFormatter.WindowSnapshot(
                    id = 2,
                    type = AccessibilityWindowInfo.TYPE_APPLICATION,
                    layer = 0,
                    isActive = true,
                    isFocused = true,
                    rootPackageName = "com.tencent.mm"
                )
            ),
            ownPackageName = "com.seenot.app"
        )

        assertEquals("com.tencent.mm", candidate?.packageName)
    }

    @Test
    fun ignoresOwnApplicationWindowAsForegroundCandidate() {
        val candidate = ForegroundWindowResolver.resolveForegroundPackage(
            windows = listOf(
                AccessibilityWindowEventDebugFormatter.WindowSnapshot(
                    id = 1,
                    type = AccessibilityWindowInfo.TYPE_APPLICATION,
                    layer = 1,
                    isActive = true,
                    isFocused = true,
                    rootPackageName = "com.seenot.app"
                )
            ),
            ownPackageName = "com.seenot.app"
        )

        assertNull(candidate)
    }
}
