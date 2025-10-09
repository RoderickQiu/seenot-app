package com.roderickqiu.seenot.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.roderickqiu.seenot.MonitoringApp

class AppDataStore(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("seenot_rules", Context.MODE_PRIVATE)
    private val gson = Gson()

    companion object {
        private const val KEY_MONITORING_APPS = "monitoring_apps"
        private const val KEY_IS_FIRST_LAUNCH = "is_first_launch"
    }

    /**
     * Save monitoring apps to SharedPreferences
     */
    fun saveMonitoringApps(apps: List<MonitoringApp>) {
        val json = gson.toJson(apps)
        prefs.edit { putString(KEY_MONITORING_APPS, json) }
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
}
