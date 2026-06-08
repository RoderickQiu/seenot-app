package com.seenot.app.config

import android.content.Context
import android.content.SharedPreferences

object GitHubStarPromptPrefs {
    private const val PREFS_NAME = "seenot_github_star_prompt"
    private const val KEY_PENDING_HOME_PROMPT = "pending_home_prompt"
    private const val KEY_SHOWN = "shown"
    private const val KEY_DISMISSED_FOREVER = "dismissed_forever"

    private const val TRIGGER_CONTROLLED_APP_COUNT = 3

    private fun prefs(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun onControlledAppCountChanged(context: Context, previousCount: Int, currentCount: Int) {
        if (previousCount < TRIGGER_CONTROLLED_APP_COUNT && currentCount >= TRIGGER_CONTROLLED_APP_COUNT) {
            scheduleIfAllowed(context)
        }
    }

    fun onControlledAppCountSeen(context: Context, currentCount: Int) {
        if (currentCount >= TRIGGER_CONTROLLED_APP_COUNT) {
            scheduleIfAllowed(context)
        }
    }

    fun shouldShowOnHome(context: Context): Boolean {
        val prefs = prefs(context)
        return prefs.getBoolean(KEY_PENDING_HOME_PROMPT, false) &&
            !prefs.getBoolean(KEY_SHOWN, false) &&
            !prefs.getBoolean(KEY_DISMISSED_FOREVER, false)
    }

    fun markShown(context: Context) {
        prefs(context)
            .edit()
            .putBoolean(KEY_SHOWN, true)
            .putBoolean(KEY_PENDING_HOME_PROMPT, false)
            .apply()
    }

    fun dismissForever(context: Context) {
        prefs(context)
            .edit()
            .putBoolean(KEY_DISMISSED_FOREVER, true)
            .putBoolean(KEY_PENDING_HOME_PROMPT, false)
            .apply()
    }

    private fun scheduleIfAllowed(context: Context) {
        val prefs = prefs(context)
        if (!prefs.getBoolean(KEY_SHOWN, false) && !prefs.getBoolean(KEY_DISMISSED_FOREVER, false)) {
            prefs.edit().putBoolean(KEY_PENDING_HOME_PROMPT, true).apply()
        }
    }
}
