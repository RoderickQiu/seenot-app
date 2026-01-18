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
import java.util.concurrent.atomic.AtomicBoolean

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
    private var logDirectory: File? = null
    private var currentLogFile: File? = null
    private var currentDayDirectory: File? = null
    private var isInitialized = AtomicBoolean(false)
    private var currentLevel = Level.DEBUG
    private var currentEntryCount = 0 // Track number of entries in current file

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
            val dayDir = currentDayDirectory ?: return

            currentLogFile = File(dayDir, "$LOG_FILE_NAME.$timestamp.txt").apply {
                if (!exists()) {
                    createNewFile()
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to create log file", e)
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
                }
                Log.d(TAG, "Test log write successful")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Test log write failed", e)
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
     * Internal log method
     */
    private fun log(level: Level, tag: String, message: String, throwable: Throwable? = null) {
        if (!isInitialized.get()) {
            Log.w(TAG, "Logger not initialized. Call Logger.init() first.")
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

        // Log to Android Logcat
        when (level) {
            Level.DEBUG -> Log.d(tag, message, throwable)
            Level.INFO -> Log.i(tag, message, throwable)
            Level.WARN -> Log.w(tag, message, throwable)
            Level.ERROR -> Log.e(tag, message, throwable)
        }

        // Log to file asynchronously
        writeToFile(logMessage)
    }

    /**
     * Write log message to file asynchronously
     */
    private fun writeToFile(message: String) {
        executor.execute {
            try {
                // Check if day changed and create new day directory if needed
                if (checkDayChange()) {
                    createCurrentDayDirectory()
                }

                val logFile = currentLogFile ?: return@execute

                // Check if file needs rotation (by size or entry count)
                if (logFile.length() >= MAX_LOG_FILE_SIZE || currentEntryCount >= MAX_LOG_ENTRIES) {
                    createNewLogFile()
                    currentEntryCount = 0 // Reset counter for new file
                }

                FileWriter(logFile, true).use { writer ->
                    writer.appendLine(message)
                }

                // Increment entry count
                currentEntryCount++
            } catch (e: IOException) {
                Log.e(TAG, "Failed to write log to file", e)
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