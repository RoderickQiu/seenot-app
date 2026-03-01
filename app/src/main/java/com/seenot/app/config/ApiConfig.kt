package com.seenot.app.config

import android.content.Context
import android.content.SharedPreferences

/**
 * API Configuration for SeeNot
 * Stores API keys and endpoints in encrypted SharedPreferences
 */
object ApiConfig {
    private const val PREFS_NAME = "api_config"
    private const val KEY_OPENAI_API_KEY = "openai_api_key"
    private const val KEY_OPENAI_BASE_URL = "openai_base_url"

    // Default values
    private const val DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1"

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getApiKey(): String {
        return prefs?.getString(KEY_OPENAI_API_KEY, "") ?: ""
    }

    fun setApiKey(key: String) {
        prefs?.edit()?.putString(KEY_OPENAI_API_KEY, key)?.apply()
    }

    fun getBaseUrl(): String {
        return prefs?.getString(KEY_OPENAI_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
    }

    fun setBaseUrl(url: String) {
        prefs?.edit()?.putString(KEY_OPENAI_BASE_URL, url)?.apply()
    }

    fun isConfigured(): Boolean {
        return getApiKey().isNotBlank()
    }
}
