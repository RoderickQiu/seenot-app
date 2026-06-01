package com.seenot.app.service

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo

object AccessibilityWindowEventDebugFormatter {
    data class WindowSnapshot(
        val id: Int,
        val type: Int,
        val layer: Int,
        val isActive: Boolean,
        val isFocused: Boolean,
        val rootPackageName: String?
    )

    fun formatWindowChanges(windowChanges: Int): String {
        if (windowChanges == 0) return "none"

        val knownChanges = listOf(
            AccessibilityEvent.WINDOWS_CHANGE_ADDED to "added",
            AccessibilityEvent.WINDOWS_CHANGE_REMOVED to "removed",
            AccessibilityEvent.WINDOWS_CHANGE_TITLE to "title",
            AccessibilityEvent.WINDOWS_CHANGE_BOUNDS to "bounds",
            AccessibilityEvent.WINDOWS_CHANGE_LAYER to "layer",
            AccessibilityEvent.WINDOWS_CHANGE_ACTIVE to "active",
            AccessibilityEvent.WINDOWS_CHANGE_FOCUSED to "focused",
            AccessibilityEvent.WINDOWS_CHANGE_ACCESSIBILITY_FOCUSED to "accessibility_focused",
            AccessibilityEvent.WINDOWS_CHANGE_PARENT to "parent",
            AccessibilityEvent.WINDOWS_CHANGE_CHILDREN to "children",
            AccessibilityEvent.WINDOWS_CHANGE_PIP to "pip"
        )

        val names = mutableListOf<String>()
        var knownMask = 0
        knownChanges.forEach { (bit, name) ->
            knownMask = knownMask or bit
            if (windowChanges and bit != 0) {
                names += name
            }
        }

        val unknown = windowChanges and knownMask.inv()
        if (unknown != 0) {
            names += "unknown(0x${unknown.toString(16)})"
        }

        return names.joinToString("|")
    }

    fun formatWindowSummary(windows: List<WindowSnapshot>): String {
        if (windows.isEmpty()) return "none"

        return windows
            .take(8)
            .mapIndexed { index, window ->
                "#${index + 1}{id=${window.id},type=${formatWindowType(window.type)}," +
                    "layer=${window.layer},active=${window.isActive},focused=${window.isFocused}," +
                    "pkg=${window.rootPackageName ?: "<unknown>"}}"
            }
            .joinToString("; ")
    }

    private fun formatWindowType(type: Int): String {
        return when (type) {
            AccessibilityWindowInfo.TYPE_APPLICATION -> "application"
            AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "input_method"
            AccessibilityWindowInfo.TYPE_SYSTEM -> "system"
            AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "accessibility_overlay"
            AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "split_screen_divider"
            else -> "unknown($type)"
        }
    }
}
