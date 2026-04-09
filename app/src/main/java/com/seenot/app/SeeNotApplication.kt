package com.seenot.app

import android.app.Application
import com.seenot.app.config.ApiConfig
import com.seenot.app.config.AiProvider
import com.seenot.app.config.ApiSettings
import com.seenot.app.domain.SessionManager
import com.seenot.app.utils.Logger

class SeeNotApplication : Application() {
    companion object {
        private const val TAG = "SeeNotApplication"

        private const val DEFAULT_API_KEY = BuildConfig.DASHSCOPE_API_KEY
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

        // Seed default LLM provider config on fresh installs.
        if (!ApiConfig.isConfigured()) {
            ApiConfig.saveSettings(
                ApiSettings.defaults(AiProvider.DASHSCOPE).copy(
                    apiKey = DEFAULT_API_KEY
                )
            )
            Logger.i(TAG, "AI model config seeded from defaults")
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
                val crashSummary = buildString {
                    append("Uncaught exception in thread: ${thread.name}")
                    append("\nThread ID: ${thread.id}, Priority: ${thread.priority}")
                    append("\nException type: ${throwable.javaClass.name}")
                    append("\nException message: ${throwable.message}")
                }
                Logger.eImmediate(TAG, crashSummary, throwable)

                var cause = throwable.cause
                var depth = 1
                while (cause != null && depth <= 5) {
                    Logger.eImmediate(
                        TAG,
                        "Caused by [$depth]: ${cause.javaClass.name}: ${cause.message}",
                        cause
                    )
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
