package com.roderickqiu.seenot.utils

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.activity.ComponentActivity
import java.util.Locale

object LanguageManager {
    private const val PREFS_NAME = "seenot_prefs"
    private const val KEY_LANGUAGE = "language"
    private const val DEFAULT_LANGUAGE = "auto" // "auto", "zh", "en"

    fun getSavedLanguage(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LANGUAGE, DEFAULT_LANGUAGE) ?: DEFAULT_LANGUAGE
    }

    fun saveLanguage(context: Context, language: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LANGUAGE, language).apply()
    }

    /**
     * Get system locale (not app locale) for "auto" mode
     */
    private fun getSystemLocale(): Locale {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            android.content.res.Resources.getSystem().configuration.locales[0]
        } else {
            @Suppress("DEPRECATION")
            android.content.res.Resources.getSystem().configuration.locale
        }
    }

    fun getLocale(language: String): Locale {
        return when (language) {
            "zh" -> Locale.Builder().setLanguage("zh").setRegion("CN").build()
            "en" -> Locale.Builder().setLanguage("en").setRegion("US").build()
            else -> getSystemLocale() // "auto" uses system default
        }
    }

    /**
     * Get the effective language code for the current context.
     * Returns "zh" or "en" based on saved setting, resolving "auto" to system language.
     */
    fun getEffectiveLanguage(context: Context): String {
        val saved = getSavedLanguage(context)
        return when (saved) {
            "zh" -> "zh"
            "en" -> "en"
            else -> getSystemLocale().language // "auto" - use system language
        }
    }

    fun updateConfiguration(context: Context) {
        val language = getSavedLanguage(context)
        val locale = getLocale(language)
        
        val config = Configuration(context.resources.configuration)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocale(locale)
            val localeList = android.os.LocaleList(locale)
            config.setLocales(localeList)
        } else {
            @Suppress("DEPRECATION")
            config.locale = locale
        }
        
        @Suppress("DEPRECATION")
        context.resources.updateConfiguration(config, context.resources.displayMetrics)
    }

    fun applyLanguage(activity: ComponentActivity, language: String) {
        saveLanguage(activity, language)
        updateConfiguration(activity)
        activity.recreate()
    }
}

