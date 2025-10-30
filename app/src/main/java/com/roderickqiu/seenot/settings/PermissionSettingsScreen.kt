package com.roderickqiu.seenot.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.roderickqiu.seenot.R

@Composable
fun PermissionSettingsScreen(modifier: Modifier = Modifier, refreshSignal: Int = 0) {
    val context = LocalContext.current

    var notifGranted by remember { mutableStateOf(false) }
    var overlayGranted by remember { mutableStateOf(false) }
    var batteryGranted by remember { mutableStateOf(false) }
    var accessibilityGranted by remember { mutableStateOf(false) }

    LaunchedEffect(Unit, refreshSignal) {
        notifGranted = areNotificationsEnabled(context)
        overlayGranted = Settings.canDrawOverlays(context)
        batteryGranted = isIgnoringBatteryOptimizations(context)
        accessibilityGranted = isAccessibilitySettingsOn(context)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        PermissionRow(
            title = context.getString(R.string.permission_notifications),
            granted = notifGranted,
            onClick = {
                val intent = Intent().apply {
                    action = "android.settings.APP_NOTIFICATION_SETTINGS"
                    putExtra("android.provider.extra.APP_PACKAGE", context.packageName)
                }
                context.startActivity(intent)
            }
        )

        PermissionRow(
            title = context.getString(R.string.permission_overlay),
            granted = overlayGranted,
            onClick = {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + context.packageName)
                )
                context.startActivity(intent)
            }
        )

        PermissionRow(
            title = context.getString(R.string.permission_battery_optimizations),
            granted = batteryGranted,
            onClick = {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:" + context.packageName)
                }
                context.startActivity(intent)
            }
        )

        PermissionRow(
            title = context.getString(R.string.permission_accessibility),
            granted = accessibilityGranted,
            onClick = {
                try {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } catch (_: Exception) {
                    context.startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            }
        )

        PermissionRow(
            title = context.getString(R.string.permission_background_autostart),
            granted = false,
            subtitle = context.getString(R.string.permission_background_note),
            onClick = {
                val url = "https://keep-alive.pages.dev/#" + android.os.Build.MANUFACTURER
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                context.startActivity(intent)
            }
        )
    }
}

@Composable
private fun PermissionRow(
    title: String,
    granted: Boolean,
    subtitle: String? = null,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(6.dp))
                StatusPill(
                    text = subtitle ?: if (granted) context.getString(R.string.status_granted) else context.getString(R.string.status_not_granted),
                    positive = granted
                )
            }

            Button(
                onClick = onClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text(text = context.getString(R.string.open_settings))
            }
        }
    }
}

@Composable
private fun StatusPill(text: String, positive: Boolean) {
    val containerColor = if (positive) {
        Color(0xFF2E7D32) // success green
    } else {
        MaterialTheme.colorScheme.error
    }
    val contentColor = if (positive) {
        Color.White
    } else {
        MaterialTheme.colorScheme.onError
    }

    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(999.dp)
    ) {
        Text(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            text = text,
            style = MaterialTheme.typography.labelMedium
        )
    }
}

private fun areNotificationsEnabled(context: Context): Boolean {
    val nm = androidx.core.app.NotificationManagerCompat.from(context)
    return nm.areNotificationsEnabled()
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

private fun isAccessibilitySettingsOn(context: Context): Boolean {
    return try {
        val enabled = Settings.Secure.getInt(
            context.applicationContext.contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED
        )
        if (enabled == 1) {
            val settingValue = Settings.Secure.getString(
                context.applicationContext.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            if (settingValue != null) {
                val splitter = TextUtils.SimpleStringSplitter(':')
                splitter.setString(settingValue)
                while (splitter.hasNext()) {
                    val service = splitter.next()
                    if (service.contains(context.packageName)) return true
                }
            }
            false
        } else {
            false
        }
    } catch (_: Settings.SettingNotFoundException) {
        false
    }
}

