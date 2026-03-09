package com.seenot.app.domain

import android.content.Context
import android.content.SharedPreferences
import com.seenot.app.utils.Logger
import java.text.SimpleDateFormat
import java.util.*

class DailyTimeTracker(context: Context) {

    companion object {
        private const val TAG = "DailyTimeTracker"
        private const val PREFS_NAME = "seenot_daily_time"
        private const val KEY_PREFIX = "daily_total_"
        private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getAccumulatedTime(constraintId: String): Long {
        val today = getTodayKey()
        val key = "${KEY_PREFIX}${constraintId}_$today"
        return prefs.getLong(key, 0L)
    }

    fun addTime(constraintId: String, deltaMs: Long) {
        val today = getTodayKey()
        val key = "${KEY_PREFIX}${constraintId}_$today"
        val current = prefs.getLong(key, 0L)
        val updated = current + deltaMs
        prefs.edit().putLong(key, updated).apply()
        Logger.d(TAG, "Updated daily time for $constraintId: ${updated}ms")
    }

    fun resetIfNewDay(constraintId: String) {
        val today = getTodayKey()
        val lastDateKey = "${KEY_PREFIX}${constraintId}_last_date"
        val lastDate = prefs.getString(lastDateKey, null)

        if (lastDate != today) {
            val key = "${KEY_PREFIX}${constraintId}_$today"
            prefs.edit()
                .putLong(key, 0L)
                .putString(lastDateKey, today)
                .apply()
            Logger.d(TAG, "Reset daily time for $constraintId (new day: $today)")
        }
    }

    fun cleanupOldEntries() {
        val today = getTodayKey()
        val yesterday = getYesterdayKey()
        val allKeys = prefs.all.keys

        val keysToRemove = allKeys.filter { key ->
            key.startsWith(KEY_PREFIX) && 
            !key.endsWith(today) && 
            !key.endsWith(yesterday) &&
            !key.endsWith("_last_date")
        }

        if (keysToRemove.isNotEmpty()) {
            val editor = prefs.edit()
            keysToRemove.forEach { editor.remove(it) }
            editor.apply()
            Logger.d(TAG, "Cleaned up ${keysToRemove.size} old daily time entries")
        }
    }

    private fun getTodayKey(): String {
        return dateFormat.format(Date())
    }

    private fun getYesterdayKey(): String {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.DAY_OF_YEAR, -1)
        return dateFormat.format(calendar.time)
    }
}
