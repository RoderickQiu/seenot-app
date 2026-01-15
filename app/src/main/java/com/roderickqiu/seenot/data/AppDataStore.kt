package com.roderickqiu.seenot.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.roderickqiu.seenot.data.MonitoringApp

class AppDataStore(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("seenot_rules", Context.MODE_PRIVATE)
    
    private val gson: Gson = GsonBuilder()
        .registerTypeAdapter(TimeConstraint::class.java, TimeConstraintAdapter())
        .create()

    companion object {
        private const val KEY_MONITORING_APPS = "monitoring_apps"
        private const val KEY_IS_FIRST_LAUNCH = "is_first_launch"
        private const val KEY_TIME_CONSTRAINT_STATES = "time_constraint_states"
    }

    /**
     * Save monitoring apps to SharedPreferences
     */
    fun saveMonitoringApps(apps: List<MonitoringApp>) {
        try {
            val json = gson.toJson(apps)
            prefs.edit { putString(KEY_MONITORING_APPS, json) }
        } catch (e: Exception) {
            Log.e("AppDataStore", "Error saving monitoring apps", e)
            throw e
        }
    }

    /**
     * Load monitoring apps from SharedPreferences
     */
    fun loadMonitoringApps(): List<MonitoringApp> {
        val json = prefs.getString(KEY_MONITORING_APPS, null) ?: return emptyList()
        val type = object : TypeToken<List<MonitoringApp>>() {}.type
        return try {
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            Log.e("AppDataStore", "Error loading monitoring apps: ${e.message}", e)
            Log.e("AppDataStore", "JSON content: $json")
            emptyList()
        }
    }

    /**
     * Check if this is the first launch
     */
    fun isFirstLaunch(): Boolean {
        return prefs.getBoolean(KEY_IS_FIRST_LAUNCH, true)
    }

    /**
     * Mark that the app has been launched before
     */
    fun markAsLaunched() {
        prefs.edit { putBoolean(KEY_IS_FIRST_LAUNCH, false) }
    }

    /**
     * Delete a specific monitoring app
     */
    fun deleteMonitoringApp(appId: String) {
        val apps = loadMonitoringApps().toMutableList()
        apps.removeAll { it.id == appId }
        saveMonitoringApps(apps)
    }

    /**
     * Delete a specific rule from a monitoring app
     */
    fun deleteRule(appId: String, ruleId: String) {
        val apps = loadMonitoringApps().toMutableList()
        val appIndex = apps.indexOfFirst { it.id == appId }
        if (appIndex != -1) {
            val app = apps[appIndex]
            val updatedRules = app.rules.filter { it.id != ruleId }
            apps[appIndex] = app.copy(rules = updatedRules)
            saveMonitoringApps(apps)
        }
    }

    /**
     * Update a monitoring app
     */
    fun updateMonitoringApp(updatedApp: MonitoringApp) {
        val apps = loadMonitoringApps().toMutableList()
        val appIndex = apps.indexOfFirst { it.id == updatedApp.id }
        if (appIndex != -1) {
            apps[appIndex] = updatedApp
        } else {
            apps.add(updatedApp)
        }
        saveMonitoringApps(apps)
    }

    /**
     * Data class for persisting time constraint state with constraint info
     */
    data class TimeConstraintStateData(
        val stateKey: String, // "${appName}_${ruleId}"
        val appName: String,
        val ruleId: String,
        val constraintType: String, // "Continuous", "DailyTotal", "RecentTotal"
        val constraintMinutes: Double,
        val constraintHours: Int? = null, // Only for RecentTotal
        val records: List<TimeRecord>
    )

    /**
     * Data class for TimeRecord (must be in data package for Gson serialization)
     */
    data class TimeRecord(
        val startTime: Long,
        val endTime: Long? = null, // null means still active
        val leaveTime: Long? = null, // when temporarily left (for sub-linear decay)
        val executionTime: Long? = null, // when execution happened (for exponential decay)
        val initialDuration: Double? = null // duration before execution (for exponential decay)
    )

    /**
     * Save all time constraint states
     */
    fun saveTimeConstraintStates(
        shortTerm: Map<String, MutableList<TimeRecord>>,
        dailyTotal: Map<String, MutableList<TimeRecord>>,
        recentTotal: Map<String, MutableList<TimeRecord>>,
        rules: List<Pair<String, Rule>> // appName to Rule mapping
    ) {
        try {
            val states = mutableListOf<TimeConstraintStateData>()
            
            // Build a map of stateKey -> (appName, rule) for lookup
            val ruleMap = rules.associate { (appName, rule) ->
                "${appName}_${rule.id}" to Pair(appName, rule)
            }
            
            // Process short-term records
            shortTerm.forEach { (stateKey, recordList) ->
                val (appName, rule) = ruleMap[stateKey] ?: return@forEach
                val constraint = rule.timeConstraint as? TimeConstraint.Continuous ?: return@forEach
                states.add(TimeConstraintStateData(
                    stateKey = stateKey,
                    appName = appName,
                    ruleId = rule.id,
                    constraintType = "Continuous",
                    constraintMinutes = constraint.minutes,
                    constraintHours = null,
                    records = recordList.map { 
                        TimeRecord(
                            it.startTime, 
                            it.endTime,
                            it.leaveTime,
                            it.executionTime,
                            it.initialDuration
                        ) 
                    }
                ))
            }
            
            // Process daily total records
            dailyTotal.forEach { (stateKey, recordList) ->
                val (appName, rule) = ruleMap[stateKey] ?: return@forEach
                val constraint = rule.timeConstraint as? TimeConstraint.DailyTotal ?: return@forEach
                states.add(TimeConstraintStateData(
                    stateKey = stateKey,
                    appName = appName,
                    ruleId = rule.id,
                    constraintType = "DailyTotal",
                    constraintMinutes = constraint.minutes,
                    constraintHours = null,
                    records = recordList.map { 
                        TimeRecord(
                            it.startTime, 
                            it.endTime,
                            it.leaveTime,
                            it.executionTime,
                            it.initialDuration
                        ) 
                    }
                ))
            }
            
            // Process recent total records
            recentTotal.forEach { (stateKey, recordList) ->
                val (appName, rule) = ruleMap[stateKey] ?: return@forEach
                val constraint = rule.timeConstraint as? TimeConstraint.RecentTotal ?: return@forEach
                states.add(TimeConstraintStateData(
                    stateKey = stateKey,
                    appName = appName,
                    ruleId = rule.id,
                    constraintType = "RecentTotal",
                    constraintMinutes = constraint.minutes,
                    constraintHours = constraint.hours,
                    records = recordList.map { 
                        TimeRecord(
                            it.startTime, 
                            it.endTime,
                            it.leaveTime,
                            it.executionTime,
                            it.initialDuration
                        ) 
                    }
                ))
            }
            
            val json = gson.toJson(states)
            prefs.edit { putString(KEY_TIME_CONSTRAINT_STATES, json) }
        } catch (e: Exception) {
            Log.e("AppDataStore", "Error saving time constraint states", e)
        }
    }

    /**
     * Load all time constraint states
     */
    fun loadTimeConstraintStates(): List<TimeConstraintStateData> {
        val json = prefs.getString(KEY_TIME_CONSTRAINT_STATES, null) ?: return emptyList()
        val type = object : TypeToken<List<TimeConstraintStateData>>() {}.type
        return try {
            gson.fromJson<List<TimeConstraintStateData>>(json, type) ?: emptyList()
        } catch (e: Exception) {
            Log.e("AppDataStore", "Error loading time constraint states: ${e.message}", e)
            emptyList()
        }
    }
}
