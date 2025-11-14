package com.roderickqiu.seenot.utils

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import java.util.Calendar

data class InstalledApp(
    val packageName: String,
    val appName: String,
    val icon: Drawable?
)

object GenericUtils {
    
    /**
     * Get all installed apps on the device
     */
    fun getInstalledApps(context: Context): List<InstalledApp> {
        val packageManager = context.packageManager
        val installedPackages = packageManager.getInstalledPackages(PackageManager.GET_META_DATA)
        
        return installedPackages
            .filter { packageInfo ->
                // Filter out system apps and only include user-installed apps
                val appInfo = packageInfo.applicationInfo
                appInfo != null && (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) == 0
            }
            .map { packageInfo ->
                val appInfo = packageInfo.applicationInfo!!
                InstalledApp(
                    packageName = packageInfo.packageName,
                    appName = packageManager.getApplicationLabel(appInfo).toString(),
                    icon = try {
                        packageManager.getApplicationIcon(appInfo)
                    } catch (e: Exception) {
                        null
                    }
                )
            }
            .sortedBy { it.appName }
    }
    
    /**
     * Get today's start time (00:00:00) in milliseconds
     */
    fun getTodayStartTime(): Long {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }
}

