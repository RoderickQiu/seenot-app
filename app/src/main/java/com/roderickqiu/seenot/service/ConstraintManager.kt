package com.roderickqiu.seenot.service

import android.util.Log
import com.roderickqiu.seenot.data.AppDataStore
import com.roderickqiu.seenot.data.Rule
import com.roderickqiu.seenot.data.TimeConstraint
import com.roderickqiu.seenot.utils.GenericUtils
import kotlin.jvm.Volatile

class ConstraintManager(
    private val appDataStore: AppDataStore,
    private val actionExecutor: ActionExecutor
) {
    @Volatile
    private var rulesEnabled = true

    // Rule-level enable/disable states (ruleId -> enabled)
    private val ruleEnabledStates = mutableMapOf<String, Boolean>()

    // State tracking for time constraints
    private val shortTermRecords = mutableMapOf<String, MutableList<TimeRecord>>()
    private val dailyTotalRecords = mutableMapOf<String, MutableList<TimeRecord>>()
    private val recentTotalRecords = mutableMapOf<String, MutableList<TimeRecord>>()
    
    data class TimeRecord(
        val startTime: Long,
        val endTime: Long? = null, // null means still active
        val leaveTime: Long? = null, // when temporarily left (for sub-linear decay)
        val executionTime: Long? = null, // when execution happened (for exponential decay)
        val initialDuration: Double? = null // duration before execution (for exponential decay)
    )

    fun setRulesEnabled(enabled: Boolean) {
        rulesEnabled = enabled
        Log.d("A11yService", "Rule execution ${if (enabled) "resumed" else "paused"}")
    }

    fun areRulesEnabled(): Boolean = rulesEnabled

    // Rule-level enable/disable management
    fun setRuleEnabled(ruleId: String, enabled: Boolean) {
        ruleEnabledStates[ruleId] = enabled
        Log.d("A11yService", "Rule $ruleId ${if (enabled) "enabled" else "disabled"}")
    }

    fun isRuleEnabled(ruleId: String): Boolean {
        return ruleEnabledStates.getOrDefault(ruleId, true) // Default to enabled
    }

    fun getAllRuleStates(): Map<String, Boolean> {
        return ruleEnabledStates.toMap()
    }

    fun setMultipleRuleStates(states: Map<String, Boolean>) {
        ruleEnabledStates.putAll(states)
        Log.d("A11yService", "Updated rule states: $states")
    }
    
    /**
     * Calculate effective time with sub-linear decay after leaving
     * Time progresses slower than linear (y=x) using inverse function
     */
    private fun calculateEffectiveTimeWithSubLinearDecay(
        startTime: Long,
        endTime: Long,
        leaveTime: Long?
    ): Double {
        if (leaveTime == null || endTime <= leaveTime) {
            // No decay needed, return normal time
            return (endTime - startTime) / (1000.0 * 60.0)
        }
        
        // Time before leaving (counted fully)
        val beforeLeaveMs = leaveTime - startTime
        val beforeLeaveMinutes = beforeLeaveMs / (1000.0 * 60.0)
        
        // Time after leaving (decay slower than linear)
        val afterLeaveMs = endTime - leaveTime
        val afterLeaveMinutes = afterLeaveMs / (1000.0 * 60.0)
        
        // Apply sub-linear decay: effective_time = actual_time / (1 + decay_rate * actual_time)
        // This makes time progress slower than y=x
        // Using decay rate of 0.05 per minute (makes 10 min = ~6.67 min effective)
        val decayRate = 0.05
        val effectiveAfterLeaveMinutes = afterLeaveMinutes / (1.0 + decayRate * afterLeaveMinutes)
        
        return beforeLeaveMinutes + effectiveAfterLeaveMinutes
    }
    
    /**
     * Calculate effective time with exponential decay after execution
     * Records decay exponentially instead of being cleared immediately
     */
    private fun calculateEffectiveTimeWithExponentialDecay(
        initialDuration: Double,
        executionTime: Long,
        now: Long
    ): Double {
        val elapsedSinceExecutionMs = now - executionTime
        val elapsedSinceExecutionMinutes = elapsedSinceExecutionMs / (1000.0 * 60.0)
        
        // Exponential decay: effective = initial * e^(-decay_rate * time)
        // Using decay rate of 0.7 per minute (half-life ~0.99 minutes)
        val decayRate = 0.7
        val decayFactor = Math.exp(-decayRate * elapsedSinceExecutionMinutes)
        
        return initialDuration * decayFactor
    }
    
    /**
     * Calculate effective minutes for a record considering all decay mechanisms
     */
    private fun calculateEffectiveMinutes(
        record: TimeRecord,
        cutoffTime: Long,
        now: Long
    ): Double {
        val start = maxOf(record.startTime, cutoffTime)
        val end = record.endTime ?: now
        
        // If execution happened, use exponential decay
        if (record.executionTime != null && record.initialDuration != null) {
            return calculateEffectiveTimeWithExponentialDecay(
                record.initialDuration,
                record.executionTime,
                now
            )
        }
        
        // Otherwise, use sub-linear decay if left
        return calculateEffectiveTimeWithSubLinearDecay(
            start,
            end,
            record.leaveTime
        )
    }

    fun handleTimeConstraint(rule: Rule, appName: String, isMatch: Boolean) {
        if (!areRulesEnabled()) return
        if (!isRuleEnabled(rule.id)) return // Check if rule is individually disabled
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
            } else if (activeRecord.leaveTime != null) {
                // Resume matching after leaving, clear leaveTime
                val index = records.indexOf(activeRecord)
                records[index] = activeRecord.copy(leaveTime = null)
            }
            
            // Remove old records outside the time window
            records.removeAll { record -> 
                val recordEnd = record.endTime ?: now
                recordEnd < cutoffTime
            }
            
            // Check total matched time in the window using effective time calculation
            val totalMinutes = records.sumOf { record ->
                calculateEffectiveMinutes(record, cutoffTime, now)
            }
            
            if (totalMinutes >= constraint.minutes) {
                actionExecutor.executeAction(rule, appName)
                // Mark records for exponential decay instead of clearing immediately
                records.forEachIndexed { index, record ->
                    if (record.executionTime == null) {
                        // Calculate initial duration before marking for decay
                        val start = maxOf(record.startTime, cutoffTime)
                        val end = record.endTime ?: now
                        val initialDuration = calculateEffectiveTimeWithSubLinearDecay(
                            start,
                            end,
                            record.leaveTime
                        )
                        records[index] = record.copy(
                            endTime = end, // Ensure endTime is set
                            executionTime = now,
                            initialDuration = initialDuration
                        )
                    }
                }
                Log.d("A11yService", "Short-term constraint met for rule ${rule.id} (matched ${totalMinutes} minutes in ${windowSizeMinutes} min window), triggered action, starting exponential decay")
                saveTimeConstraintStates()
            } else {
                // Log accumulated time and remaining time needed
                val remainingMinutes = constraint.minutes - totalMinutes
                Log.d("A11yService", "Constraint rule ${rule.id} (Continuous): accumulated ${String.format("%.2f", totalMinutes)} minutes, need ${String.format("%.2f", remainingMinutes)} more minutes to execute")
                // Save state when records change
                saveTimeConstraintStates()
            }
        } else {
            // End current record if any and mark leave time for sub-linear decay
            val hadActiveRecord = shortTermRecords[stateKey]?.any { it.endTime == null } == true
            val records = shortTermRecords[stateKey]
            records?.find { it.endTime == null }?.let { record ->
                val index = records.indexOf(record)
                records[index] = record.copy(
                    endTime = now,
                    leaveTime = now // Mark when we left for sub-linear decay
                )
                Log.d("A11yService", "Ended short-term record for rule ${rule.id}, marked leave time for sub-linear decay")
            }
            
            // Clean up old records outside the window and decayed records
            val hadRecordsBeforeCleanup = records?.isNotEmpty() == true
            records?.removeAll { record -> 
                val recordEnd = record.endTime ?: now
                // Remove if outside window
                if (recordEnd < cutoffTime) {
                    return@removeAll true
                }
                // Remove if exponentially decayed to near zero (less than 0.01 minutes)
                if (record.executionTime != null && record.initialDuration != null) {
                    val effectiveTime = calculateEffectiveTimeWithExponentialDecay(
                        record.initialDuration,
                        record.executionTime,
                        now
                    )
                    if (effectiveTime < 0.01) {
                        return@removeAll true
                    }
                }
                false
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
                // Log accumulated time and remaining time needed
                val remainingMinutes = constraint.minutes - totalMinutes
                Log.d("A11yService", "Constraint rule ${rule.id} (DailyTotal): accumulated ${String.format("%.2f", totalMinutes)} minutes, need ${String.format("%.2f", remainingMinutes)} more minutes to execute")
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
                // Log accumulated time and remaining time needed
                val remainingMinutes = constraint.minutes - totalMinutes
                Log.d("A11yService", "Constraint rule ${rule.id} (RecentTotal): accumulated ${String.format("%.2f", totalMinutes)} minutes, need ${String.format("%.2f", remainingMinutes)} more minutes to execute")
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
            records[index] = record.copy(
                endTime = now,
                leaveTime = now // Mark when we left for sub-linear decay
            )
            Log.d("A11yService", "Ended short-term record for rule $ruleId, marked leave time for sub-linear decay")
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
                TimeRecord(
                    it.startTime, 
                    it.endTime,
                    it.leaveTime,
                    it.executionTime,
                    it.initialDuration
                ) 
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
                            calculateEffectiveMinutes(record, cutoffTime, now)
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
                records.map { 
                    AppDataStore.TimeRecord(
                        it.startTime, 
                        it.endTime,
                        it.leaveTime,
                        it.executionTime,
                        it.initialDuration
                    ) 
                }.toMutableList()
            }
            val dailyTotalConverted = dailyTotalRecords.mapValues { (_, records) ->
                records.map { 
                    AppDataStore.TimeRecord(
                        it.startTime, 
                        it.endTime,
                        it.leaveTime,
                        it.executionTime,
                        it.initialDuration
                    ) 
                }.toMutableList()
            }
            val recentTotalConverted = recentTotalRecords.mapValues { (_, records) ->
                records.map { 
                    AppDataStore.TimeRecord(
                        it.startTime, 
                        it.endTime,
                        it.leaveTime,
                        it.executionTime,
                        it.initialDuration
                    ) 
                }.toMutableList()
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

    fun handleOnEnterRules(appName: String) {
        if (!areRulesEnabled()) return
        try {
            val monitoringApps = appDataStore.loadMonitoringApps()
            monitoringApps.find { it.name == appName && it.isEnabled } ?: return
        } catch (e: Exception) {
            Log.e("A11yService", "Failed to handle ON_ENTER rules for $appName", e)
        }
    }

    /**
     * Clear all ongoing judgments and execution for a given app when it exits
     */
    fun clearAppJudgmentsAndExecution(appName: String) {
        try {
            val monitoringApps = appDataStore.loadMonitoringApps()
            val targetApp = monitoringApps.find { it.name == appName } ?: return

            var clearedCount = 0

            // Clear all records for all rules of this app
            targetApp.rules.forEach { rule ->
                val stateKey = "${appName}_${rule.id}"

                // Clear short-term records
                if (shortTermRecords.remove(stateKey) != null) {
                    clearedCount++
                }

                // Clear daily total records
                if (dailyTotalRecords.remove(stateKey) != null) {
                    clearedCount++
                }

                // Clear recent total records
                if (recentTotalRecords.remove(stateKey) != null) {
                    clearedCount++
                }
            }

            if (clearedCount > 0) {
                Log.d("A11yService", "Cleared all ongoing judgments and execution for app: $appName ($clearedCount state keys)")
                saveTimeConstraintStates()
            }
        } catch (e: Exception) {
            Log.e("A11yService", "Failed to clear judgments and execution for $appName", e)
        }
    }
}

