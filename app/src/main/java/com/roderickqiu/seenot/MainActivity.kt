package com.roderickqiu.seenot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roderickqiu.seenot.R
import com.roderickqiu.seenot.components.AddAppDialog
import com.roderickqiu.seenot.components.MonitoringAppItem
import com.roderickqiu.seenot.components.UnifiedEditDialog
import com.roderickqiu.seenot.data.ActionType
import com.roderickqiu.seenot.data.ConditionType
import com.roderickqiu.seenot.data.MonitoringApp
import com.roderickqiu.seenot.data.MonitoringAppRepository
import com.roderickqiu.seenot.data.Rule
import com.roderickqiu.seenot.data.RuleAction
import com.roderickqiu.seenot.data.RuleCondition
import com.roderickqiu.seenot.ui.theme.SeeNotTheme

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
                var showTopMenu by remember { mutableStateOf(false) }
                var showPermissionSettings by remember { mutableStateOf(false) }
                var permissionRefreshKey by remember { mutableStateOf(0) }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        if (showPermissionSettings) {
                            CenterAlignedTopAppBar(
                                title = { Text(context.getString(R.string.permission_settings)) },
                                navigationIcon = {
                                    IconButton(onClick = { showPermissionSettings = false }) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = context.getString(R.string.back))
                                    }
                                }
                            )
                        } else {
                            CenterAlignedTopAppBar(
                                title = { Text("SeeNot") },
                                navigationIcon = {
                                    // Left-side hamburger menu opening dropdown
                                    androidx.compose.foundation.layout.Box {
                                        IconButton(onClick = { showTopMenu = true }) {
                                            Icon(Icons.Default.Menu, contentDescription = context.getString(R.string.more_options))
                                        }
                                        DropdownMenu(
                                            expanded = showTopMenu,
                                            onDismissRequest = { showTopMenu = false }
                                        ) {
                                            DropdownMenuItem(
                                                text = { Text(text = context.getString(R.string.permission_settings)) },
                                                onClick = {
                                                    showTopMenu = false
                                                    showPermissionSettings = true
                                                }
                                            )
                                        }
                                    }
                                }
                            )
                        }
                    },
                    floatingActionButton = {
                        if (showPermissionSettings) {
                            androidx.compose.material3.ExtendedFloatingActionButton(
                                onClick = { permissionRefreshKey++ },
                                icon = { Icon(Icons.Default.Refresh, contentDescription = context.getString(R.string.refresh_status)) },
                                text = { Text(text = context.getString(R.string.refresh_status)) }
                            )
                        } else {
                            FloatingActionButton(
                                onClick = { showAddAppDialog = true }
                            ) {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = context.getString(R.string.add_app)
                                )
                            }
                        }
                    }
                ) { innerPadding ->
                    if (showPermissionSettings) {
                        com.roderickqiu.seenot.settings.PermissionSettingsScreen(
                            modifier = Modifier.padding(innerPadding),
                            refreshSignal = permissionRefreshKey
                        )
                    } else {
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