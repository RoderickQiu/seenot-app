package com.roderickqiu.seenot.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.roderickqiu.seenot.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.math.BigInteger
import java.util.Calendar

/**
 * Repository for tracking AI API call statistics.
 * Uses BigInteger for counters to support virtually unlimited values.
 */
class AIStatsRepo(private val context: Context) {

    private val gson: Gson = GsonBuilder()
        .setPrettyPrinting()
        .create()
    private val statsFile: File = File(context.filesDir, "ai_stats.json")

    companion object {
        private const val TAG = "AIStatsRepo"
        private const val MAX_DAILY_RECORDS = 30 // Keep last 30 days of daily stats
    }

    /**
     * Record a single AI API call.
     * Thread-safe, can be called from any coroutine context.
     */
    suspend fun recordApiCall(
        success: Boolean,
        latencyMs: Long,
        promptTokens: Int? = null,
        completionTokens: Int? = null,
        matchedRules: Int = 0
    ) = withContext(Dispatchers.IO) {
        try {
            val stats = loadStats()
            val today = getTodayKey()

            // Update overall stats
            val newOverall = stats.overall.update(success, latencyMs, promptTokens, completionTokens, matchedRules)

            // Update today's stats
            val dailyStats = stats.daily.toMutableMap()
            val todayStats = dailyStats[today] ?: DailyStats.empty()
            dailyStats[today] = todayStats.update(success, latencyMs, promptTokens, completionTokens, matchedRules)

            // Cleanup old daily records
            val trimmedDaily = trimOldRecords(dailyStats)

            // Update this week's stats
            val weekKey = getThisWeekKey()
            val weeklyStats = stats.weekly.toMutableMap()
            val thisWeekStats = weeklyStats[weekKey] ?: PeriodStats.empty()
            weeklyStats[weekKey] = thisWeekStats.update(success, latencyMs, promptTokens, completionTokens, matchedRules)

            // Cleanup old weekly records (keep 12 weeks)
            val trimmedWeekly = trimOldWeeklyRecords(weeklyStats, 12)

            val newStats = AIStats(
                overall = newOverall,
                daily = trimmedDaily,
                weekly = trimmedWeekly,
                lastUpdated = System.currentTimeMillis()
            )

            saveStats(newStats)
            Logger.d(TAG, "Recorded API call: success=$success, latency=${latencyMs}ms")
        } catch (e: Exception) {
            Logger.e(TAG, "Error recording API call stats", e)
        }
    }

    /**
     * Get statistics for display.
     * Returns today, this week, and overall stats.
     */
    fun getDisplayStats(): DisplayStats {
        val stats = loadStats()
        val today = getTodayKey()
        val weekKey = getThisWeekKey()

        return DisplayStats(
            today = stats.daily[today] ?: DailyStats.empty(),
            thisWeek = stats.weekly[weekKey] ?: PeriodStats.empty(),
            overall = stats.overall,
            lastUpdated = stats.lastUpdated
        )
    }

    /**
     * Get the raw stats data (for export/debugging)
     */
    fun getAllStats(): AIStats = loadStats()

    /**
     * Reset all statistics.
     */
    suspend fun clearAllStats() = withContext(Dispatchers.IO) {
        try {
            if (statsFile.exists()) {
                statsFile.writeText("{}")
            }
            Logger.d(TAG, "Cleared all AI stats")
        } catch (e: Exception) {
            Logger.e(TAG, "Error clearing stats", e)
        }
    }

    private fun loadStats(): AIStats {
        return try {
            if (!statsFile.exists()) return AIStats.empty()

            val json = statsFile.readText()
            if (json.isBlank()) return AIStats.empty()

            val type = object : TypeToken<AIStats>() {}.type
            gson.fromJson(json, type) ?: AIStats.empty()
        } catch (e: Exception) {
            Logger.e(TAG, "Error loading AI stats", e)
            AIStats.empty()
        }
    }

    private fun saveStats(stats: AIStats) {
        try {
            val json = gson.toJson(stats)
            statsFile.writeText(json)
        } catch (e: Exception) {
            Logger.e(TAG, "Error saving AI stats", e)
        }
    }

    private fun trimOldRecords(daily: Map<String, DailyStats>): Map<String, DailyStats> {
        if (daily.size <= MAX_DAILY_RECORDS) return daily

        val sortedKeys = daily.keys.sortedDescending()
        val keysToKeep = sortedKeys.take(MAX_DAILY_RECORDS)
        return daily.filterKeys { it in keysToKeep }
    }

    private fun trimOldWeeklyRecords(weekly: Map<String, PeriodStats>, keep: Int): Map<String, PeriodStats> {
        if (weekly.size <= keep) return weekly

        val sortedKeys = weekly.keys.sortedDescending()
        val keysToKeep = sortedKeys.take(keep)
        return weekly.filterKeys { it in keysToKeep }
    }

    private fun getTodayKey(): String {
        val cal = Calendar.getInstance()
        return "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.MONTH) + 1}-${cal.get(Calendar.DAY_OF_MONTH)}"
    }

    private fun getThisWeekKey(): String {
        val cal = Calendar.getInstance()
        val year = cal.get(Calendar.YEAR)
        val week = cal.get(Calendar.WEEK_OF_YEAR)
        return "${year}-W${week}"
    }
}

/**
 * Main stats container.
 */
data class AIStats(
    val overall: PeriodStats,
    val daily: Map<String, DailyStats>,
    val weekly: Map<String, PeriodStats>,
    val lastUpdated: Long
) {
    companion object {
        fun empty() = AIStats(
            overall = PeriodStats.empty(),
            daily = emptyMap(),
            weekly = emptyMap(),
            lastUpdated = 0
        )
    }
}

/**
 * Statistics for a specific time period (overall or weekly).
 * Uses BigInteger for counters to support unlimited growth.
 */
data class PeriodStats(
    val totalRequests: BigInteger,
    val successfulRequests: BigInteger,
    val failedRequests: BigInteger,
    val ruleMatches: BigInteger,
    val totalTokens: BigInteger, // prompt + completion
    val promptTokens: BigInteger,
    val completionTokens: BigInteger,
    val totalLatencyMs: BigInteger,
    val minLatencyMs: Long,
    val maxLatencyMs: Long,
    val recordCount: BigInteger // for calculating average
) {
    fun update(
        success: Boolean,
        latencyMs: Long,
        promptTokens: Int?,
        completionTokens: Int?,
        matchedRules: Int
    ): PeriodStats {
        val pTokens = promptTokens?.toBigInteger() ?: BigInteger.ZERO
        val cTokens = completionTokens?.toBigInteger() ?: BigInteger.ZERO

        return PeriodStats(
            totalRequests = totalRequests + BigInteger.ONE,
            successfulRequests = if (success) successfulRequests + BigInteger.ONE else successfulRequests,
            failedRequests = if (!success) failedRequests + BigInteger.ONE else failedRequests,
            ruleMatches = ruleMatches + matchedRules.toBigInteger(),
            totalTokens = totalTokens + pTokens + cTokens,
            promptTokens = this.promptTokens + pTokens,
            completionTokens = this.completionTokens + cTokens,
            totalLatencyMs = totalLatencyMs + latencyMs.toBigInteger(),
            minLatencyMs = if (recordCount == BigInteger.ZERO) latencyMs else minOf(minLatencyMs, latencyMs),
            maxLatencyMs = if (recordCount == BigInteger.ZERO) latencyMs else maxOf(maxLatencyMs, latencyMs),
            recordCount = recordCount + BigInteger.ONE
        )
    }

    val avgLatencyMs: Long
        get() = if (recordCount > BigInteger.ZERO) {
            (totalLatencyMs / recordCount).toLong()
        } else 0

    val successRate: Float
        get() = if (totalRequests > BigInteger.ZERO) {
            successfulRequests.toFloat() / totalRequests.toFloat()
        } else 0f

    companion object {
        fun empty() = PeriodStats(
            totalRequests = BigInteger.ZERO,
            successfulRequests = BigInteger.ZERO,
            failedRequests = BigInteger.ZERO,
            ruleMatches = BigInteger.ZERO,
            totalTokens = BigInteger.ZERO,
            promptTokens = BigInteger.ZERO,
            completionTokens = BigInteger.ZERO,
            totalLatencyMs = BigInteger.ZERO,
            minLatencyMs = Long.MAX_VALUE,
            maxLatencyMs = 0,
            recordCount = BigInteger.ZERO
        )
    }
}

/**
 * Daily statistics with execution count tracking.
 */
data class DailyStats(
    val periodStats: PeriodStats,
    val actionExecutions: BigInteger // Number of times actions were actually executed
) {
    fun update(
        success: Boolean,
        latencyMs: Long,
        promptTokens: Int?,
        completionTokens: Int?,
        matchedRules: Int
    ): DailyStats {
        return DailyStats(
            periodStats = periodStats.update(success, latencyMs, promptTokens, completionTokens, matchedRules),
            actionExecutions = actionExecutions + matchedRules.toBigInteger()
        )
    }

    // Delegate properties for convenience
    val totalRequests: BigInteger get() = periodStats.totalRequests
    val successfulRequests: BigInteger get() = periodStats.successfulRequests
    val failedRequests: BigInteger get() = periodStats.failedRequests
    val ruleMatches: BigInteger get() = periodStats.ruleMatches
    val totalTokens: BigInteger get() = periodStats.totalTokens
    val promptTokens: BigInteger get() = periodStats.promptTokens
    val completionTokens: BigInteger get() = periodStats.completionTokens
    val avgLatencyMs: Long get() = periodStats.avgLatencyMs
    val minLatencyMs: Long get() = periodStats.minLatencyMs
    val maxLatencyMs: Long get() = periodStats.maxLatencyMs
    val successRate: Float get() = periodStats.successRate

    companion object {
        fun empty() = DailyStats(
            periodStats = PeriodStats.empty(),
            actionExecutions = BigInteger.ZERO
        )
    }
}

/**
 * Aggregated statistics for display.
 */
data class DisplayStats(
    val today: DailyStats,
    val thisWeek: PeriodStats,
    val overall: PeriodStats,
    val lastUpdated: Long
)
