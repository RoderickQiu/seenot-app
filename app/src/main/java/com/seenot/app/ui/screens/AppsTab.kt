package com.seenot.app.ui.screens

import android.net.Uri
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateUtils
import android.app.NotificationManager
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityManager
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.window.Dialog
import com.seenot.app.BuildConfig
import com.seenot.app.R
import com.seenot.app.account.SeenotAccountApi
import com.seenot.app.account.SeenotAccountSession
import com.seenot.app.account.SeenotAccountState
import com.seenot.app.account.SeenotManagedAiQuotaExceededException
import com.seenot.app.account.SeenotSyncCoordinator
import com.seenot.app.account.SeenotVersionCheckPrefs
import com.seenot.app.account.SeenotVersionCheckResponse
import com.seenot.app.config.ApiConfig
import com.seenot.app.config.AiSource
import com.seenot.app.config.AppLocalePrefs
import com.seenot.app.config.AiProvider
import com.seenot.app.config.ApiSettings
import com.seenot.app.config.InterventionDialogPrefs
import com.seenot.app.config.recommendedModelPresets
import com.seenot.app.config.InterventionLevelPrefs
import com.seenot.app.config.QwenRegion
import com.seenot.app.config.SttSettings
import com.seenot.app.config.recommendedSttModelPresets
import com.seenot.app.config.selectableProviders
import com.seenot.app.config.selectableSttProviders
import com.seenot.app.config.IntentReminderPrefs
import com.seenot.app.config.NoMonitorReminderPrefs
import com.seenot.app.config.RuleRecordingPrefs
import com.seenot.app.domain.AppEntryIntentMode
import com.seenot.app.domain.SessionManager
import com.seenot.app.domain.AppMonitoringPause
import com.seenot.app.service.ForegroundUsageStatsReader
import com.seenot.app.service.SeenotAccessibilityService
import com.seenot.app.service.MediaSessionProbe
import com.seenot.app.ai.voice.VoiceInputManager
import com.seenot.app.ai.voice.VoiceRecordingState
import com.seenot.app.ai.parser.AppInfo
import com.seenot.app.ui.overlay.VoiceInputOverlay
import com.seenot.app.data.repository.AppHintRepository
import com.seenot.app.data.repository.RuleRecordRepository
import com.seenot.app.data.model.APP_HINT_SOURCE_FEEDBACK_GENERATED
import com.seenot.app.data.model.APP_HINT_SOURCE_INTENT_CARRY_OVER
import com.seenot.app.data.model.APP_HINT_SOURCE_MANUAL
import com.seenot.app.data.model.AppHintScopeType
import com.seenot.app.data.model.ConstraintType
import com.seenot.app.data.model.InterventionLevel
import com.seenot.app.data.model.TimeScope
import com.seenot.app.data.model.buildAppGeneralScopeKey
import com.seenot.app.data.model.buildAppGeneralScopeLabel
import com.seenot.app.data.model.buildIntentScopedHintId
import com.seenot.app.data.model.buildIntentScopedHintLabel
import com.seenot.app.domain.SessionConstraint
import com.seenot.app.observability.RuntimeEventLogger
import android.widget.Toast
import kotlinx.coroutines.launch
import com.seenot.app.ui.overlay.VoiceInputState
import com.seenot.app.ui.overlay.VoiceInputStatus
import kotlinx.coroutines.flow.collectLatest

/**
 * Apps Tab - Controlled app selection
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsTab(
    modifier: Modifier = Modifier,
    onSyncNeeded: () -> Unit = {}
) {
    val context = LocalContext.current

    // Get SessionManager instance
    val sessionManager = remember { SessionManager.getInstance(context) }

    // State for controlled apps from SessionManager
    var controlledApps by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pausedMonitoringApps by remember { mutableStateOf<Map<String, AppMonitoringPause>>(emptyMap()) }

    // State for add app dialog
    var showAddAppDialog by remember { mutableStateOf(false) }
    // State for app rules dialog
    var selectedAppForRules by remember { mutableStateOf<AppInfo?>(null) }
    var selectedAppForPause by remember { mutableStateOf<AppInfo?>(null) }
    var selectedAppForDelete by remember { mutableStateOf<AppInfo?>(null) }

    // Collect controlled apps from SessionManager
    LaunchedEffect(Unit) {
        controlledApps = sessionManager.controlledApps.value
        pausedMonitoringApps = sessionManager.pausedMonitoringApps.value
        sessionManager.controlledApps.collectLatest {
            controlledApps = it
        }
    }
    LaunchedEffect(Unit) {
        sessionManager.pausedMonitoringApps.collectLatest {
            pausedMonitoringApps = it
        }
    }

    // Get app names for controlled apps
    val pm = context.packageManager
    val controlledAppList = controlledApps.mapNotNull { packageName ->
        try {
            val appInfo = pm.getApplicationInfo(packageName, 0)
            AppInfo(
                name = pm.getApplicationLabel(appInfo).toString(),
                packageName = packageName
            )
        } catch (e: Exception) {
            null
        }
    }.sortedBy { it.name }

    Column(modifier = modifier.fillMaxWidth()) {
        // Header with add button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.controlled_apps),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Button(onClick = { showAddAppDialog = true }) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.add_app))
            }
        }

        if (controlledAppList.isEmpty()) {
            // Empty state
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Apps,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.no_controlled_apps_yet),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.tap_add_app_to_select),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = { showAddAppDialog = true }) {
                        Text(stringResource(R.string.add_first_app_now))
                    }
                }
            }
        } else {
            // Controlled app list
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(controlledAppList) { app ->
                    AppItem(
                        app = app,
                        pause = pausedMonitoringApps[app.packageName],
                        onDelete = {
                            selectedAppForDelete = app
                        },
                        onEditRules = {
                            selectedAppForRules = app
                        },
                        onPauseMonitoring = {
                            selectedAppForPause = app
                        },
                        onResumeMonitoring = {
                            sessionManager.resumeAppMonitoring(app.packageName)
                        }
                    )
                }
            }
        }

    }

    // Add App Dialog
    if (showAddAppDialog) {
        AddAppDialog(
            existingAppPackages = controlledApps,
            onDismiss = { showAddAppDialog = false },
            onAppSelected = { packageName ->
                sessionManager.addControlledApp(packageName)
                onSyncNeeded()
                showAddAppDialog = false
            }
        )
    }

    // App Rules Dialog
    selectedAppForRules?.let { app ->
        AppRulesDialog(
            app = app,
            sessionManager = sessionManager,
            onSyncNeeded = onSyncNeeded,
            onDismiss = { selectedAppForRules = null }
        )
    }

    selectedAppForPause?.let { app ->
        PauseMonitoringDialog(
            app = app,
            onDismiss = { selectedAppForPause = null },
            onPauseForHalfHour = {
                sessionManager.pauseAppMonitoring(app.packageName, 30L * 60L * 1000L)
                selectedAppForPause = null
            },
            onPauseForOneDay = {
                sessionManager.pauseAppMonitoring(app.packageName, 24L * 60L * 60L * 1000L)
                selectedAppForPause = null
            },
            onPauseForCustomHours = { hours ->
                sessionManager.pauseAppMonitoring(app.packageName, hours * 60L * 60L * 1000L)
                selectedAppForPause = null
            },
            onPausePermanently = {
                sessionManager.pauseAppMonitoring(app.packageName, null)
                selectedAppForPause = null
            }
        )
    }

    selectedAppForDelete?.let { app ->
        AlertDialog(
            onDismissRequest = { selectedAppForDelete = null },
            title = { Text(stringResource(R.string.remove_controlled_app_title)) },
            text = { Text(stringResource(R.string.remove_controlled_app_message, app.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        sessionManager.removeControlledApp(app.packageName)
                        onSyncNeeded()
                        selectedAppForDelete = null
                    }
                ) {
                    Text(
                        text = stringResource(R.string.remove_controlled_app_action),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedAppForDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

/**
 * App Item
 */
@Composable
fun AppItem(
    app: AppInfo,
    pause: AppMonitoringPause?,
    onDelete: () -> Unit,
    onEditRules: () -> Unit,
    onPauseMonitoring: () -> Unit,
    onResumeMonitoring: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = app.name.take(1).uppercase(),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = app.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                pause?.let {
                    Text(
                        text = formatMonitoringPauseStatus(LocalContext.current, it),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = if (pause == null) onPauseMonitoring else onResumeMonitoring,
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        if (pause == null) Icons.Default.PauseCircle else Icons.Default.PlayCircle,
                        contentDescription = if (pause == null) {
                            stringResource(R.string.pause_app_monitoring)
                        } else {
                            stringResource(R.string.resume_app_monitoring)
                        },
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(
                    onClick = onEditRules,
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = stringResource(R.string.edit_intent),
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.delete),
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun PauseMonitoringDialog(
    app: AppInfo,
    onDismiss: () -> Unit,
    onPauseForHalfHour: () -> Unit,
    onPauseForOneDay: () -> Unit,
    onPauseForCustomHours: (Long) -> Unit,
    onPausePermanently: () -> Unit
) {
    var showCustomHoursDialog by rememberSaveable { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pause_app_monitoring_title, app.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.pause_app_monitoring_desc))
                FilledTonalButton(
                    onClick = onPauseForHalfHour,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.pause_for_half_hour))
                }
                FilledTonalButton(
                    onClick = onPauseForOneDay,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.pause_for_one_day))
                }
                OutlinedButton(
                    onClick = { showCustomHoursDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.pause_custom_hours_entry))
                }
                OutlinedButton(
                    onClick = onPausePermanently,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.pause_permanently))
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )

    if (showCustomHoursDialog) {
        PauseCustomHoursDialog(
            onDismiss = { showCustomHoursDialog = false },
            onPauseForCustomHours = { hours ->
                onPauseForCustomHours(hours)
                showCustomHoursDialog = false
            }
        )
    }
}

private fun formatMonitoringPauseStatus(context: Context, pause: AppMonitoringPause): String {
    val resumeAt = pause.resumeAt
    if (resumeAt == null) {
        return context.getString(R.string.app_monitoring_paused_permanently)
    }
    return context.getString(R.string.app_monitoring_paused_until, formatResumeTime(context, resumeAt))
}

internal fun formatResumeTime(context: Context, resumeAt: Long): String {
    return DateUtils.formatDateTime(
        context,
        resumeAt,
        DateUtils.FORMAT_SHOW_DATE or
            DateUtils.FORMAT_SHOW_TIME or
            DateUtils.FORMAT_ABBREV_MONTH or
            DateUtils.FORMAT_ABBREV_RELATIVE
    )
}

/**
 * Get installed apps (only apps with launcher icons, excluding SeeNot itself)
 */
private fun getInstalledApps(context: android.content.Context): List<AppInfo> {
    val pm = context.packageManager

    // Get all apps that have a launcher intent (user-visible apps)
    val launchIntent = Intent(Intent.ACTION_MAIN, null).apply {
        addCategory(Intent.CATEGORY_LAUNCHER)
    }
    val apps = pm.queryIntentActivities(launchIntent, 0)
        .map { it.activityInfo.packageName }
        .distinct()
        .filter { it != context.packageName }
        .mapNotNull { packageName ->
            try {
                val appInfo = pm.getApplicationInfo(packageName, 0)
                if (shouldExcludeFromControlledAppPicker(pm, context.packageName, packageName)) {
                    android.util.Log.d("AppsTab", "Filtering out utility/system shell app: $packageName")
                    return@mapNotNull null
                }
                AppInfo(
                    name = pm.getApplicationLabel(appInfo).toString(),
                    packageName = packageName
                )
            } catch (e: Exception) {
                null
            }
        }
        .sortedBy { it.name }

    android.util.Log.d("AppsTab", "Found ${apps.size} launchable apps")
    return apps
}

private fun shouldExcludeFromControlledAppPicker(
    packageManager: PackageManager,
    selfPackageName: String,
    packageName: String
): Boolean {
    if (packageName == selfPackageName) return true
    // Across OEMs, the only stable "not a real target app" signal for launcher-
    // visible packages is that they are HOME apps. Other utility/system surfaces
    // vary too much by package name and preload strategy to exclude safely.
    return isHomeApp(packageManager, packageName)
}

private fun isHomeApp(packageManager: PackageManager, packageName: String): Boolean {
    val homeIntent = Intent(Intent.ACTION_MAIN).apply {
        addCategory(Intent.CATEGORY_HOME)
    }
    return packageManager.queryIntentActivities(homeIntent, 0)
        .any { it.activityInfo?.packageName == packageName }
}

/**
 * Add App Dialog - Select apps to add to controlled list
 */
@Composable
fun AddAppDialog(
    existingAppPackages: Set<String>,
    onDismiss: () -> Unit,
    onAppSelected: (String) -> Unit
) {
    val context = LocalContext.current
    var installedApps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        isLoading = true
        val allApps = getInstalledApps(context)
        // Filter out already added apps
        installedApps = allApps.filter { it.packageName !in existingAppPackages }
        isLoading = false
        android.util.Log.d("AppsTab", "Available apps to add: ${installedApps.size}")
    }

    // Filter by search query
    val filteredApps = installedApps.filter {
        searchQuery.isBlank() || it.name.contains(searchQuery, ignoreCase = true)
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.add_app_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(16.dp))

                if (isLoading) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator()
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = stringResource(R.string.processing_label),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (installedApps.isEmpty()) {
                    Text(
                        text = stringResource(R.string.no_apps_available),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.search_app_label)) },
                        placeholder = { Text(stringResource(R.string.search_placeholder)) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(280.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filteredApps) { app ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onAppSelected(app.packageName) }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        shape = MaterialTheme.shapes.small,
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(
                                                text = app.name.take(1).uppercase(),
                                                style = MaterialTheme.typography.titleSmall
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = app.name,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Text(
                                            text = app.packageName,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            }
        }
    }
}

