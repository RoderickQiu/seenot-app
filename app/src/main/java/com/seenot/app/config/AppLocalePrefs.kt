package com.seenot.app.config

import android.app.LocaleManager
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
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

    private fun normalizeLanguageCode(languageCode: String?): String {
        return when (languageCode?.lowercase(Locale.ROOT)) {
            LANG_ZH, "zh-cn", "zh-hans", "zh-hant" -> LANG_ZH
            LANG_EN, "en-us", "en-gb" -> LANG_EN
            else -> LANG_EN
        }
    }

    private fun inferInitialLanguage(context: Context): String {
        val config = context.resources.configuration
        val systemLocale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.locales.get(0)
        } else {
            @Suppress("DEPRECATION")
            config.locale
        } ?: Locale.getDefault()
        return if (systemLocale.language.equals(LANG_ZH, ignoreCase = true)) LANG_ZH else LANG_EN
    }

    /**
     * Get stored language code. On first launch, infer from system language:
     * Chinese system -> zh, otherwise -> en.
     */
    fun getLanguage(context: Context): String {
        val prefs = getPrefs(context)
        val storedLanguage = prefs.getString(KEY_LANGUAGE, null)
        if (storedLanguage != null) {
            return normalizeLanguageCode(storedLanguage)
        }

        val inferredLanguage = inferInitialLanguage(context)
        prefs.edit().putString(KEY_LANGUAGE, inferredLanguage).apply()
        return inferredLanguage
    }

    /**
     * Set and apply the language immediately
     */
    fun setLanguage(context: Context, languageCode: String) {
        val normalizedLanguage = normalizeLanguageCode(languageCode)
        getPrefs(context).edit().putString(KEY_LANGUAGE, normalizedLanguage).apply()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ use LocaleManager
            val localeManager = context.getSystemService(LocaleManager::class.java)
            localeManager?.applicationLocales = LocaleList.forLanguageTags(normalizedLanguage)
        } else {
            // Android 12 fallback: set default locale
            Locale.setDefault(Locale.forLanguageTag(normalizedLanguage))
        }
    }

    /**
     * Apply the stored language preference (call on app start)
     */
    fun applyStoredLanguage(context: Context) {
        val language = getLanguage(context)
        val currentAppLanguage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.resources.configuration.locales.get(0)?.language
        } else {
            @Suppress("DEPRECATION")
            context.resources.configuration.locale?.language
        }

        if (language.isEmpty() || language == currentAppLanguage) {
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val localeManager = context.getSystemService(LocaleManager::class.java)
            localeManager?.applicationLocales = LocaleList.forLanguageTags(language)
        } else {
            Locale.setDefault(Locale.forLanguageTag(language))
        }
    }

    fun getAiOutputLanguageName(context: Context): String {
        return when (getLanguage(context)) {
            LANG_ZH -> "Simplified Chinese"
            else -> "English"
        }
    }

    fun createLocalizedContext(context: Context): Context {
        val language = getLanguage(context)
        val locale = Locale.forLanguageTag(language)
        val configuration = Configuration(context.resources.configuration)

        Locale.setDefault(locale)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            configuration.setLocale(locale)
            configuration.setLocales(android.os.LocaleList(locale))
        } else {
            @Suppress("DEPRECATION")
            configuration.locale = locale
        }

        return context.createConfigurationContext(configuration)
    }
}
