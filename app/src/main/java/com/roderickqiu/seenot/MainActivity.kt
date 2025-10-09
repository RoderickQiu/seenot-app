package com.roderickqiu.seenot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.roderickqiu.seenot.R
import com.roderickqiu.seenot.data.ActionType
import com.roderickqiu.seenot.data.ConditionType
import com.roderickqiu.seenot.data.MonitoringAppRepository
import com.roderickqiu.seenot.data.Rule
import com.roderickqiu.seenot.data.RuleAction
import com.roderickqiu.seenot.data.RuleCondition
import com.roderickqiu.seenot.ui.theme.SeeNotTheme
import com.roderickqiu.seenot.ui.theme.YellowGrey40
import com.roderickqiu.seenot.ui.theme.YellowGrey80
import com.roderickqiu.seenot.utils.InstalledAppHelper
import com.roderickqiu.seenot.utils.InstalledApp
import com.roderickqiu.seenot.utils.RuleFormatter

data class MonitoringApp(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val isEnabled: Boolean = true,
    val rules: List<Rule>
)


class MainActivity : ComponentActivity() {
    private lateinit var repository: MonitoringAppRepository

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        repository = MonitoringAppRepository(this)

        setContent {
            SeeNotTheme {
                val context = LocalContext.current
                var monitoringApps by remember { mutableStateOf(repository.getAllApps()) }
                var showAddAppDialog by remember { mutableStateOf(false) }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        CenterAlignedTopAppBar(
                            title = {
                                Text("SeeNot")
                            }
                        )
                    },
                    floatingActionButton = {
                        FloatingActionButton(
                            onClick = { showAddAppDialog = true }
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = context.getString(R.string.add_app)
                            )
                        }
                    }
                ) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .padding(horizontal = 16.dp)
                    ) {
                        Text(
                            text = context.getString(R.string.monitoring_software_list),
                            fontSize = 16.sp,
                            modifier = Modifier.padding(vertical = 16.dp)
                        )

                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(bottom = 80.dp)
                        ) {
                            items(monitoringApps) { app ->
                                MonitoringAppItem(
                                    app = app,
                                    onDeleteApp = { appId ->
                                        repository.deleteApp(appId)
                                        monitoringApps = repository.getAllApps()
                                    },
                                    onEditApp = { updatedApp ->
                                        repository.updateApp(updatedApp)
                                        monitoringApps = repository.getAllApps()
                                    }
                                )
                            }
                        }
                    }
                }
                
                // Add App Dialog
                if (showAddAppDialog) {
                    AddAppDialog(
                        onDismiss = { showAddAppDialog = false },
                        onAddApp = { appName ->
                            val newApp = MonitoringApp(
                                name = appName,
                                rules = listOf(
                                    Rule(
                                        condition = RuleCondition(
                                            type = ConditionType.ON_ENTER
                                        ),
                                        action = RuleAction(
                                            type = ActionType.ASK
                                        )
                                    )
                                )
                            )
                            repository.addApp(newApp)
                            monitoringApps = repository.getAllApps()
                            showAddAppDialog = false
                        },
                        existingApps = monitoringApps
                    )
                }
            }
        }
    }
}

@Composable
fun MonitoringAppItem(
    app: MonitoringApp,
    onDeleteApp: (String) -> Unit,
    onEditApp: (MonitoringApp) -> Unit
) {
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    val isDarkTheme = isSystemInDarkTheme()
    var showMenu by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var isEnabled by remember { mutableStateOf(app.isEnabled) }

    // Unified color scheme
    val iconColor = if (app.isEnabled) {
        if (isDarkTheme) YellowGrey80 else YellowGrey40
    } else {
        colorScheme.outline.copy(alpha = 0.6f) // Gray color for disabled state
    }

    val textColor = if (app.isEnabled) {
        Color.White
    } else {
        colorScheme.onSurface.copy(alpha = 0.6f)
    }

    // Auto-generate icon (take first character of app name)
    val icon = app.name.take(1)

    // Auto-add status suffix
    val displayName = if (!app.isEnabled) {
        "${app.name} ${context.getString(R.string.status_disabled)}"
    } else {
        app.name
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // App icon
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(iconColor),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = icon,
                    color = textColor,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            // App info
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = displayName,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (app.isEnabled) colorScheme.onSurface else colorScheme.onSurface.copy(
                        alpha = 0.6f
                    )
                )

                Spacer(modifier = Modifier.height(4.dp))

                app.rules.forEach { rule ->
                    Text(
                        text = RuleFormatter.formatRule(context, rule),
                        fontSize = 14.sp,
                        color = colorScheme.onSurface.copy(alpha = 0.7f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                    )
                }
            }

            // More options dropdown menu
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = context.getString(R.string.more_options),
                        tint = colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(context.getString(R.string.edit)) },
                        onClick = {
                            showMenu = false
                            showEditDialog = true
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = null
                            )
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(context.getString(R.string.delete)) },
                        onClick = {
                            showMenu = false
                            onDeleteApp(app.id)
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null
                            )
                        }
                    )
                }
            }
        }
    }

    // Edit Dialog
    if (showEditDialog) {
        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            title = { Text("${context.getString(R.string.edit_app)} ${app.name}") },
            text = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(context.getString(R.string.enabled_status))
                    Switch(
                        checked = isEnabled,
                        onCheckedChange = { isEnabled = it }
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val updatedApp = app.copy(isEnabled = isEnabled)
                        onEditApp(updatedApp)
                        showEditDialog = false
                    }
                ) {
                    Text(context.getString(R.string.save))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        isEnabled = app.isEnabled // Reset to original value
                        showEditDialog = false
                    }
                ) {
                    Text(context.getString(R.string.cancel))
                }
            }
        )
    }
}

@Composable
fun AddAppDialog(
    onDismiss: () -> Unit,
    onAddApp: (String) -> Unit,
    existingApps: List<MonitoringApp>
) {
    val context = LocalContext.current
    var installedApps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var selectedApp by remember { mutableStateOf<InstalledApp?>(null) }
    var showAppList by remember { mutableStateOf(false) }
    
    LaunchedEffect(Unit) {
        val allInstalledApps = InstalledAppHelper.getInstalledApps(context)
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