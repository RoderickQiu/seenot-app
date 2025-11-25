package com.roderickqiu.seenot.service

import android.util.Log
import com.roderickqiu.seenot.data.AppDataStore
import com.roderickqiu.seenot.data.Rule
import com.roderickqiu.seenot.data.TimeConstraint
import com.roderickqiu.seenot.utils.GenericUtils

class ConstraintManager(
    private val appDataStore: AppDataStore,
    private val actionExecutor: ActionExecutor
) {
    // State tracking for time constraints
    private val shortTermRecords = mutableMapOf<String, MutableList<TimeRecord>>()
    private val dailyTotalRecords = mutableMapOf<String, MutableList<TimeRecord>>()
    private val recentTotalRecords = mutableMapOf<String, MutableList<TimeRecord>>()
    
    data class TimeRecord(
        val startTime: Long,
        val endTime: Long? = null // null means still active
    )

    fun handleTimeConstraint(rule: Rule, appName: String, isMatch: Boolean) {
        val constraint = rule.timeConstraint ?: return
        
        when (constraint) {
            is TimeConstraint.Continuous -> {
                handleContinuousConstraint(rule, appName, constraint, isMatch)
            }
            is TimeConstraint.DailyTotal -> {
                handleDailyTotalConstraint(rule, appName, constraint, isMatch)
            }
            is TimeConstraint.RecentTotal -> {
                handleRecentTotalConstraint(rule, appName, constraint, isMatch)
            }
        }
    }
    
    private fun handleContinuousConstraint(
        rule: Rule,
        appName: String,
        constraint: TimeConstraint.Continuous,
        isMatch: Boolean
    ) {
        val stateKey = "${appName}_${rule.id}"
        val now = System.currentTimeMillis()
        
        // Window size: 3x the target time (e.g., 5 min target = 15 min window)
        // This allows for some detection errors while still requiring substantial matching time
        val windowSizeMinutes = constraint.minutes * 3.0
        val windowSizeMs = (windowSizeMinutes * 60 * 1000).toLong()
        val cutoffTime = now - windowSizeMs
        
        if (isMatch) {
            // Start or continue recording
            val records = shortTermRecords.getOrPut(stateKey) { mutableListOf() }
            val activeRecord = records.find { it.endTime == null }
            
            if (activeRecord == null) {
                // Start new record
                records.add(TimeRecord(startTime = now))
                Log.d("A11yService", "Started short-term tracking for rule ${rule.id}, target=${constraint.minutes} minutes, window=${windowSizeMinutes} minutes")
            }
            
            // Remove old records outside the time window
            records.removeAll { record -> 
                val recordEnd = record.endTime ?: now
                recordEnd < cutoffTime
            }
            
            // Check total matched time in the window
            val totalMinutes = records.sumOf { record ->
                val start = maxOf(record.startTime, cutoffTime)
                val end = record.endTime ?: now
                maxOf(0.0, (end - start) / (1000.0 * 60.0))
            }
            
            if (totalMinutes >= constraint.minutes) {
                actionExecutor.executeAction(rule, appName)
                // Clear records after triggering
                records.clear()
                Log.d("A11yService", "Short-term constraint met for rule ${rule.id} (matched ${totalMinutes} minutes in ${windowSizeMinutes} min window), triggered action")
                saveTimeConstraintStates()
            } else {
                // Save state when records change
                saveTimeConstraintStates()
            }
        } else {
            // End current record if any
            val hadActiveRecord = shortTermRecords[stateKey]?.any { it.endTime == null } == true
            endShortTermRecord(rule.id, appName)
            
            // Clean up old records outside the window
            val records = shortTermRecords[stateKey]
            val hadRecordsBeforeCleanup = records?.isNotEmpty() == true
            records?.removeAll { record -> 
                val recordEnd = record.endTime ?: now
                recordEnd < cutoffTime
            }
            
            // If all records are cleared and we're not matching, we can remove the entry
            if (records?.isEmpty() == true) {
                shortTermRecords.remove(stateKey)
            }
            
            // Save if state changed (record ended or records were cleaned up)
            if (hadActiveRecord || hadRecordsBeforeCleanup) {
                saveTimeConstraintStates()
            }
        }
    }
    
    private fun handleDailyTotalConstraint(
        rule: Rule,
        appName: String,
        constraint: TimeConstraint.DailyTotal,
        isMatch: Boolean
    ) {
        val stateKey = "${appName}_${rule.id}"
        val now = System.currentTimeMillis()
        
        if (isMatch) {
            // Start or continue recording
            val records = dailyTotalRecords.getOrPut(stateKey) { mutableListOf() }
            val activeRecord = records.find { it.endTime == null }
            
            if (activeRecord == null) {
                // Start new record
                records.add(TimeRecord(startTime = now))
                Log.d("A11yService", "Started daily total tracking for rule ${rule.id}")
            }
            
            // Check total time today
            val todayStart = GenericUtils.getTodayStartTime()
            val totalMinutes = records.sumOf { record ->
                val start = maxOf(record.startTime, todayStart)
                val end = record.endTime ?: now
                maxOf(0.0, (end - start) / (1000.0 * 60.0))
            }
            
            if (totalMinutes >= constraint.minutes) {
                actionExecutor.executeAction(rule, appName)
                // Clear today's records after triggering
                records.clear()
                Log.d("A11yService", "Daily total constraint met for rule ${rule.id}, triggered action")
                saveTimeConstraintStates()
            } else {
                // Save state when records change
                saveTimeConstraintStates()
            }
        } else {
            // End current record
            val records = dailyTotalRecords[stateKey]
            records?.find { it.endTime == null }?.let { record ->
                val index = records.indexOf(record)
                records[index] = record.copy(endTime = now)
                saveTimeConstraintStates()
            }
        }
    }
    
    private fun handleRecentTotalConstraint(
        rule: Rule,
        appName: String,
        constraint: TimeConstraint.RecentTotal,
        isMatch: Boolean
    ) {
        val stateKey = "${appName}_${rule.id}"
        val now = System.currentTimeMillis()
        val cutoffTime = now - (constraint.hours * 60 * 60 * 1000L)
        
        if (isMatch) {
            // Start or continue recording
            val records = recentTotalRecords.getOrPut(stateKey) { mutableListOf() }
            val activeRecord = records.find { it.endTime == null }
            
            if (activeRecord == null) {
                records.add(TimeRecord(startTime = now))
                Log.d("A11yService", "Started recent total tracking for rule ${rule.id}")
            }
            
            // Remove old records outside the time window
            records.removeAll { record -> record.endTime?.let { it < cutoffTime } == true }
            
            // Check total time in recent window
            val totalMinutes = records.sumOf { record ->
                val start = maxOf(record.startTime, cutoffTime)
                val end = record.endTime ?: now
                maxOf(0.0, (end - start) / (1000.0 * 60.0))
            }
            
            if (totalMinutes >= constraint.minutes) {
                actionExecutor.executeAction(rule, appName)
                // Clear records after triggering
                records.clear()
                Log.d("A11yService", "Recent total constraint met for rule ${rule.id}, triggered action")
                saveTimeConstraintStates()
            } else {
                // Save state when records change
                saveTimeConstraintStates()
            }
        } else {
            // End current record
            val records = recentTotalRecords[stateKey]
            records?.find { it.endTime == null }?.let { record ->
                val index = records.indexOf(record)
                records[index] = record.copy(endTime = now)
                saveTimeConstraintStates()
            }
            
            // Clean up old records
            records?.removeAll { record -> record.endTime?.let { it < cutoffTime } == true }
            if (records != null) {
                saveTimeConstraintStates()
            }
        }
    }
    
    fun endShortTermRecord(ruleId: String, appName: String) {
        val stateKey = "${appName}_${ruleId}"
        val now = System.currentTimeMillis()
        val records = shortTermRecords[stateKey]
        records?.find { it.endTime == null }?.let { record ->
            val index = records.indexOf(record)
            records[index] = record.copy(endTime = now)
            Log.d("A11yService", "Ended short-term record for rule $ruleId")
        }
        // Note: saveTimeConstraintStates() is called by the caller after cleanup
    }
    
    /**
     * Load time constraint states from persistence and clean expired ones
     */
    fun loadTimeConstraintStates() {
        val now = System.currentTimeMillis()
        val states = appDataStore.loadTimeConstraintStates()
        
        if (states.isEmpty()) {
            Log.d("A11yService", "No persisted time constraint states found")
            return
        }
        
        Log.d("A11yService", "Loading ${states.size} persisted time constraint states")
        
        val monitoringApps = appDataStore.loadMonitoringApps()
        val ruleMap = monitoringApps.flatMap { app ->
            app.rules.map { rule -> "${app.name}_${rule.id}" to Pair(app.name, rule) }
        }.toMap()
        
        var validCount = 0
        var expiredCount = 0
        
        states.forEach { stateData ->
            val (appName, _) = ruleMap[stateData.stateKey] ?: run {
                expiredCount++
                Log.d("A11yService", "Skipping expired state: ${stateData.stateKey} (rule not found)")
                return@forEach
            }
            
            val records = stateData.records.map { 
                TimeRecord(it.startTime, it.endTime) 
            }.toMutableList()
            var isValid = false
            
            when (stateData.constraintType) {
                "Continuous" -> {
                    val windowSizeMs = (stateData.constraintMinutes * 3.0 * 60 * 1000).toLong()
                    val cutoffTime = now - windowSizeMs
                    
                    // Clean expired records
                    records.removeAll { record ->
                        val recordEnd = record.endTime ?: now
                        recordEnd < cutoffTime
                    }
                    
                    if (records.isNotEmpty()) {
                        shortTermRecords[stateData.stateKey] = records
                        isValid = true
                        val totalMinutes = records.sumOf { record ->
                            val start = maxOf(record.startTime, cutoffTime)
                            val end = record.endTime ?: now
                            maxOf(0.0, (end - start) / (1000.0 * 60.0))
                        }
                        Log.d("A11yService", "Restored Continuous constraint: $appName/${stateData.ruleId}, matched ${String.format("%.2f", totalMinutes)}/${stateData.constraintMinutes} minutes in window")
                    }
                }
                "DailyTotal" -> {
                    val todayStart = GenericUtils.getTodayStartTime()
                    
                    // Clean records before today
                    records.removeAll { record ->
                        val recordEnd = record.endTime ?: now
                        recordEnd < todayStart
                    }
                    
                    // Adjust start times to today if needed
                    records.forEachIndexed { index, record ->
                        if (record.startTime < todayStart) {
                            records[index] = record.copy(startTime = todayStart)
                        }
                    }
                    
                    if (records.isNotEmpty()) {
                        dailyTotalRecords[stateData.stateKey] = records
                        isValid = true
                        val totalMinutes = records.sumOf { record ->
                            val start = maxOf(record.startTime, todayStart)
                            val end = record.endTime ?: now
                            maxOf(0.0, (end - start) / (1000.0 * 60.0))
                        }
                        Log.d("A11yService", "Restored DailyTotal constraint: $appName/${stateData.ruleId}, matched ${String.format("%.2f", totalMinutes)}/${stateData.constraintMinutes} minutes today")
                    }
                }
                "RecentTotal" -> {
                    val cutoffTime = now - (stateData.constraintHours!! * 60 * 60 * 1000L)
                    
                    // Clean expired records
                    records.removeAll { record ->
                        val recordEnd = record.endTime ?: now
                        recordEnd < cutoffTime
                    }
                    
                    // Adjust start times if needed
                    records.forEachIndexed { index, record ->
                        if (record.startTime < cutoffTime) {
                            records[index] = record.copy(startTime = cutoffTime)
                        }
                    }
                    
                    if (records.isNotEmpty()) {
                        recentTotalRecords[stateData.stateKey] = records
                        isValid = true
                        val totalMinutes = records.sumOf { record ->
                            val start = maxOf(record.startTime, cutoffTime)
                            val end = record.endTime ?: now
                            maxOf(0.0, (end - start) / (1000.0 * 60.0))
                        }
                        Log.d("A11yService", "Restored RecentTotal constraint: $appName/${stateData.ruleId}, matched ${String.format("%.2f", totalMinutes)}/${stateData.constraintMinutes} minutes in last ${stateData.constraintHours} hours")
                    }
                }
            }
            
            if (isValid) {
                validCount++
            } else {
                expiredCount++
            }
        }
        
        Log.d("A11yService", "Time constraint states loaded: $validCount valid, $expiredCount expired")
        
        // Save cleaned states back
        saveTimeConstraintStates()
    }

    /**
     * Save time constraint states to persistence
     */
    private fun saveTimeConstraintStates() {
        try {
            val monitoringApps = appDataStore.loadMonitoringApps()
            val rules = monitoringApps.flatMap { app ->
                app.rules.map { rule -> Pair(app.name, rule) }
            }
            
            // Convert TimeRecord to AppDataStore.TimeRecord
            val shortTermConverted = shortTermRecords.mapValues { (_, records) ->
                records.map { AppDataStore.TimeRecord(it.startTime, it.endTime) }.toMutableList()
            }
            val dailyTotalConverted = dailyTotalRecords.mapValues { (_, records) ->
                records.map { AppDataStore.TimeRecord(it.startTime, it.endTime) }.toMutableList()
            }
            val recentTotalConverted = recentTotalRecords.mapValues { (_, records) ->
                records.map { AppDataStore.TimeRecord(it.startTime, it.endTime) }.toMutableList()
            }
            
            appDataStore.saveTimeConstraintStates(
                shortTermConverted,
                dailyTotalConverted,
                recentTotalConverted,
                rules
            )
        } catch (e: Exception) {
            Log.e("A11yService", "Error saving time constraint states", e)
        }
    }
    
}

