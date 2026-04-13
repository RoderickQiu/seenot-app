package com.seenot.app.config

import android.app.LocaleManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * Manages app language/locale preferences
 */
object AppLocalePrefs {

    private const val PREFS_NAME = "seenot_locale"
    private const val KEY_LANGUAGE = "language"

    // Language codes
    const val LANG_ZH = "zh"
    const val LANG_EN = "en"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Get stored language code, defaults to zh if not set
     */
    fun getLanguage(context: Context): String {
        return getPrefs(context).getString(KEY_LANGUAGE, LANG_ZH) ?: LANG_ZH
    }

    /**
     * Set and apply the language immediately
     */
    fun setLanguage(context: Context, languageCode: String) {
        getPrefs(context).edit().putString(KEY_LANGUAGE, languageCode).apply()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ use LocaleManager
            val localeManager = context.getSystemService(LocaleManager::class.java)
            localeManager?.applicationLocales = LocaleList.forLanguageTags(languageCode)
        } else {
            // Android 12 fallback: set default locale
            Locale.setDefault(Locale.forLanguageTag(languageCode))
        }
    }

    /**
     * Apply the stored language preference (call on app start)
     */
    fun applyStoredLanguage(context: Context) {
        val language = getLanguage(context)
        if (language.isEmpty() || language == Locale.getDefault().language) {
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val localeManager = context.getSystemService(LocaleManager::class.java)
            localeManager?.applicationLocales = LocaleList.forLanguageTags(language)
        } else {
            Locale.setDefault(Locale.forLanguageTag(language))
        }
    }
}
