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

        ApiConfig.reconcileDevelopmentInjectedDashscopeKey(
            buildInjectedKey = BuildConfig.DASHSCOPE_API_KEY,
            developmentModeEnabled = BuildConfig.ENABLE_DEVELOPMENT_MODE,
            validUntilEpochMs = BuildConfig.DEVELOPMENT_DASHSCOPE_KEY_VALID_UNTIL_EPOCH_MS
        )

        // Seed default LLM provider config on fresh installs.
        if (!ApiConfig.isConfigured()) {
            val defaultApiKey = currentInjectedDashscopeKeyIfActive()
            ApiConfig.saveSettings(
                ApiSettings.defaults(AiProvider.DASHSCOPE).copy(
                    apiKey = defaultApiKey
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

    private fun currentInjectedDashscopeKeyIfActive(nowEpochMs: Long = System.currentTimeMillis()): String {
        return if (
            BuildConfig.ENABLE_DEVELOPMENT_MODE &&
            BuildConfig.DASHSCOPE_API_KEY.isNotBlank() &&
            BuildConfig.DEVELOPMENT_DASHSCOPE_KEY_VALID_UNTIL_EPOCH_MS > nowEpochMs
        ) {
            BuildConfig.DASHSCOPE_API_KEY
        } else {
            ""
        }
    }
}
