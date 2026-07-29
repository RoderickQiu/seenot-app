package com.seenot.app.config

import android.content.Context

object GuardedModePrefs {
    private const val NAME = "guarded_mode"
    private const val DIMMING = "dimming_enabled"
    private fun dimmingKey(packageName: String) = "$DIMMING:$packageName"
    fun isDimmingEnabled(context: Context, packageName: String): Boolean =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getBoolean(dimmingKey(packageName), true)
    fun setDimmingEnabled(context: Context, packageName: String, enabled: Boolean) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(dimmingKey(packageName), enabled)
            .apply()
    }
}
