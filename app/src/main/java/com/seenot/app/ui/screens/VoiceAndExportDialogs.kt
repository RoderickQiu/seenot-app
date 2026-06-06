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
 * Voice Input Dialog - shown when user enters a controlled app
 */
@Composable
fun VoiceInputDialog(
    packageName: String,
    onDismiss: () -> Unit,
    onIntentConfirmed: (List<com.seenot.app.domain.SessionConstraint>) -> Unit
) {
    val context = LocalContext.current

    // Initialize VoiceInputManager
    val voiceManager = remember { VoiceInputManager(context) }

    // Get app name
    val appDisplayName = remember(packageName) {
        getAppDisplayName(context, packageName)
    }

    // Check if has last intent
    val sessionManager = remember { SessionManager.getInstance(context) }
    val hasLastIntent = remember(packageName) { sessionManager.hasLastIntent(packageName) }

    // Collect state from voice manager
    val recordingState by voiceManager.recordingState.collectAsState()
    val recognizedText by voiceManager.recognizedText.collectAsState()
    val parsedIntent by voiceManager.parsedIntent.collectAsState()
    val error by voiceManager.error.collectAsState()

    // Local text input state
    var textInput by remember { mutableStateOf("") }
    var showTextInput by remember { mutableStateOf(false) }

    // Handle parsed intent
    LaunchedEffect(parsedIntent) {
        parsedIntent?.let { intent ->
            onIntentConfirmed(intent.constraints)
        }
    }

    // Release on dispose
    DisposableEffect(Unit) {
        onDispose {
            voiceManager.release()
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header
                Text(
                    text = when (recordingState) {
                        VoiceRecordingState.RECORDING -> stringResource(R.string.state_recording)
                        VoiceRecordingState.PROCESSING -> stringResource(R.string.state_processing)
                        VoiceRecordingState.TRANSCRIBED -> stringResource(R.string.state_transcribed)
                        VoiceRecordingState.PARSED -> stringResource(R.string.state_parsed)
                        VoiceRecordingState.ERROR -> stringResource(R.string.state_error)
                        else -> stringResource(R.string.declare_intent_default)
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = stringResource(R.string.current_app_label, appDisplayName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Show "continue last intent" button if available
                if (hasLastIntent && recordingState == VoiceRecordingState.IDLE) {
                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedButton(
                        onClick = {
                            // Load and use last intent
                            val lastConstraints = sessionManager.loadLastIntent(packageName)
                            if (lastConstraints != null) {
                                onIntentConfirmed(lastConstraints)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.History, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.continue_last_intent_button))
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Show error if any
                error?.let { err ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = err,
                            modifier = Modifier.padding(12.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // Show recognized/entered text
                when {
                    recordingState == VoiceRecordingState.RECORDING -> {
                        // Show real-time recognized text during recording
                        if (recognizedText != null) {
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = recognizedText!!,
                                    modifier = Modifier.padding(16.dp),
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                        // Recording animation
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { voiceManager.stopRecording() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.stop_recording_button))
                        }
                    }
                    recordingState == VoiceRecordingState.PROCESSING -> {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.recognizing_voice),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    recordingState == VoiceRecordingState.PARSED && parsedIntent != null -> {
                        // Show final parsed constraints
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = stringResource(R.string.recognized_content_label),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                )
                                Text(
                                    text = parsedIntent!!.text,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = stringResource(R.string.parsed_result_label),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                )
                                parsedIntent!!.constraints.forEach { constraint ->
                                    Text(
                                        text = "• ${stringResource(constraintTypeLabel(constraint.type))}: ${constraint.description}",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))

                        // Show confirm button (this will be auto-triggered by LaunchedEffect)
                        Button(
                            onClick = { /* Auto-confirmed by LaunchedEffect */ },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = false
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.intent_confirmed))
                        }
                    }
                    recognizedText != null && (recordingState == VoiceRecordingState.TRANSCRIBED || recordingState == VoiceRecordingState.PROCESSING) -> {
                        // Show recognized text
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = recognizedText!!,
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))

                        // Confirm or retry
                        Button(
                            onClick = {
                                // Intent already being parsed, wait for result
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = false
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.parsing_intent))
                        }
                    }
                    showTextInput -> {
                        // Text input mode
                        OutlinedTextField(
                            value = textInput,
                            onValueChange = { textInput = it },
                            label = { Text(stringResource(R.string.input_intent_label)) },
                            placeholder = { Text(stringResource(R.string.input_intent_hint)) },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                            enabled = recordingState != VoiceRecordingState.PROCESSING
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
                            onClick = {
                                voiceManager.parseTextInput(textInput, packageName, appDisplayName)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = textInput.isNotBlank() && recordingState != VoiceRecordingState.PROCESSING
                        ) {
                            if (recordingState == VoiceRecordingState.PROCESSING) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (recordingState == VoiceRecordingState.PROCESSING) stringResource(R.string.processing_label) else stringResource(R.string.confirm_button))
                        }
                    }
                    else -> {
                        // Voice input button
                        Button(
                            onClick = { voiceManager.startRecording() },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = recordingState != VoiceRecordingState.PROCESSING
                        ) {
                            Icon(Icons.Default.Mic, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.start_voice_input_button))
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Text input fallback
                        OutlinedButton(
                            onClick = { showTextInput = true },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = recordingState != VoiceRecordingState.PROCESSING
                        ) {
                            Icon(Icons.Default.Keyboard, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.use_text_input_button))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Skip button
                TextButton(
                    onClick = onDismiss,
                    enabled = recordingState != VoiceRecordingState.PROCESSING
                ) {
                    Text(stringResource(R.string.skip_button))
                }
            }
        }
    }
}

@Composable
fun ExportDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val configurationExporter = remember { ConfigurationExporter(context) }
    val runtimeEventLogger = remember { RuntimeEventLogger.getInstance(context) }
    val runtimeEventEnabled = BuildConfig.ENABLE_RUNTIME_EVENT_LOGGING

    var startDate by remember { mutableStateOf("") }
    var endDate by remember { mutableStateOf("") }
    var isExporting by remember { mutableStateOf(false) }
    var exportMessage by remember { mutableStateOf("") }
    var selectedExportTab by remember { mutableIntStateOf(0) }
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                isExporting = true
                exportMessage = context.getString(R.string.exporting_app_config)
                try {
                    val importedCount = configurationExporter.importConfiguration(uri) { progress ->
                        exportMessage = progress
                    }.getOrThrow()
                    exportMessage = context.getString(R.string.import_success, importedCount)
                } catch (e: Exception) {
                    exportMessage = context.getString(R.string.import_failed, e.message ?: "")
                } finally {
                    isExporting = false
                }
            }
        }
    }

    // Get current date as default
    val currentDate = remember {
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
    }

    // Set default dates (last 7 days to today)
    LaunchedEffect(Unit) {
        val calendar = java.util.Calendar.getInstance()
        endDate = currentDate
        calendar.add(java.util.Calendar.DAY_OF_MONTH, -7)
        startDate = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(calendar.time)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.export_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                val availableTabs = buildList {
                    add(context.getString(R.string.app_config))
                    add(context.getString(R.string.logs))
                    if (runtimeEventEnabled) {
                        add(context.getString(R.string.runtime_events))
                    }
                }
                val safeSelectedTab = selectedExportTab.coerceIn(0, availableTabs.lastIndex)

                if (safeSelectedTab != selectedExportTab) {
                    selectedExportTab = safeSelectedTab
                }

                TabRow(selectedTabIndex = safeSelectedTab) {
                    Tab(
                        selected = safeSelectedTab == 0,
                        onClick = {
                            selectedExportTab = 0
                            exportMessage = ""
                        },
                        text = { Text(stringResource(R.string.app_config)) }
                    )
                    Tab(
                        selected = safeSelectedTab == 1,
                        onClick = {
                            selectedExportTab = 1
                            exportMessage = ""
                        },
                        text = { Text(stringResource(R.string.logs)) }
                    )
                    if (runtimeEventEnabled) {
                        Tab(
                            selected = safeSelectedTab == 2,
                            onClick = {
                                selectedExportTab = 2
                                exportMessage = ""
                            },
                            text = { Text(stringResource(R.string.runtime_events)) }
                        )
                    }
                }

                if (safeSelectedTab == 0) {
                    Text(
                        text = stringResource(R.string.export_app_config_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    isExporting = true
                                    exportMessage = context.getString(R.string.exporting_app_config_ellipsis)
                                    try {
                                        val uri = configurationExporter.exportConfiguration { progress ->
                                            exportMessage = progress
                                        }
                                        if (uri != null) {
                                            configurationExporter.shareExportedFile(uri) { error ->
                                                exportMessage = error
                                            }
                                            if (!exportMessage.contains(context.getString(R.string.fail))) {
                                                exportMessage = context.getString(R.string.export_app_config_success)
                                            }
                                        } else if (exportMessage.isBlank()) {
                                            exportMessage = context.getString(R.string.export_app_config_failed)
                                        }
                                    } catch (e: Exception) {
                                        exportMessage = context.getString(R.string.export_app_config_failed_reason, e.message ?: "")
                                    } finally {
                                        isExporting = false
                                    }
                                }
                            },
                            enabled = !isExporting,
                            modifier = Modifier.weight(1f)
                        ) {
                            if (isExporting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text(stringResource(R.string.export_button))
                            }
                        }

                        Button(
                            onClick = {
                                exportMessage = ""
                                importLauncher.launch(arrayOf("application/json", "text/plain"))
                            },
                            enabled = !isExporting,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.import_button))
                        }
                    }
                } else if (safeSelectedTab == 1) {
                    Text(
                        text = stringResource(R.string.export_logs_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedTextField(
                        value = startDate,
                        onValueChange = { startDate = it },
                        label = { Text(stringResource(R.string.start_date_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isExporting
                    )

                    OutlinedTextField(
                        value = endDate,
                        onValueChange = { endDate = it },
                        label = { Text(stringResource(R.string.end_date_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isExporting
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    isExporting = true
                                    exportMessage = context.getString(R.string.sharing_all_logs)
                                    try {
                                        val success = com.seenot.app.utils.Logger.shareAllLogs(context)
                                        exportMessage = if (success) context.getString(R.string.share_success) else context.getString(R.string.share_failed_check_logs)
                                        if (success) {
                                            kotlinx.coroutines.delay(1000)
                                            onDismiss()
                                        }
                                    } catch (e: Exception) {
                                        exportMessage = context.getString(R.string.share_failed_reason, e.message ?: "")
                                    } finally {
                                        isExporting = false
                                    }
                                }
                            },
                            enabled = !isExporting,
                            modifier = Modifier.weight(1f)
                        ) {
                            if (isExporting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text(stringResource(R.string.share_all_button))
                            }
                        }

                        Button(
                            onClick = {
                                scope.launch {
                                    isExporting = true
                                    exportMessage = context.getString(R.string.sharing_logs)
                                    try {
                                        val dateFormat = java.text.SimpleDateFormat(
                                            "yyyy-MM-dd",
                                            java.util.Locale.getDefault()
                                        )
                                        val start = dateFormat.parse(startDate)
                                        val end = dateFormat.parse(endDate)

                                        if (start != null && end != null) {
                                            val success = com.seenot.app.utils.Logger.shareLogs(context, start, end)
                                            exportMessage = if (success) {
                                                context.getString(R.string.share_success)
                                            } else {
                                                context.getString(R.string.share_failed_check_date_and_logs)
                                            }
                                            if (success) {
                                                kotlinx.coroutines.delay(1000)
                                                onDismiss()
                                            }
                                        } else {
                                            exportMessage = context.getString(R.string.date_format_error)
                                        }
                                    } catch (e: Exception) {
                                        exportMessage = context.getString(R.string.share_failed_reason, e.message ?: "")
                                    } finally {
                                        isExporting = false
                                    }
                                }
                            },
                            enabled = !isExporting && startDate.isNotEmpty() && endDate.isNotEmpty(),
                            modifier = Modifier.weight(1f)
                        ) {
                            if (isExporting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                Text(stringResource(R.string.share_date_range_button))
                            }
                        }
                    }
                } else {
                    Text(
                        text = stringResource(R.string.export_runtime_events_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedTextField(
                        value = startDate,
                        onValueChange = { startDate = it },
                        label = { Text(stringResource(R.string.start_date_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isExporting
                    )

                    OutlinedTextField(
                        value = endDate,
                        onValueChange = { endDate = it },
                        label = { Text(stringResource(R.string.end_date_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isExporting
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    isExporting = true
                                    exportMessage = context.getString(R.string.exporting_runtime_events)
                                    try {
                                        val uri = runtimeEventLogger.exportAll { progress ->
                                            exportMessage = progress
                                        }
                                        if (uri != null) {
                                            runtimeEventLogger.shareExportedFile(uri) { error ->
                                                exportMessage = error
                                            }
                                            if (!exportMessage.contains(context.getString(R.string.fail))) {
                                                exportMessage = context.getString(R.string.export_runtime_events_success)
                                            }
                                        }
                                    } catch (e: Exception) {
                                        exportMessage = context.getString(R.string.export_runtime_events_failed_reason, e.message ?: "")
                                    } finally {
                                        isExporting = false
                                    }
                                }
                            },
                            enabled = !isExporting,
                            modifier = Modifier.weight(1f)
                        ) {
                            if (isExporting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text(stringResource(R.string.export_all_button))
                            }
                        }

                        Button(
                            onClick = {
                                scope.launch {
                                    isExporting = true
                                    exportMessage = context.getString(R.string.exporting_runtime_events)
                                    try {
                                        val dateFormat = java.text.SimpleDateFormat(
                                            "yyyy-MM-dd",
                                            java.util.Locale.getDefault()
                                        )
                                        val start = dateFormat.parse(startDate)
                                        val end = dateFormat.parse(endDate)
                                        if (start != null && end != null) {
                                            val uri = runtimeEventLogger.export(start, end) { progress ->
                                                exportMessage = progress
                                            }
                                            if (uri != null) {
                                                runtimeEventLogger.shareExportedFile(uri) { error ->
                                                    exportMessage = error
                                                }
                                                if (!exportMessage.contains(context.getString(R.string.fail))) {
                                                    exportMessage = context.getString(R.string.export_runtime_events_success)
                                                }
                                            }
                                        } else {
                                            exportMessage = context.getString(R.string.date_format_error)
                                        }
                                    } catch (e: Exception) {
                                        exportMessage = context.getString(R.string.export_runtime_events_failed_reason, e.message ?: "")
                                    } finally {
                                        isExporting = false
                                    }
                                }
                            },
                            enabled = !isExporting && startDate.isNotEmpty() && endDate.isNotEmpty(),
                            modifier = Modifier.weight(1f)
                        ) {
                            if (isExporting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                Text(stringResource(R.string.export_range_button))
                            }
                        }
                    }
                }

                if (exportMessage.isNotEmpty()) {
                    Text(
                        text = exportMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (exportMessage.contains(context.getString(R.string.success))) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        }
    )
}

private fun getAppDisplayName(context: android.content.Context, packageName: String): String {
    return try {
        val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
        context.packageManager.getApplicationLabel(appInfo).toString()
    } catch (e: Exception) {
        packageName
    }
}
