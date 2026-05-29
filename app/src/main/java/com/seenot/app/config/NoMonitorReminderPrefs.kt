package com.seenot.app.config

import android.content.Context
import android.content.SharedPreferences

object NoMonitorReminderPrefs {

    private const val PREFS_NAME = "seenot_no_monitor_reminder"
    private const val KEY_ENABLED = "no_monitor_reminder_enabled"

    private const val DEFAULT_ENABLED = true

    const val REMINDER_INTERVAL_MS = 20 * 60 * 1000L

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_ENABLED, DEFAULT_ENABLED)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }
}
