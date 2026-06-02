package com.seenot.app.service

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process

class ForegroundUsageStatsReader(private val context: Context) {
    private val usageStatsManager =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

    fun hasPermission(): Boolean {
        val appOpsManager = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOpsManager.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOpsManager.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun readTransitionEvents(
        beginMs: Long,
        endMs: Long
    ): List<UsageStatsForegroundReducer.UsageEvent> {
        val usageEvents = usageStatsManager.queryEvents(beginMs, endMs)
        val event = UsageEvents.Event()
        val result = mutableListOf<UsageStatsForegroundReducer.UsageEvent>()

        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(event)
            val type = event.toReducerEventType() ?: continue
            val packageName = event.packageName ?: continue
            if (packageName == context.packageName) continue

            result += UsageStatsForegroundReducer.UsageEvent(
                timeMs = event.timeStamp,
                packageName = packageName,
                className = event.className,
                type = type
            )
        }

        return result.sortedBy { it.timeMs }
    }

    private fun UsageEvents.Event.toReducerEventType(): UsageStatsForegroundReducer.EventType? {
        return when {
            eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ->
                UsageStatsForegroundReducer.EventType.ENTER
            eventType == UsageEvents.Event.MOVE_TO_BACKGROUND ->
                UsageStatsForegroundReducer.EventType.EXIT
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                eventType == UsageEvents.Event.ACTIVITY_RESUMED ->
                UsageStatsForegroundReducer.EventType.ENTER
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                eventType == UsageEvents.Event.ACTIVITY_PAUSED ->
                UsageStatsForegroundReducer.EventType.EXIT
            else -> null
        }
    }

    companion object {
        const val QUERY_WINDOW_MS = 10_000L
    }
}
