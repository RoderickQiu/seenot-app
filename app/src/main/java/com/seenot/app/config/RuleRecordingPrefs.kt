package com.seenot.app.config

import android.content.Context
import android.content.SharedPreferences

/**
 * Preferences for rule recording settings
 */
object RuleRecordingPrefs {

    private const val PREFS_NAME = "seenot_rule_recording"
    private const val KEY_ENABLE_RULE_RECORDING = "enable_rule_recording"
    private const val KEY_SCREENSHOT_MODE = "rule_record_screenshot_mode"

    /**
     * Screenshot save modes:
     * - "all": Save all screenshots
     * - "matched_only": Only save screenshots when constraint is matched/violated
     * - "none": Don't save screenshots
     */
    enum class ScreenshotMode(val value: String) {
        ALL("all"),
        MATCHED_ONLY("matched_only"),
        NONE("none");

        companion object {
            fun fromValue(value: String): ScreenshotMode {
                return entries.find { it.value == value } ?: ALL
            }
        }
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Whether rule recording is enabled
     */
    fun isEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_ENABLE_RULE_RECORDING, false)
    }

    /**
     * Set rule recording enabled state
     */
    fun setEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_ENABLE_RULE_RECORDING, enabled).apply()
    }

    /**
     * Get screenshot save mode
     */
    fun getScreenshotMode(context: Context): ScreenshotMode {
        val value = getPrefs(context).getString(KEY_SCREENSHOT_MODE, ScreenshotMode.ALL.value)
        return ScreenshotMode.fromValue(value ?: ScreenshotMode.ALL.value)
    }

    /**
     * Set screenshot save mode
     */
    fun setScreenshotMode(context: Context, mode: ScreenshotMode) {
        getPrefs(context).edit().putString(KEY_SCREENSHOT_MODE, mode.value).apply()
    }

    /**
     * Whether to save screenshot for a given match status
     */
    fun shouldSaveScreenshot(context: Context, isMatched: Boolean): Boolean {
        if (!isEnabled(context)) return false

        return when (getScreenshotMode(context)) {
            ScreenshotMode.ALL -> true
            ScreenshotMode.MATCHED_ONLY -> isMatched
            ScreenshotMode.NONE -> false
        }
    }
}
