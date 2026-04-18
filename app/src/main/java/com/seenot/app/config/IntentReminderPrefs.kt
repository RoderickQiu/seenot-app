package com.seenot.app.config

import android.content.Context
import android.content.SharedPreferences
import com.seenot.app.R

object IntentReminderPrefs {

    private const val PREFS_NAME = "seenot_intent_reminder"
    private const val KEY_ENABLED = "intent_reminder_enabled"
    private const val KEY_DELAY_MS = "intent_reminder_delay_ms"

    const val DELAY_30_SECONDS_MS = 30_000L
    const val DELAY_1_MINUTE_MS = 60_000L
    const val DELAY_2_MINUTES_MS = 120_000L
    const val DELAY_3_MINUTES_MS = 180_000L
    const val DELAY_5_MINUTES_MS = 300_000L
    const val DELAY_10_MINUTES_MS = 600_000L

    val supportedDelayOptionsMs = listOf(
        DELAY_30_SECONDS_MS,
        DELAY_1_MINUTE_MS,
        DELAY_2_MINUTES_MS,
        DELAY_3_MINUTES_MS,
        DELAY_5_MINUTES_MS,
        DELAY_10_MINUTES_MS
    )

    private const val DEFAULT_ENABLED = true
    private const val DEFAULT_DELAY_MS = DELAY_3_MINUTES_MS

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_ENABLED, DEFAULT_ENABLED)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun getDelayMs(context: Context): Long {
        val stored = getPrefs(context).getLong(KEY_DELAY_MS, DEFAULT_DELAY_MS)
        return stored.takeIf { it in supportedDelayOptionsMs } ?: DEFAULT_DELAY_MS
    }

    fun setDelayMs(context: Context, delayMs: Long) {
        val normalized = delayMs.takeIf { it in supportedDelayOptionsMs } ?: DEFAULT_DELAY_MS
        getPrefs(context).edit().putLong(KEY_DELAY_MS, normalized).apply()
    }

    fun formatDelayLabel(context: Context, delayMs: Long): String {
        return when (delayMs) {
            DELAY_30_SECONDS_MS -> context.getString(R.string.intent_reminder_delay_30_seconds)
            DELAY_1_MINUTE_MS -> context.getString(R.string.intent_reminder_delay_1_minute)
            else -> context.getString(
                R.string.intent_reminder_delay_minutes,
                (delayMs / 60_000L).toString()
            )
        }
    }
}
