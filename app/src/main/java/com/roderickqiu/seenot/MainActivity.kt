package com.roderickqiu.seenot

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.google.gson.Gson
import com.google.gson.GsonBuilder
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roderickqiu.seenot.R
import com.roderickqiu.seenot.components.AboutDialog
import com.roderickqiu.seenot.components.AddAppDialog
import com.roderickqiu.seenot.components.ImportExportDialog
import com.roderickqiu.seenot.components.MonitoringAppItem
import com.roderickqiu.seenot.components.PermissionBanner
import com.roderickqiu.seenot.components.UnifiedEditDialog
import com.roderickqiu.seenot.components.RuleRecordsPage
import com.roderickqiu.seenot.data.ActionType
import com.roderickqiu.seenot.data.ConditionType
import com.roderickqiu.seenot.data.MonitoringApp
import com.roderickqiu.seenot.data.MonitoringRepo
import com.roderickqiu.seenot.data.Rule
import com.roderickqiu.seenot.data.RuleAction
import com.roderickqiu.seenot.data.RuleCondition
import com.roderickqiu.seenot.data.RuleRecordRepo
import com.roderickqiu.seenot.ui.theme.SeeNotTheme
import com.roderickqiu.seenot.settings.SettingsDialog
import com.roderickqiu.seenot.utils.LanguageManager

class MainActivity : ComponentActivity() {
    private lateinit var repository: MonitoringRepo

    /**
     * Share all rules as JSON text
     */
    private fun exportRules() {
        try {
            val monitoringApps = repository.getAllApps()

            // Create Gson with custom adapter for TimeConstraint
            val gson = GsonBuilder()
                .registerTypeAdapter(com.roderickqiu.seenot.data.TimeConstraint::class.java,
                    com.roderickqiu.seenot.data.TimeConstraintAdapter())
                .setPrettyPrinting()
                .create()

            val jsonString = gson.toJson(monitoringApps)

            // Create share intent
            val shareIntent = Intent().apply {
                action = Intent.ACTION_SEND
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, jsonString)
                putExtra(Intent.EXTRA_SUBJECT, "SeeNot 规则导出")
            }

            // Start share activity
            startActivity(Intent.createChooser(shareIntent, "分享规则"))
        } catch (e: Exception) {
            Toast.makeText(this, "分享失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Import rules from JSON text and replace all current apps
     */
    private fun importRules(jsonText: String, onImportComplete: () -> Unit) {
        try {
            // Create Gson with custom adapter for TimeConstraint
            val gson = GsonBuilder()
                .registerTypeAdapter(com.roderickqiu.seenot.data.TimeConstraint::class.java,
                    com.roderickqiu.seenot.data.TimeConstraintAdapter())
                .create()

            // Parse JSON to list of MonitoringApp
            val importedApps = gson.fromJson(jsonText, Array<com.roderickqiu.seenot.data.MonitoringApp>::class.java)
                ?.toList() ?: emptyList()

            // Validate that we have valid apps
            if (importedApps.isEmpty()) {
                Toast.makeText(this, getString(R.string.invalid_json_format), Toast.LENGTH_LONG).show()
                return
            }

            // Replace all apps in data store
            val dataStore = com.roderickqiu.seenot.data.AppDataStore(this)
            dataStore.saveMonitoringApps(importedApps)

            Toast.makeText(this, getString(R.string.rules_imported_successfully), Toast.LENGTH_LONG).show()

            // Call the callback to refresh UI
            onImportComplete()
        } catch (e: Exception) {
            Toast.makeText(this, "${getString(R.string.invalid_json_format)}: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Apply saved language setting
        LanguageManager.updateConfiguration(this)
        enableEdgeToEdge()

        repository = MonitoringRepo(this)

        setContent {
            SeeNotTheme {
                val context = LocalContext.current
                var monitoringApps by remember { mutableStateOf(repository.getAllApps()) }
                var showAddAppDialog by remember { mutableStateOf(false) }
                var showTopMenu by remember { mutableStateOf(false) }
                var showPermissionSettings by remember { mutableStateOf(false) }
                var showAiSettings by remember { mutableStateOf(false) }
                var showAboutDialog by remember { mutableStateOf(false) }
                var showImportExportDialog by remember { mutableStateOf(false) }
                var showRuleRecordsPage by remember { mutableStateOf(false) }
                var permissionRefreshKey by remember { mutableStateOf(0) }
                var bannerRefreshKey by remember { mutableStateOf(0) }
                var previousShowPermissionSettings by remember { mutableStateOf(false) }
                
                // Refresh banner when returning from permission settings
                LaunchedEffect(showPermissionSettings) {
                    // Only refresh when transitioning from true to false (returning from settings)
                    if (previousShowPermissionSettings && !showPermissionSettings) {
                        bannerRefreshKey++
                    }
                    previousShowPermissionSettings = showPermissionSettings
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        if (showRuleRecordsPage) {
                            CenterAlignedTopAppBar(
                                title = { Text(context.getString(R.string.rule_records)) },
                                navigationIcon = {
                                    IconButton(onClick = { showRuleRecordsPage = false }) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = context.getString(R.string.back))
                                    }
                                }
                            )
                        } else if (showPermissionSettings) {
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
                                            DropdownMenuItem(
                                                text = { Text(text = context.getString(R.string.ai_settings)) },
                                                onClick = {
                                                    showTopMenu = false
                                                    showAiSettings = true
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text(text = context.getString(R.string.import_export_rules)) },
                                                onClick = {
                                                    showTopMenu = false
                                                    showImportExportDialog = true
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text(text = context.getString(R.string.rule_records_menu)) },
                                                onClick = {
                                                    showTopMenu = false
                                                    showRuleRecordsPage = true
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text(text = context.getString(R.string.about)) },
                                                onClick = {
                                                    showTopMenu = false
                                                    showAboutDialog = true
                                                }
                                            )
                                        }
                                    }
                                }
                            )
                        }
                    },
                    floatingActionButton = {
                        if (showRuleRecordsPage) {
                            // Rule Records page doesn't need FAB
                        } else if (showPermissionSettings) {
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
                    if (showRuleRecordsPage) {
                        RuleRecordsPage(
                            modifier = Modifier.padding(innerPadding)
                        )
                    } else if (showPermissionSettings) {
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
                            PermissionBanner(
                                modifier = Modifier.padding(vertical = 16.dp),
                                onClick = {
                                    showPermissionSettings = true
                                },
                                refreshSignal = bannerRefreshKey
                            )
                            
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

                if (showAiSettings) {
                    SettingsDialog(
                        onDismiss = { showAiSettings = false },
                        onLanguageChanged = {
                            // Language change will trigger activity recreation
                            LanguageManager.applyLanguage(this@MainActivity, LanguageManager.getSavedLanguage(this@MainActivity))
                        }
                    )
                }

                if (showImportExportDialog) {
                    ImportExportDialog(
                        onDismiss = { showImportExportDialog = false },
                        onExport = { exportRules() },
                        onImport = { jsonText ->
                            importRules(jsonText) {
                                // Refresh monitoring apps after import
                                monitoringApps = repository.getAllApps()
                            }
                            showImportExportDialog = false
                        }
                    )
                }

                if (showAboutDialog) {
                    AboutDialog(
                        onDismiss = { showAboutDialog = false },
                        versionName = packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0",
                        versionCode = packageManager.getPackageInfo(packageName, 0).longVersionCode.toInt()
                    )
                }
            }
        }
    }
}