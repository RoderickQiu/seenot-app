package com.seenot.app.config

import android.content.Context
import android.content.SharedPreferences

object InterventionDialogPrefs {

    private const val PREFS_NAME = "seenot_intervention_dialog"
    private const val KEY_NON_GENTLE_ALLOW_IGNORE_ONCE = "non_gentle_allow_ignore_once"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isNonGentleAllowIgnoreOnceEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_NON_GENTLE_ALLOW_IGNORE_ONCE, false)
    }

    fun setNonGentleAllowIgnoreOnceEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_NON_GENTLE_ALLOW_IGNORE_ONCE, enabled).apply()
    }
}
