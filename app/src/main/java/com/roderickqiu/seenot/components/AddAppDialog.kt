package com.roderickqiu.seenot.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roderickqiu.seenot.R
import com.roderickqiu.seenot.data.MonitoringApp
import com.roderickqiu.seenot.utils.GenericUtils
import com.roderickqiu.seenot.utils.InstalledApp

@Composable
fun AddAppDialog(
    onDismiss: () -> Unit,
    onAddApp: (String) -> Unit,
    existingApps: List<MonitoringApp>
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var installedApps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var selectedApp by remember { mutableStateOf<InstalledApp?>(null) }
    var showAppList by remember { mutableStateOf(false) }
    
    LaunchedEffect(Unit) {
        val allInstalledApps = GenericUtils.getInstalledApps(context)
        val existingAppNames = existingApps.map { it.name }.toSet()
        
        // Filter out SeeNot app and already added apps
        installedApps = allInstalledApps.filter { app ->
            app.appName != "SeeNot" && !existingAppNames.contains(app.appName)
        }
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(context.getString(R.string.add_new_app)) },
        text = {
            Column {
                Text(
                    text = context.getString(R.string.select_app),
                    fontSize = 16.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                
                if (installedApps.isEmpty()) {
                    Text(
                        text = context.getString(R.string.no_apps_found),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                } else {
                    OutlinedButton(
                        onClick = { showAppList = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = selectedApp?.appName ?: context.getString(R.string.select_app),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    
                    if (showAppList) {
                        LazyColumn(
                            modifier = Modifier
                                .height(200.dp)
                                .fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(installedApps) { app ->
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick = {
                                        selectedApp = app
                                        showAppList = false
                                    }
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Default.Add,
                                            contentDescription = null,
                                            modifier = Modifier.size(24.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = app.appName,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    selectedApp?.let { app ->
                        onAddApp(app.appName)
                    }
                },
                enabled = selectedApp != null
            ) {
                Text(context.getString(R.string.add_empty_rule))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(context.getString(R.string.cancel))
            }
        }
    )
}
