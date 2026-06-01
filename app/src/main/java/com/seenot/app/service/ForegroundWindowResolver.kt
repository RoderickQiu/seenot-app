package com.seenot.app.service

import android.view.accessibility.AccessibilityWindowInfo

object ForegroundWindowResolver {
    data class Candidate(
        val packageName: String,
        val windowId: Int,
        val isActive: Boolean,
        val isFocused: Boolean
    )

    fun resolveForegroundPackage(
        windows: List<AccessibilityWindowEventDebugFormatter.WindowSnapshot>,
        ownPackageName: String
    ): Candidate? {
        return windows
            .asSequence()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .filter { it.rootPackageName?.isNotBlank() == true }
            .filter { it.rootPackageName != ownPackageName }
            .filter { it.isActive || it.isFocused }
            .sortedWith(
                compareByDescending<AccessibilityWindowEventDebugFormatter.WindowSnapshot> { it.isActive && it.isFocused }
                    .thenByDescending { it.isFocused }
                    .thenByDescending { it.isActive }
                    .thenByDescending { it.layer }
            )
            .firstOrNull()
            ?.let { window ->
                Candidate(
                    packageName = window.rootPackageName!!,
                    windowId = window.id,
                    isActive = window.isActive,
                    isFocused = window.isFocused
                )
            }
    }
}
