package com.roderickqiu.seenot

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.roderickqiu.seenot.components.ToastOverlay
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.roderickqiu.seenot.data.LabelNormalizationRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
import com.roderickqiu.seenot.components.LabelNormPage
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
import com.roderickqiu.seenot.settings.RuleRecordingSettingsDialog
import com.roderickqiu.seenot.settings.SettingsDialog
import com.roderickqiu.seenot.utils.LanguageManager
import com.roderickqiu.seenot.utils.Logger

class MainActivity : ComponentActivity() {
    private lateinit var repository: MonitoringRepo

    /**
     * Export all log files as a ZIP archive
     */
    private fun exportLogs() {
        try {
            val logFiles = Logger.getAllLogFiles()
            if (logFiles.isEmpty()) {
                ToastOverlay.show(this, "没有找到日志文件", 3000L)
                return
            }

            // Create ZIP file in cache/exports directory (matches file_paths.xml)
            val exportsDir = java.io.File(cacheDir, "exports").apply {
                if (!exists()) mkdirs()
            }
            val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", java.util.Locale.getDefault()).format(java.util.Date())
            val zipFile = java.io.File(exportsDir, "seenot_logs_$timestamp.zip")

            // Create ZIP archive
            java.util.zip.ZipOutputStream(java.io.FileOutputStream(zipFile)).use { zos ->
                logFiles.forEach { logFile ->
                    try {
                        java.io.FileInputStream(logFile).use { fis ->
                            val entryName = logFile.absolutePath.substringAfter("logs/") // Keep relative path structure
                            val zipEntry = java.util.zip.ZipEntry(entryName)
                            zos.putNextEntry(zipEntry)

                            val buffer = ByteArray(1024)
                            var length: Int
                            while (fis.read(buffer).also { length = it } > 0) {
                                zos.write(buffer, 0, length)
                            }
                            zos.closeEntry()
                        }
                    } catch (e: Exception) {
                        Logger.e("MainActivity", "Failed to add file ${logFile.name} to ZIP", e)
                    }
                }
            }

            // Share the ZIP file
            val zipUri = androidx.core.content.FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                zipFile
            )

            val shareIntent = Intent().apply {
                action = Intent.ACTION_SEND
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, zipUri)
                putExtra(Intent.EXTRA_SUBJECT, "SeeNot 日志导出")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(Intent.createChooser(shareIntent, "分享日志文件"))

            Logger.i("MainActivity", "Exported ${logFiles.size} log files to ZIP archive")

        } catch (e: Exception) {
            Logger.e("MainActivity", "Failed to export logs", e)
            ToastOverlay.show(this, "导出日志失败：${e.message}", 5000L)
        }
    }

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
            ToastOverlay.show(this, "分享失败：${e.message}", 5000L)
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
                ToastOverlay.show(this, getString(R.string.invalid_json_format), 5000L)
                return
            }

            // Replace all apps in data store
            val dataStore = com.roderickqiu.seenot.data.AppDataStore(this)
            dataStore.saveMonitoringApps(importedApps)

            ToastOverlay.show(this, getString(R.string.rules_imported_successfully), 5000L)

            // Call the callback to refresh UI
            onImportComplete()
        } catch (e: Exception) {
            ToastOverlay.show(this, "${getString(R.string.invalid_json_format)}: ${e.message}", 5000L)
        }
    }

    /**
     * Export Activity Insights data as JSON file
     */
    private fun exportActivityInsights() {
        try {
            val repo = LabelNormalizationRepo(this)
            val observations = repo.loadObservations()
            val labels = repo.loadLabels()
            val mergeSuggestions = repo.loadMergeSuggestions()

            val exportData = ActivityInsightsExport(
                observations = observations,
                labels = labels,
                mergeSuggestions = mergeSuggestions,
                exportDate = System.currentTimeMillis(),
                exportVersion = "1.0"
            )

            val gson = GsonBuilder().setPrettyPrinting().create()
            val jsonString = gson.toJson(exportData)

            // Create file in cache directory
            val exportsDir = java.io.File(cacheDir, "exports").apply {
                if (!exists()) mkdirs()
            }
            val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", java.util.Locale.getDefault()).format(java.util.Date())
            val exportFile = java.io.File(exportsDir, "seenot_insights_$timestamp.json")
            exportFile.writeText(jsonString)

            // Share the file
            val fileUri = androidx.core.content.FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                exportFile
            )

            val shareIntent = Intent().apply {
                action = Intent.ACTION_SEND
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, fileUri)
                putExtra(Intent.EXTRA_SUBJECT, "SeeNot Activity Insights Export")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val chooserIntent = Intent.createChooser(shareIntent, "Share Activity Insights")
            chooserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(chooserIntent)

            ToastOverlay.show(this, "Activity Insights exported to file", 3000L)
        } catch (e: Exception) {
            Logger.e("MainActivity", "Failed to export activity insights", e)
            ToastOverlay.show(this, "Export failed: ${e.message}", 5000L)
        }
    }

    /**
     * Launch file picker to import Activity Insights from file
     */
    private fun launchImportInsightsFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "Select Activity Insights JSON file")
        }
        importInsightsLauncher.launch(intent)
    }

    /**
     * Import Activity Insights from file URI
     */
    private fun importActivityInsightsFromUri(uri: android.net.Uri) {
        try {
            val jsonString = contentResolver.openInputStream(uri)?.use { stream ->
                stream.bufferedReader().readText()
            } ?: run {
                ToastOverlay.show(this, "Cannot read file", 5000L)
                return
            }

            val gson = GsonBuilder().create()
            val exportData = gson.fromJson(jsonString, ActivityInsightsExport::class.java)

            if (exportData == null) {
                ToastOverlay.show(this, "Invalid JSON format", 5000L)
                return
            }

            val repo = LabelNormalizationRepo(this)

            // Save all data
            exportData.observations?.let { repo.saveObservations(it) }
            exportData.labels?.let { repo.saveLabels(it) }
            exportData.mergeSuggestions?.let { repo.saveMergeSuggestions(it) }

            ToastOverlay.show(this, "Activity Insights imported successfully", 3000L)
        } catch (e: Exception) {
            Logger.e("MainActivity", "Failed to import activity insights from file", e)
            ToastOverlay.show(this, "Import failed: ${e.message}", 5000L)
        }
    }

    /**
     * Data class for Activity Insights export/import
     */
    data class ActivityInsightsExport(
        val observations: List<com.roderickqiu.seenot.data.ScreenObservation>? = null,
        val labels: List<com.roderickqiu.seenot.data.ContentLabel>? = null,
        val mergeSuggestions: List<com.roderickqiu.seenot.data.LabelMergeSuggestion>? = null,
        val exportDate: Long = System.currentTimeMillis(),
        val exportVersion: String = "1.0"
    )

    private lateinit var importInsightsLauncher: androidx.activity.result.ActivityResultLauncher<android.content.Intent>

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Apply saved language setting
        LanguageManager.updateConfiguration(this)
        enableEdgeToEdge()

        // Check external storage before initializing logger
        val externalAvailable = Logger.isExternalStorageAvailable(this)
        Log.i("MainActivity", "External storage available: $externalAvailable")
        Log.i("MainActivity", "External storage state: ${Logger.getExternalStorageInfo()}")

        // Initialize custom logger
        Logger.init(this)

        // Test logger with some initial messages
        Logger.i(
            "MainActivity",
            "App started, log directory: ${Logger.getLogDirectoryPath()}, max entries per file: ${Logger.getMaxEntriesPerFile()}, debug logging enabled, external storage was available during init: $externalAvailable"
        )

        // Perform logger health check
        val healthCheck = Logger.performHealthCheck()
        Logger.i("MainActivity", "Logger health check result:\n${healthCheck.getSummary()}")

        if (!healthCheck.isHealthy()) {
            Logger.w("MainActivity", "Logger health issues detected, recovery was attempted")
        }

        // Create missing day directories to ensure continuity
        Logger.createMissingDayDirectories(30) // Create directories for last 30 days

        repository = MonitoringRepo(this)

        // Register file picker for importing Activity Insights
        importInsightsLauncher = registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                result.data?.data?.let { uri ->
                    importActivityInsightsFromUri(uri)
                }
            }
        }

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
                var showRuleRecordingDialog by remember { mutableStateOf(false) }
                var showRuleRecordsPage by remember { mutableStateOf(false) }
                var showLabelNormPage by remember { mutableStateOf(false) }
                var showLabelNormMenu by remember { mutableStateOf(false) }
                var showLabelNormClearConfirm by remember { mutableStateOf(false) }
                var labelNormRefreshKey by remember { mutableStateOf(0) }
                var permissionRefreshKey by remember { mutableStateOf(0) }
                val scope = rememberCoroutineScope()
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
                        } else if (showLabelNormPage) {
                            CenterAlignedTopAppBar(
                                title = { Text(context.getString(R.string.content_labels)) },
                                navigationIcon = {
                                    IconButton(onClick = { showLabelNormPage = false }) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = context.getString(R.string.back))
                                    }
                                },
                                actions = {
                                    androidx.compose.foundation.layout.Box {
                                        IconButton(onClick = { showLabelNormMenu = true }) {
                                            Icon(Icons.Default.MoreVert, contentDescription = context.getString(R.string.more_options))
                                        }
                                        DropdownMenu(
                                            expanded = showLabelNormMenu,
                                            onDismissRequest = { showLabelNormMenu = false }
                                        ) {
                                            DropdownMenuItem(
                                                text = { Text(context.getString(R.string.clear_label_history)) },
                                                onClick = {
                                                    showLabelNormMenu = false
                                                    showLabelNormClearConfirm = true
                                                }
                                            )
                                        }
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
                                                text = { Text(text = context.getString(R.string.rule_records_menu)) },
                                                onClick = {
                                                    showTopMenu = false
                                                    showRuleRecordsPage = true
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text(text = context.getString(R.string.content_labels_menu)) },
                                                onClick = {
                                                    showTopMenu = false
                                                    showLabelNormPage = true
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
                        if (showRuleRecordsPage || showLabelNormPage) {
                            // Rule Records / Label Norm pages don't need FAB
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
                            modifier = Modifier.padding(innerPadding),
                            onNavigateToSettings = {
                                showRuleRecordingDialog = true
                            }
                        )
                    } else if (showLabelNormPage) {
                        LabelNormPage(
                            modifier = Modifier.padding(innerPadding),
                            refreshKey = labelNormRefreshKey
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
                
                // Clear Label History Dialog
                if (showLabelNormClearConfirm) {
                    AlertDialog(
                        onDismissRequest = { showLabelNormClearConfirm = false },
                        title = { Text(context.getString(R.string.clear_label_history)) },
                        text = { Text(context.getString(R.string.clear_label_history_confirm)) },
                        confirmButton = {
                            TextButton(onClick = {
                                showLabelNormClearConfirm = false
                                scope.launch(Dispatchers.IO) {
                                    LabelNormalizationRepo(context).clearAll()
                                    labelNormRefreshKey++
                                }
                            }) {
                                Text(context.getString(R.string.delete))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showLabelNormClearConfirm = false }) {
                                Text(context.getString(R.string.cancel))
                            }
                        }
                    )
                }

                // Add App Dialog
                if (showAddAppDialog) {
                    AddAppDialog(
                        onDismiss = { showAddAppDialog = false },
                        onAddApp = { appName ->
                            val newApp = MonitoringApp(
                                name = appName,
                                rules = emptyList()
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
                        },
                        onImportExportClick = {
                            showAiSettings = false
                            showImportExportDialog = true
                        },
                        onRuleRecordingClick = {
                            showAiSettings = false
                            showRuleRecordingDialog = true
                        }
                    )
                }

                if (showRuleRecordingDialog) {
                    RuleRecordingSettingsDialog(onDismiss = { showRuleRecordingDialog = false })
                }

                if (showImportExportDialog) {
                    ImportExportDialog(
                        onDismiss = { showImportExportDialog = false },
                        onExport = { exportRules() },
                        onExportLogs = { exportLogs() },
                        onExportInsights = { exportActivityInsights() },
                        onImport = { jsonText ->
                            importRules(jsonText) {
                                // Refresh monitoring apps after import
                                monitoringApps = repository.getAllApps()
                            }
                            showImportExportDialog = false
                        },
                        onImportInsightsFromFile = {
                            launchImportInsightsFilePicker()
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

    override fun onResume() {
        super.onResume()
        // Re-apply language when returning from system settings (e.g., accessibility settings)
        // because the system may reset the app's configuration
        LanguageManager.updateConfiguration(this)
    }

    override fun attachBaseContext(newBase: Context?) {
        // Apply language before super.attachBaseContext to ensure correct language in all cases
        newBase?.let { LanguageManager.updateConfiguration(it) }
        super.attachBaseContext(newBase)
    }
}