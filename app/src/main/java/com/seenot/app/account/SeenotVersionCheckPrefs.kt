package com.seenot.app.account

import android.content.Context
import com.google.gson.Gson

object SeenotVersionCheckPrefs {
    private const val PREFS_NAME = "seenot_version_check"
    private const val KEY_AUTOMATIC_CHECK_ENABLED = "automatic_check_enabled"
    private const val KEY_LAST_SUCCESSFUL_AUTOMATIC_CHECK_AT_MS = "last_successful_automatic_check_at_ms"
    private const val KEY_LAST_VERSION_CHECK_RESPONSE = "last_version_check_response"
    private const val KEY_LAST_PROMPTED_UPDATE_VERSION = "last_prompted_update_version"
    private const val AUTOMATIC_CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L
    private val gson = Gson()

    fun isAutomaticCheckEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_AUTOMATIC_CHECK_ENABLED, true)
    }

    fun setAutomaticCheckEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTOMATIC_CHECK_ENABLED, enabled).apply()
    }

    fun isAutomaticCheckDue(context: Context, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!isAutomaticCheckEnabled(context)) return false
        val lastCheckAt = prefs(context).getLong(KEY_LAST_SUCCESSFUL_AUTOMATIC_CHECK_AT_MS, 0L)
        return lastCheckAt <= 0L || nowMs - lastCheckAt >= AUTOMATIC_CHECK_INTERVAL_MS
    }

    fun saveLastSuccessfulAutomaticCheckAt(context: Context, checkedAtMs: Long = System.currentTimeMillis()) {
        prefs(context).edit().putLong(KEY_LAST_SUCCESSFUL_AUTOMATIC_CHECK_AT_MS, checkedAtMs).apply()
    }

    fun saveLastVersionCheckResponse(context: Context, response: SeenotVersionCheckResponse) {
        prefs(context).edit()
            .putString(KEY_LAST_VERSION_CHECK_RESPONSE, gson.toJson(response))
            .apply()
    }

    fun getLastVersionCheckResponse(context: Context): SeenotVersionCheckResponse? {
        val json = prefs(context).getString(KEY_LAST_VERSION_CHECK_RESPONSE, null) ?: return null
        return runCatching {
            gson.fromJson(json, SeenotVersionCheckResponse::class.java)
        }.getOrNull()
    }

    fun shouldPromptForUpdate(context: Context, response: SeenotVersionCheckResponse): Boolean {
        if (!response.updateAvailable) return false
        val lastPromptedVersion = prefs(context).getString(KEY_LAST_PROMPTED_UPDATE_VERSION, null)
        return lastPromptedVersion != response.latestVersion
    }

    fun markUpdatePrompted(context: Context, response: SeenotVersionCheckResponse) {
        if (!response.updateAvailable) return
        prefs(context).edit()
            .putString(KEY_LAST_PROMPTED_UPDATE_VERSION, response.latestVersion)
            .apply()
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
