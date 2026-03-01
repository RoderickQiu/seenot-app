package com.seenot.app

import android.app.Application
import android.util.Log
import com.seenot.app.config.ApiConfig
import com.seenot.app.domain.SessionManager

class SeeNotApplication : Application() {
    companion object {
        private const val TAG = "SeeNotApplication"

        // Default API key from .env - replace with actual key or set via settings
        private const val DEFAULT_API_KEY = "REMOVED_API_KEY"
        private const val DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Application onCreate")

        // Initialize API Config
        ApiConfig.init(this)

        // Set default API key if not already set
        if (!ApiConfig.isConfigured()) {
            ApiConfig.setApiKey(DEFAULT_API_KEY)
            ApiConfig.setBaseUrl(DEFAULT_BASE_URL)
            Log.d(TAG, "API key configured from defaults")
        }

        // Initialize SessionManager early to start observing app changes
        SessionManager.getInstance(this)
        Log.d(TAG, "SessionManager initialized")
    }
}
