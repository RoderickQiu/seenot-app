package com.seenot.app

import android.app.Application
import com.seenot.app.config.ApiConfig
import com.seenot.app.domain.SessionManager
import com.seenot.app.utils.Logger

class SeeNotApplication : Application() {
    companion object {
        private const val TAG = "SeeNotApplication"

        // Default API key from BuildConfig (loaded from local.properties)
        private const val DEFAULT_API_KEY = BuildConfig.DASHSCOPE_API_KEY
        private const val DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    }

    override fun onCreate() {
        super.onCreate()

        // Initialize Logger first
        Logger.init(this, Logger.Level.DEBUG)
        Logger.i(TAG, "Application starting...")

        // Setup global uncaught exception handler
        setupGlobalExceptionHandler()

        // Initialize API Config
        ApiConfig.init(this)

        // Set default API key if not already set
        if (!ApiConfig.isConfigured()) {
            ApiConfig.setApiKey(DEFAULT_API_KEY)
            ApiConfig.setBaseUrl(DEFAULT_BASE_URL)
            Logger.i(TAG, "API key configured from defaults")
        }

        // Initialize SessionManager early to start observing app changes
        SessionManager.getInstance(this)
        Logger.i(TAG, "SessionManager initialized")
    }

    /**
     * Setup global uncaught exception handler to log all crashes
     */
    private fun setupGlobalExceptionHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                // Log the uncaught exception
                Logger.e(TAG, "Uncaught exception in thread: ${thread.name}", throwable)
                Logger.e(TAG, "Stack trace: ${throwable.stackTraceToString()}")

                // Log additional context
                Logger.e(TAG, "Thread ID: ${thread.id}, Priority: ${thread.priority}")
                Logger.e(TAG, "Exception type: ${throwable.javaClass.name}")
                Logger.e(TAG, "Exception message: ${throwable.message}")

                // Log cause chain if exists
                var cause = throwable.cause
                var depth = 1
                while (cause != null && depth <= 5) {
                    Logger.e(TAG, "Caused by [$depth]: ${cause.javaClass.name}: ${cause.message}")
                    cause = cause.cause
                    depth++
                }
            } catch (e: Exception) {
                // If logging fails, at least try to print to system
                e.printStackTrace()
            } finally {
                // Call the original handler to let the system handle the crash
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }

        Logger.i(TAG, "Global exception handler installed")
    }
}
