package com.seenot.app.config

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * API Configuration for SeeNot
 * Stores API keys and endpoints in encrypted SharedPreferences
 */
object ApiConfig {
    private const val PREFS_NAME = "api_config_secure"
    private const val KEY_OPENAI_API_KEY = "openai_api_key"
    private const val KEY_OPENAI_BASE_URL = "openai_base_url"

    // Default values
    private const val DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1"

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        prefs = EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
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
