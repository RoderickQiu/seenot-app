package com.roderickqiu.seenot.utils

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Result of logger health check
 */
data class HealthCheckResult(
    var isInitialized: Boolean = false,
    var externalStorageAvailable: Boolean = false,
    var logDirectoryExists: Boolean = false,
    var currentDayDirectoryExists: Boolean = false,
    var currentLogFileExists: Boolean = false,
    var currentLogFileSize: Long = 0,
    var canWriteToLogFile: Boolean = false,
    var hasMissingDays: Boolean = false,
    var missingLogDays: List<String> = emptyList(),
    var recoveryAttempted: Boolean = false,
    var postRecoveryHealthy: Boolean = false,
    var error: String? = null
) {
    fun isHealthy(): Boolean {
        return isInitialized &&
               externalStorageAvailable &&
               logDirectoryExists &&
               currentDayDirectoryExists &&
               currentLogFileExists &&
               canWriteToLogFile &&
               !hasMissingDays
    }

    fun getSummary(): String {
        return buildString {
            append("Logger Health: ${if (isHealthy()) "HEALTHY" else "UNHEALTHY"}\n")
            append("Initialized: $isInitialized\n")
            append("External Storage: $externalStorageAvailable\n")
            append("Log Directory: $logDirectoryExists\n")
            append("Current Day Dir: $currentDayDirectoryExists\n")
            append("Current Log File: $currentLogFileExists\n")
            append("Can Write: $canWriteToLogFile\n")
            append("Missing Days: ${missingLogDays.size} (${missingLogDays.take(5).joinToString(", ")})")
            if (recoveryAttempted) {
                append("\nRecovery Attempted: Post-recovery healthy: $postRecoveryHealthy")
            }
            error?.let { append("\nError: $it") }
        }
    }
}

/**
 * Custom Logger class that provides logging functionality with file persistence
 * Supports different log levels and automatic log rotation
 */
object Logger {

    // Log levels
    enum class Level(val value: Int) {
        DEBUG(0),
        INFO(1),
        WARN(2),
        ERROR(3)
    }

    private const val TAG = "SeeNotLogger"
    private const val LOG_FILE_NAME = "seenot.log"
    private const val MAX_LOG_FILE_SIZE = 1 * 1024 * 1024 // 1MB
    private const val MAX_LOG_ENTRIES = 1000 // Maximum log entries per file

    private val executor = Executors.newSingleThreadExecutor()
    private val maintenanceExecutor = Executors.newSingleThreadScheduledExecutor()
    private var logDirectory: File? = null
    private var currentLogFile: File? = null
    private var currentDayDirectory: File? = null
    private var isInitialized = AtomicBoolean(false)
    private var currentLevel = Level.DEBUG
    private var currentEntryCount = 0 // Track number of entries in current file
    private var lastDayCheck = System.currentTimeMillis() // Track last day check time

    // Date format for log timestamps and file names
    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val timeFormat = SimpleDateFormat("HH-mm-ss-SSS", Locale.getDefault())

    /**
     * Initialize the logger with application context
     * Should be called once during app startup
     */
    fun init(context: Context, level: Level = Level.DEBUG) {
        if (isInitialized.getAndSet(true)) {
            Log.w(TAG, "Logger already initialized")
            return
        }

        currentLevel = level

        // Use external storage exclusively (no fallback to internal)
        logDirectory = try {
            val externalDir = context.getExternalFilesDir("logs")
            if (externalDir != null && Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
                Log.i(TAG, "Using external storage for logs: ${externalDir.absolutePath}")
                externalDir.apply {
                    if (!exists()) {
                        val created = mkdirs()
                        if (!created && !exists()) {
                            throw IllegalStateException("Failed to create external log directory")
                        }
                    }
                }
            } else {
                throw IllegalStateException("External storage not available. State: ${Environment.getExternalStorageState()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize external storage logger", e)
            throw RuntimeException("Logger initialization failed: External storage not available", e)
        }

        Log.i(TAG, "Logger initialized. Log directory: ${logDirectory?.absolutePath}")

        // Create current day directory
        createCurrentDayDirectory()

        // Create current log file
        createNewLogFile()

        // Reset entry count for new file
        currentEntryCount = 0

        // Test write to ensure everything works
        testLogWrite()

        Log.i(TAG, "Current day directory: ${currentDayDirectory?.absolutePath}")
        Log.i(TAG, "Current log file: ${currentLogFile?.absolutePath}")

        // Start maintenance task to check for day changes every hour
        startMaintenanceTask()
    }

    /**
     * Set the minimum log level
     */
    fun setLogLevel(level: Level) {
        currentLevel = level
    }

    /**
     * Get the current log directory
     */
    fun getLogDirectory(): File? = logDirectory

    /**
     * Get the current log directory path as string
     */
    fun getLogDirectoryPath(): String? = logDirectory?.absolutePath

    /**
     * Get the current entry count in the active log file
     */
    fun getCurrentEntryCount(): Int = currentEntryCount

    /**
     * Get the maximum entries per log file
     */
    fun getMaxEntriesPerFile(): Int = MAX_LOG_ENTRIES

    /**
     * Check if external storage is available for logging
     */
    fun isExternalStorageAvailable(context: Context): Boolean {
        return try {
            val externalDir = context.getExternalFilesDir("logs")
            externalDir != null && Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Get external storage state information
     */
    fun getExternalStorageInfo(): String {
        val state = Environment.getExternalStorageState()
        return when (state) {
            Environment.MEDIA_MOUNTED -> "Available"
            Environment.MEDIA_MOUNTED_READ_ONLY -> "Read-only"
            Environment.MEDIA_REMOVED -> "Removed"
            Environment.MEDIA_UNMOUNTED -> "Unmounted"
            Environment.MEDIA_CHECKING -> "Checking"
            Environment.MEDIA_NOFS -> "No filesystem"
            Environment.MEDIA_UNKNOWN -> "Unknown"
            else -> "State: $state"
        }
    }

    /**
     * Create directory for current day
     */
    private fun createCurrentDayDirectory() {
        try {
            val today = dateFormat.format(Date())
            currentDayDirectory = File(logDirectory, today).apply {
                if (!exists()) {
                    mkdirs()
                }
            }
            Log.i(TAG, "Created day directory: ${currentDayDirectory?.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create day directory", e)
        }
    }

    /**
     * Create a new log file with timestamp in filename
     */
    private fun createNewLogFile() {
        try {
            val timestamp = timeFormat.format(Date())
            val dayDir = currentDayDirectory ?: run {
                Log.w(TAG, "No current day directory, creating one")
                createCurrentDayDirectory()
                currentDayDirectory ?: throw IOException("Cannot create day directory")
            }

            // Ensure day directory exists
            if (!dayDir.exists()) {
                dayDir.mkdirs()
            }

            currentLogFile = File(dayDir, "$LOG_FILE_NAME.$timestamp.txt").apply {
                if (!exists()) {
                    val created = createNewFile()
                    if (!created) {
                        throw IOException("Failed to create log file: $absolutePath")
                    }
                }
                // Ensure file is writable
                if (!canWrite()) {
                    throw IOException("Log file is not writable: $absolutePath")
                }
            }

            Log.i(TAG, "Created new log file: ${currentLogFile?.absolutePath}")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to create log file", e)
            currentLogFile = null
            throw e // Re-throw to indicate failure
        }
    }

    /**
     * Check if we need to create a new day directory (crossed midnight)
     */
    private fun checkDayChange(): Boolean {
        val today = dateFormat.format(Date())
        val currentDay = currentDayDirectory?.name
        return currentDay != today
    }

    /**
     * Test log file write to ensure directory and file creation works
     */
    private fun testLogWrite() {
        try {
            val testMessage = "${timestampFormat.format(Date())} [TEST] [${Thread.currentThread().name}:${android.os.Process.myPid()}] LoggerTest: Logger initialization test\n"
            currentLogFile?.let { file ->
                FileWriter(file, true).use { writer ->
                    writer.write(testMessage)
                    writer.flush()
                }
                Log.d(TAG, "Test log write successful")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Test log write failed", e)
            throw e // Re-throw to indicate initialization failure
        }
    }

    /**
     * Perform health check and attempt recovery if needed
     */
    fun performHealthCheck(): HealthCheckResult {
        val result = HealthCheckResult()

        try {
            result.isInitialized = isInitialized.get()
            result.externalStorageAvailable = logDirectory?.exists() ?: false
            result.logDirectoryExists = logDirectory?.exists() ?: false
            result.currentDayDirectoryExists = currentDayDirectory?.exists() ?: false
            result.currentLogFileExists = currentLogFile?.exists() ?: false

            if (result.currentLogFileExists) {
                result.currentLogFileSize = currentLogFile?.length() ?: 0
                result.canWriteToLogFile = currentLogFile?.canWrite() ?: false
            }

            // Check for missing log days
            if (result.logDirectoryExists) {
                val missingDays = checkForMissingLogDays()
                result.missingLogDays = missingDays
                result.hasMissingDays = missingDays.isNotEmpty()
            }

            // Attempt recovery if issues found
            if (!result.isHealthy()) {
                Log.w(TAG, "Logger health check failed, attempting recovery")
                attemptRecovery()
                // Re-check after recovery
                result.recoveryAttempted = true
                result.postRecoveryHealthy = isHealthy()
            } else {
                result.postRecoveryHealthy = true
            }

        } catch (e: Exception) {
            Log.e(TAG, "Health check failed", e)
            result.error = e.message
        }

        return result
    }

    /**
     * Check if logger is in a healthy state
     */
    private fun isHealthy(): Boolean {
        return isInitialized.get() &&
               logDirectory?.exists() == true &&
               currentDayDirectory?.exists() == true &&
               currentLogFile?.exists() == true &&
               currentLogFile?.canWrite() == true
    }

    /**
     * Check for missing log days by comparing expected days with actual directories
     */
    private fun checkForMissingLogDays(): List<String> {
        val missingDays = mutableListOf<String>()

        try {
            val logDir = logDirectory ?: return missingDays

            // Get all existing day directories
            val existingDays = logDir.listFiles()?.filter { it.isDirectory }?.map { it.name }?.toSet() ?: emptySet()

            // Calculate expected days (last 30 days)
            val calendar = Calendar.getInstance()
            val expectedDays = mutableSetOf<String>()

            for (i in 0..29) {
                expectedDays.add(dateFormat.format(calendar.time))
                calendar.add(Calendar.DAY_OF_MONTH, -1)
            }

            // Find missing days
            for (expectedDay in expectedDays) {
                if (!existingDays.contains(expectedDay)) {
                    missingDays.add(expectedDay)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to check for missing log days", e)
        }

        return missingDays.sorted()
    }

    /**
     * Attempt to recover from logger issues
     */
    private fun attemptRecovery() {
        try {
            Log.i(TAG, "Attempting logger recovery")

            // Reset state
            currentLogFile = null
            currentDayDirectory = null
            currentEntryCount = 0

            // Recreate directory structure
            createCurrentDayDirectory()
            createNewLogFile()

            // Test write
            testLogWrite()

            Log.i(TAG, "Logger recovery completed")
        } catch (e: Exception) {
            Log.e(TAG, "Logger recovery failed", e)
        }
    }

    /**
     * Get the current log file
     */
    fun getCurrentLogFile(): File? = currentLogFile

    /**
     * Log debug message
     */
    fun d(tag: String, message: String, throwable: Throwable? = null) {
        log(Level.DEBUG, tag, message, throwable)
    }

    /**
     * Log info message
     */
    fun i(tag: String, message: String, throwable: Throwable? = null) {
        log(Level.INFO, tag, message, throwable)
    }

    /**
     * Log warning message
     */
    fun w(tag: String, message: String, throwable: Throwable? = null) {
        log(Level.WARN, tag, message, throwable)
    }

    /**
     * Log error message
     */
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        log(Level.ERROR, tag, message, throwable)
    }

    /**
     * Internal log method with fallback to Android logging if file logging fails
     */
    private fun log(level: Level, tag: String, message: String, throwable: Throwable? = null) {
        if (!isInitialized.get()) {
            // Fallback to Android logging if not initialized
            when (level) {
                Level.DEBUG -> Log.d(TAG, "Logger not initialized: $tag: $message", throwable)
                Level.INFO -> Log.i(TAG, "Logger not initialized: $tag: $message", throwable)
                Level.WARN -> Log.w(TAG, "Logger not initialized: $tag: $message", throwable)
                Level.ERROR -> Log.e(TAG, "Logger not initialized: $tag: $message", throwable)
            }
            return
        }

        if (level.value < currentLevel.value) {
            return
        }

        val timestamp = timestampFormat.format(Date())
        val levelStr = level.name
        val threadName = Thread.currentThread().name
        val pid = android.os.Process.myPid()

        val logMessage = buildString {
            append("$timestamp [$levelStr] [$threadName:$pid] $tag: $message")
            if (throwable != null) {
                append("\n${getStackTraceString(throwable)}")
            }
        }

        // Always log to Android Logcat (as backup)
        when (level) {
            Level.DEBUG -> Log.d(tag, message, throwable)
            Level.INFO -> Log.i(tag, message, throwable)
            Level.WARN -> Log.w(tag, message, throwable)
            Level.ERROR -> Log.e(tag, message, throwable)
        }

        // Try to log to file, with fallback error handling
        try {
            writeToFile(logMessage)
        } catch (e: Exception) {
            // If file logging fails, log the error to Android logcat
            Log.e(TAG, "File logging failed, falling back to Android logging: ${e.message}", e)
            // Don't recursively call log() here to avoid infinite loop
            Log.println(Log.ERROR, TAG, "File logging failed: $tag: $message")
        }
    }

    /**
     * Write log message to file asynchronously with recovery mechanisms
     */
    private fun writeToFile(message: String) {
        executor.execute {
            var retryCount = 0
            val maxRetries = 3

            while (retryCount < maxRetries) {
                try {
                    // Thread-safe day change handling
                    ensureCorrectDaySetup()

                    val logFile = currentLogFile ?: run {
                        // Try to recreate log file if missing
                        ensureCorrectDaySetup()
                        currentLogFile ?: throw IOException("Cannot create log file")
                    }

                    // Check if file needs rotation (by size or entry count)
                    if (logFile.length() >= MAX_LOG_FILE_SIZE || currentEntryCount >= MAX_LOG_ENTRIES) {
                        createNewLogFile()
                        currentEntryCount = 0 // Reset counter for new file
                    }

                    // Double-check we still have the correct file after potential rotation
                    val finalLogFile = currentLogFile ?: throw IOException("Log file lost during rotation")

                    // Ensure parent directories exist
                    finalLogFile.parentFile?.mkdirs()

                    FileWriter(finalLogFile, true).use { writer ->
                        writer.appendLine(message)
                        writer.flush() // Force write to disk
                    }

                    // Increment entry count
                    currentEntryCount++

                    // Success - break out of retry loop
                    break

                } catch (e: IOException) {
                    retryCount++
                    Log.e(TAG, "Failed to write log to file (attempt $retryCount/$maxRetries): ${e.message}", e)

                    if (retryCount >= maxRetries) {
                        // Final failure - try to reinitialize logger
                        try {
                            Log.w(TAG, "Attempting to reinitialize logger after repeated failures")
                            // Reset current files and try to recreate
                            currentLogFile = null
                            currentDayDirectory = null
                            ensureCorrectDaySetup()
                            currentEntryCount = 0
                        } catch (reinitError: Exception) {
                            Log.e(TAG, "Logger reinitialization also failed", reinitError)
                        }
                    } else {
                        // Wait before retry (exponential backoff)
                        try {
                            Thread.sleep((1000L * retryCount).coerceAtMost(5000L))
                        } catch (sleepError: InterruptedException) {
                            Thread.currentThread().interrupt()
                        }
                    }
                } catch (e: Exception) {
                    retryCount++
                    Log.e(TAG, "Unexpected error during log file write (attempt $retryCount/$maxRetries): ${e.message}", e)

                    if (retryCount >= maxRetries) {
                        break // Give up after max retries
                    }
                }
            }
        }
    }

    /**
     * Thread-safe method to ensure we have the correct day directory and log file
     * This handles date changes and ensures atomic setup
     */
    private fun ensureCorrectDaySetup() {
        val now = System.currentTimeMillis()
        val today = dateFormat.format(Date(now))

        // Periodic check every 5 minutes or if day changed
        val needsCheck = (now - lastDayCheck > 5 * 60 * 1000) || (currentDayDirectory?.name != today)

        if (needsCheck) {
            synchronized(this) {
                // Double-check after acquiring lock
                val currentToday = dateFormat.format(Date(System.currentTimeMillis()))
                if (currentDayDirectory?.name != currentToday) {
                    Log.i(TAG, "Date changed or periodic check, setting up new day directory: $currentToday")

                    // Create new day directory
                    currentDayDirectory = File(logDirectory, currentToday).apply {
                        if (!exists()) {
                            mkdirs()
                        }
                    }

                    // Create new log file for the new day
                    createNewLogFile()
                    currentEntryCount = 0

                    Log.i(TAG, "Successfully set up logging for day: $currentToday")
                }

                lastDayCheck = now
            }
        }

        // Ensure we have a log file even if day didn't change
        if (currentLogFile == null) {
            synchronized(this) {
                if (currentLogFile == null) {
                    createNewLogFile()
                    currentEntryCount = 0
                }
            }
        }
    }



    /**
     * Get stack trace as string
     */
    private fun getStackTraceString(throwable: Throwable): String {
        val sw = java.io.StringWriter()
        val pw = java.io.PrintWriter(sw)
        throwable.printStackTrace(pw)
        return sw.toString()
    }

    /**
     * Get all log files for export/debugging purposes
     */
    fun getAllLogFiles(): List<File> {
        val logDir = logDirectory ?: return emptyList()
        val allFiles = mutableListOf<File>()

        // Recursively find all log files in date directories
        logDir.walkTopDown().forEach { file ->
            if (file.isFile && file.name.startsWith(LOG_FILE_NAME)) {
                allFiles.add(file)
            }
        }

        return allFiles.sortedByDescending { it.lastModified() }
    }

    /**
     * Clear all log files
     */
    fun clearAllLogs() {
        executor.execute {
            try {
                getAllLogFiles().forEach { file ->
                    file.delete()
                }
                // Recreate current day directory and log file
                createCurrentDayDirectory()
                createNewLogFile()
                currentEntryCount = 0
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear logs", e)
            }
        }
    }

    /**
     * Start periodic maintenance task to ensure logger health
     */
    private fun startMaintenanceTask() {
        maintenanceExecutor.scheduleWithFixedDelay({
            try {
                // Periodic health check and day change detection
                ensureCorrectDaySetup()

                // Log a heartbeat every 6 hours to ensure system is working
                val hoursSinceStart = (System.currentTimeMillis() - lastDayCheck) / (1000 * 60 * 60)
                if (hoursSinceStart >= 6) {
                    Log.i(TAG, "Logger maintenance: System healthy, day=${currentDayDirectory?.name}, entries=$currentEntryCount")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Logger maintenance task failed", e)
            }
        }, 1, 60, TimeUnit.MINUTES) // Run every hour, start after 1 minute

        Log.i(TAG, "Logger maintenance task started")
    }

    /**
     * Create missing day directories to ensure continuity
     * This helps diagnose if days were missed due to logger failures
     */
    fun createMissingDayDirectories(daysBack: Int = 7) {
        executor.execute {
            try {
                val calendar = Calendar.getInstance()
                val baseDir = logDirectory ?: return@execute

                for (i in 0..daysBack) {
                    val dateStr = dateFormat.format(calendar.time)
                    val dayDir = File(baseDir, dateStr)

                    if (!dayDir.exists()) {
                        dayDir.mkdirs()
                        Logger.i(TAG, "Created missing day directory: $dateStr")
                    }

                    calendar.add(Calendar.DAY_OF_MONTH, -1)
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to create missing day directories", e)
            }
        }
    }

    /**
     * Get log file size in human readable format
     */
    fun getTotalLogSize(): String {
        val totalSize = getAllLogFiles().sumOf { it.length() }
        return formatFileSize(totalSize)
    }

    /**
     * Format file size to human readable string
     */
    private fun formatFileSize(size: Long): String {
        if (size < 1024) return "$size B"
        val z = (63 - java.lang.Long.numberOfLeadingZeros(size)) / 10
        return String.format("%.1f %sB", size.toDouble() / (1L shl z * 10), " KMGTPE"[z])
    }
}