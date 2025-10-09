package com.roderickqiu.seenot.utils

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

data class InstalledApp(
    val packageName: String,
    val appName: String,
    val icon: Drawable?
)

object InstalledAppHelper {
    
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
}
