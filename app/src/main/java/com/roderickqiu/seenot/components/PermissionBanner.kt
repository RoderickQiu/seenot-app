package com.roderickqiu.seenot.components

import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.roderickqiu.seenot.R

@Composable
fun PermissionBanner(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    refreshSignal: Int = 0
) {
    val context = LocalContext.current
    var isAccessibilityGranted by remember { mutableStateOf(false) }

    LaunchedEffect(Unit, refreshSignal) {
        isAccessibilityGranted = isAccessibilitySettingsOn(context)
    }

    // Only show banner if permission is not granted
    if (!isAccessibilityGranted) {
        val colorScheme = MaterialTheme.colorScheme
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
            shape = RoundedCornerShape(12.dp),
            color = colorScheme.errorContainer
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    Column {
                        Text(
                            text = context.getString(R.string.permissions_required),
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold
                            ),
                            color = colorScheme.onErrorContainer
                        )
                        Text(
                            text = context.getString(R.string.accessibility_service_required),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colorScheme.onErrorContainer,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
                Text(
                    text = context.getString(R.string.grant_permission),
                    style = MaterialTheme.typography.labelLarge,
                    color = colorScheme.onSurface,
                    modifier = Modifier.padding(start = 16.dp)
                )
            }
        }
    }
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

