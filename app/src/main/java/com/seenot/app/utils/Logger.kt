package com.seenot.app.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
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
    private val healthCheckExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

    private var logDirectory: File? = null
    private var currentLogFile: File? = null
    private var currentLevel: Level = Level.DEBUG
    private var logEntryCount = 0
    private val isInitialized = AtomicBoolean(false)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    /**
     * Initialize the logger with application context
     */
    fun init(context: Context, level: Level = Level.DEBUG) {
        if (isInitialized.getAndSet(true)) {
            Log.w(TAG, "Logger already initialized")
            return
        }

        currentLevel = level

        executor.execute {
            try {
                setupLogDirectory(context)
                i(TAG, "Logger initialized successfully")

                // Start periodic health check
                startHealthCheck()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize logger", e)
            }
        }
    }

    /**
     * Setup log directory structure
     */
    private fun setupLogDirectory(context: Context) {
        val baseDir = if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
            context.getExternalFilesDir(null)
        } else {
            context.filesDir
        }

        logDirectory = File(baseDir, "logs").apply {
            if (!exists()) {
                mkdirs()
            }
        }

        createCurrentDayDirectory()
    }

    /**
     * Create directory for current day
     */
    private fun createCurrentDayDirectory() {
        val today = dateFormat.format(Date())
        val dayDir = File(logDirectory, today)

        if (!dayDir.exists()) {
            dayDir.mkdirs()
        }

        currentLogFile = File(dayDir, LOG_FILE_NAME)
        logEntryCount = 0
    }

    /**
     * Check if current day has changed and rotate if needed
     */
    private fun checkDayRotation() {
        val today = dateFormat.format(Date())
        val currentDay = currentLogFile?.parentFile?.name

        if (currentDay != today) {
            createCurrentDayDirectory()
        }
    }

    /**
     * Write log entry to file
     */
    private fun writeToFile(level: Level, tag: String, message: String, throwable: Throwable? = null) {
        executor.execute {
            try {
                checkDayRotation()

                val logFile = currentLogFile ?: return@execute

                // Check if rotation is needed
                if (logFile.length() > MAX_LOG_FILE_SIZE || logEntryCount >= MAX_LOG_ENTRIES) {
                    rotateLogFile()
                }

                val timestamp = timeFormat.format(Date())
                val levelStr = level.name.padEnd(5)
                val logEntry = buildString {
                    append("$timestamp $levelStr [$tag] $message")
                    throwable?.let {
                        append("\n")
                        append(it.stackTraceToString())
                    }
                    append("\n")
                }

                FileWriter(logFile, true).use { writer ->
                    writer.write(logEntry)
                }

                logEntryCount++
            } catch (e: IOException) {
                Log.e(TAG, "Failed to write log to file", e)
            }
        }
    }

    /**
     * Rotate log file when size limit is reached
     */
    private fun rotateLogFile() {
        val logFile = currentLogFile ?: return
        val timestamp = SimpleDateFormat("HHmmss", Locale.getDefault()).format(Date())
        val rotatedFile = File(logFile.parent, "${LOG_FILE_NAME}.$timestamp")

        try {
            logFile.renameTo(rotatedFile)
            logEntryCount = 0
        } catch (e: Exception) {
            Log.e(TAG, "Failed to rotate log file", e)
        }
    }

    /**
     * Log debug message
     */
    fun d(tag: String, message: String) {
        if (currentLevel.value <= Level.DEBUG.value) {
            Log.d(tag, message)
            writeToFile(Level.DEBUG, tag, message)
        }
    }

    /**
     * Log info message
     */
    fun i(tag: String, message: String) {
        if (currentLevel.value <= Level.INFO.value) {
            Log.i(tag, message)
            writeToFile(Level.INFO, tag, message)
        }
    }

    /**
     * Log warning message
     */
    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (currentLevel.value <= Level.WARN.value) {
            if (throwable != null) {
                Log.w(tag, message, throwable)
            } else {
                Log.w(tag, message)
            }
            writeToFile(Level.WARN, tag, message, throwable)
        }
    }

    /**
     * Log error message
     */
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (currentLevel.value <= Level.ERROR.value) {
            if (throwable != null) {
                Log.e(tag, message, throwable)
            } else {
                Log.e(tag, message)
            }
            writeToFile(Level.ERROR, tag, message, throwable)
        }
    }

    /**
     * Set log level
     */
    fun setLevel(level: Level) {
        currentLevel = level
        i(TAG, "Log level changed to ${level.name}")
    }

    /**
     * Get current log level
     */
    fun getLevel(): Level = currentLevel

    /**
     * Clear all log files
     */
    fun clearLogs() {
        executor.execute {
            try {
                logDirectory?.listFiles()?.forEach { dayDir ->
                    if (dayDir.isDirectory) {
                        dayDir.listFiles()?.forEach { it.delete() }
                        dayDir.delete()
                    }
                }
                createCurrentDayDirectory()
                i(TAG, "All logs cleared")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear logs", e)
            }
        }
    }

    /**
     * Get all log files
     */
    fun getAllLogFiles(): List<File> {
        val files = mutableListOf<File>()
        logDirectory?.listFiles()?.forEach { dayDir ->
            if (dayDir.isDirectory) {
                dayDir.listFiles()?.forEach { file ->
                    if (file.isFile) {
                        files.add(file)
                    }
                }
            }
        }
        return files.sortedByDescending { it.lastModified() }
    }

    /**
     * Get log files for specific date
     */
    fun getLogFilesForDate(date: Date): List<File> {
        val dateStr = dateFormat.format(date)
        val dayDir = File(logDirectory, dateStr)
        return if (dayDir.exists() && dayDir.isDirectory) {
            dayDir.listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()
        } else {
            emptyList()
        }
    }

    /**
     * Get current log file
     */
    fun getCurrentLogFile(): File? = currentLogFile

    /**
     * Get log directory
     */
    fun getLogDirectory(): File? = logDirectory

    /**
     * Check logger health
     */
    fun checkHealth(): HealthCheckResult {
        val result = HealthCheckResult()

        try {
            result.isInitialized = isInitialized.get()

            if (!result.isInitialized) {
                result.error = "Logger not initialized"
                return result
            }

            result.externalStorageAvailable = Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED

            val baseDir = logDirectory
            result.logDirectoryExists = baseDir?.exists() == true

            if (!result.logDirectoryExists) {
                result.error = "Log directory does not exist"
                return result
            }

            val today = dateFormat.format(Date())
            val dayDir = File(baseDir, today)
            result.currentDayDirectoryExists = dayDir.exists()

            val logFile = currentLogFile
            result.currentLogFileExists = logFile?.exists() == true
            result.currentLogFileSize = logFile?.length() ?: 0

            // Test write capability
            result.canWriteToLogFile = try {
                logFile?.let {
                    FileWriter(it, true).use { writer ->
                        writer.write("")
                    }
                    true
                } ?: false
            } catch (e: Exception) {
                result.error = "Cannot write to log file: ${e.message}"
                false
            }

            // Check for missing days
            val missingDays = findMissingLogDays()
            result.hasMissingDays = missingDays.isNotEmpty()
            result.missingLogDays = missingDays

        } catch (e: Exception) {
            result.error = "Health check failed: ${e.message}"
        }

        return result
    }

    /**
     * Find missing log days
     */
    private fun findMissingLogDays(): List<String> {
        val baseDir = logDirectory ?: return emptyList()
        val missingDays = mutableListOf<String>()

        val calendar = Calendar.getInstance()
        val today = calendar.time

        // Check last 7 days
        for (i in 0..6) {
            val dateStr = dateFormat.format(calendar.time)
            val dayDir = File(baseDir, dateStr)

            if (!dayDir.exists() && calendar.time.before(today)) {
                missingDays.add(dateStr)
            }

            calendar.add(Calendar.DAY_OF_MONTH, -1)
        }

        return missingDays
    }

    /**
     * Start periodic health check
     */
    private fun startHealthCheck() {
        healthCheckExecutor.scheduleAtFixedRate({
            try {
                val health = checkHealth()
                if (!health.isHealthy()) {
                    Log.w(TAG, "Logger health check failed: ${health.getSummary()}")
                    attemptRecovery(health)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Health check error", e)
            }
        }, 1, 60, TimeUnit.MINUTES)
    }

    /**
     * Attempt to recover from unhealthy state
     */
    private fun attemptRecovery(health: HealthCheckResult) {
        executor.execute {
            try {
                Log.i(TAG, "Attempting logger recovery...")

                if (!health.logDirectoryExists) {
                    logDirectory?.mkdirs()
                }

                if (!health.currentDayDirectoryExists) {
                    createCurrentDayDirectory()
                }

                if (health.hasMissingDays) {
                    createMissingDayDirectories()
                }

                val postRecoveryHealth = checkHealth()
                if (postRecoveryHealth.isHealthy()) {
                    Log.i(TAG, "Logger recovery successful")
                } else {
                    Log.w(TAG, "Logger recovery incomplete: ${postRecoveryHealth.getSummary()}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Logger recovery failed", e)
            }
        }
    }

    /**
     * Create missing day directories
     */
    private fun createMissingDayDirectories() {
        executor.execute {
            try {
                val calendar = Calendar.getInstance()
                val daysBack = 7

                val baseDir = logDirectory ?: return@execute

                for (i in 0..daysBack) {
                    val dateStr = dateFormat.format(calendar.time)
                    val dayDir = File(baseDir, dateStr)

                    if (!dayDir.exists()) {
                        dayDir.mkdirs()
                        i(TAG, "Created missing day directory: $dateStr")
                    }

                    calendar.add(Calendar.DAY_OF_MONTH, -1)
                }
            } catch (e: Exception) {
                e(TAG, "Failed to create missing day directories", e)
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

    /**
     * Export logs from a date range to a single file
     * @param startDate Start date (inclusive)
     * @param endDate End date (inclusive)
     * @param outputFile Output file to write combined logs
     * @return True if export succeeded, false otherwise
     */
    fun exportLogs(startDate: Date, endDate: Date, outputFile: File): Boolean {
        return try {
            val baseDir = logDirectory ?: return false

            val calendar = Calendar.getInstance()
            calendar.time = startDate

            val endCalendar = Calendar.getInstance()
            endCalendar.time = endDate

            // Ensure output directory exists
            outputFile.parentFile?.mkdirs()

            FileWriter(outputFile, false).use { writer ->
                writer.write("=== SeeNot Log Export ===\n")
                writer.write("Export Date: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}\n")
                writer.write("Date Range: ${dateFormat.format(startDate)} to ${dateFormat.format(endDate)}\n")
                writer.write("${"=".repeat(50)}\n\n")

                while (calendar.time <= endCalendar.time) {
                    val dateStr = dateFormat.format(calendar.time)
                    val dayDir = File(baseDir, dateStr)

                    if (dayDir.exists() && dayDir.isDirectory) {
                        writer.write("\n### Logs for $dateStr ###\n\n")

                        // Get all log files for this day, sorted by name
                        val logFiles = dayDir.listFiles { file ->
                            file.isFile && file.name.startsWith("seenot.log")
                        }?.sortedBy { it.name } ?: emptyList()

                        for (logFile in logFiles) {
                            writer.write("--- ${logFile.name} ---\n")
                            logFile.bufferedReader().use { reader ->
                                reader.copyTo(writer)
                            }
                            writer.write("\n")
                        }
                    }

                    calendar.add(Calendar.DAY_OF_MONTH, 1)
                }

                writer.write("\n=== End of Export ===\n")
            }

            i(TAG, "Logs exported to: ${outputFile.absolutePath}")
            true
        } catch (e: Exception) {
            e(TAG, "Failed to export logs", e)
            false
        }
    }

    /**
     * Export logs from a date range (string format: yyyy-MM-dd)
     */
    fun exportLogs(startDateStr: String, endDateStr: String, outputFile: File): Boolean {
        return try {
            val startDate = dateFormat.parse(startDateStr) ?: return false
            val endDate = dateFormat.parse(endDateStr) ?: return false
            exportLogs(startDate, endDate, outputFile)
        } catch (e: Exception) {
            e(TAG, "Failed to parse dates for export", e)
            false
        }
    }

    /**
     * Export all logs to a single file
     */
    fun exportAllLogs(outputFile: File): Boolean {
        return try {
            val baseDir = logDirectory ?: return false
            val allDirs = baseDir.listFiles { file ->
                file.isDirectory && file.name.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))
            }?.sortedBy { it.name } ?: return false

            if (allDirs.isEmpty()) return false

            val firstDate = dateFormat.parse(allDirs.first().name) ?: return false
            val lastDate = dateFormat.parse(allDirs.last().name) ?: return false

            exportLogs(firstDate, lastDate, outputFile)
        } catch (e: Exception) {
            e(TAG, "Failed to export all logs", e)
            false
        }
    }

    /**
     * Get logs from a date range as a string
     */
    fun getLogsAsString(startDate: Date, endDate: Date): String? {
        return try {
            val tempFile = File.createTempFile("seenot_logs_", ".txt")
            if (exportLogs(startDate, endDate, tempFile)) {
                val content = tempFile.readText()
                tempFile.delete()
                content
            } else {
                null
            }
        } catch (e: Exception) {
            e(TAG, "Failed to get logs as string", e)
            null
        }
    }

    /**
     * Share logs from a date range
     */
    fun shareLogs(context: Context, startDate: Date, endDate: Date): Boolean {
        return try {
            val tempFile = File(context.cacheDir, "seenot_logs_${System.currentTimeMillis()}.txt")
            if (exportLogs(startDate, endDate, tempFile)) {
                shareFile(context, tempFile)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            e(TAG, "Failed to share logs", e)
            false
        }
    }

    /**
     * Share all logs
     */
    fun shareAllLogs(context: Context): Boolean {
        return try {
            val tempFile = File(context.cacheDir, "seenot_all_logs_${System.currentTimeMillis()}.txt")
            if (exportAllLogs(tempFile)) {
                shareFile(context, tempFile)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            e(TAG, "Failed to share all logs", e)
            false
        }
    }

    /**
     * Share a file using Android share intent
     */
    private fun shareFile(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "SeeNot 日志")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        context.startActivity(Intent.createChooser(intent, "分享日志").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    /**
     * Shutdown logger
     */
    fun shutdown() {
        executor.shutdown()
        healthCheckExecutor.shutdown()
        isInitialized.set(false)
    }
}
