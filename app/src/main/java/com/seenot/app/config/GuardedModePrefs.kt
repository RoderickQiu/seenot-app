package com.seenot.app.config

import android.content.Context

object GuardedModePrefs {
    private const val NAME = "guarded_mode"
    private const val DIMMING = "dimming_enabled"
    fun isDimmingEnabled(context: Context): Boolean =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getBoolean(DIMMING, true)
    fun setDimmingEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(DIMMING, enabled)
            .apply()
    }
}
