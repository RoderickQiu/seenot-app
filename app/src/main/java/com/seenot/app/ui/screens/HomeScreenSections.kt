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
 * Home Tab - Permission status and quick actions
 */
@Composable
fun HomeTab(
    isAccessibilityEnabled: Boolean,
    isOverlayEnabled: Boolean,
    isNotificationEnabled: Boolean,
    isUsageStatsAccessEnabled: Boolean,
    isMediaSessionAccessEnabled: Boolean,
    isBatteryOptimizationIgnored: Boolean,
    isMicrophoneEnabled: Boolean,
    isAiConfigured: Boolean,
    isHomeReady: Boolean,
    controlledAppCount: Int,
    globalMonitoringPause: AppMonitoringPause?,
    onEnableAccessibility: () -> Unit,
    onEnableOverlay: () -> Unit,
    onOpenSecondaryHelp: (SetupSecondaryHelp) -> Unit,
    onRequestIgnoreBatteryOptimizations: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onOpenMediaSessionAccessSettings: () -> Unit,
    onOpenUsageStatsAccessSettings: () -> Unit,
    onRequestMicrophone: () -> Unit,
    onOpenAiSettings: () -> Unit,
    onOpenAiOptionsHelp: () -> Unit,
    onOpenUsageInstructions: () -> Unit,
    onOpenAccount: () -> Unit,
    onUseSeenotAi: () -> Unit,
    onOpenControlledApps: () -> Unit,
    onPauseGlobalMonitoring: (Long?) -> Unit,
    onResumeGlobalMonitoring: () -> Unit,
    accountState: SeenotAccountState,
    showHomeTimeline: Boolean,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    var showSetupDetails by rememberSaveable { mutableStateOf(false) }
    var showGlobalPauseDialog by rememberSaveable { mutableStateOf(false) }
    var showAiChoiceDialog by rememberSaveable { mutableStateOf(false) }
    val hasControlledApps = controlledAppCount > 0
    val requiredSetupSteps = listOf(
        SetupProgressStep(
            title = stringResource(R.string.permission_overlay),
            description = stringResource(R.string.permission_overlay_desc),
            offImpact = stringResource(R.string.permission_overlay_off),
            isComplete = isOverlayEnabled,
            actionLabel = stringResource(R.string.permission_overlay_action),
            secondaryHelp = SetupSecondaryHelp.RESTRICTED_SETTINGS_OVERLAY,
            onAction = onEnableOverlay
        ),
        SetupProgressStep(
            title = stringResource(R.string.permission_notification),
            description = stringResource(R.string.permission_notification_desc),
            offImpact = stringResource(R.string.permission_notification_off),
            isComplete = isNotificationEnabled,
            actionLabel = stringResource(R.string.permission_notification_action),
            onAction = onOpenNotificationSettings
        ),
        SetupProgressStep(
            title = stringResource(R.string.permission_accessibility),
            description = stringResource(R.string.permission_accessibility_desc),
            offImpact = stringResource(R.string.permission_accessibility_off),
            isComplete = isAccessibilityEnabled,
            actionLabel = stringResource(R.string.permission_accessibility_action),
            secondaryHelp = SetupSecondaryHelp.RESTRICTED_SETTINGS_ACCESSIBILITY,
            onAction = onEnableAccessibility
        ),
        SetupProgressStep(
            title = stringResource(R.string.permission_battery_optimization),
            description = stringResource(R.string.permission_battery_optimization_desc),
            offImpact = stringResource(R.string.permission_battery_optimization_off),
            isComplete = isBatteryOptimizationIgnored,
            actionLabel = stringResource(R.string.permission_battery_optimization_action),
            secondaryHelp = SetupSecondaryHelp.BACKGROUND_LIMITS,
            onAction = onRequestIgnoreBatteryOptimizations
        ),
        SetupProgressStep(
            title = stringResource(R.string.setup_step_ai_title),
            description = stringResource(R.string.setup_step_ai_desc),
            isComplete = isAiConfigured,
            actionLabel = stringResource(R.string.choose_ai_plan_title),
            secondaryHelp = SetupSecondaryHelp.AI_OPTIONS,
            onAction = { showAiChoiceDialog = true }
        ),
        SetupProgressStep(
            title = stringResource(R.string.setup_step_apps_title),
            description = stringResource(R.string.setup_step_apps_desc),
            isComplete = hasControlledApps,
            actionLabel = stringResource(R.string.setup_step_apps_action),
            onAction = onOpenControlledApps
        )
    )
    val optionalSetupSteps = listOf(
        SetupProgressStep(
            title = stringResource(R.string.permission_usage_stats),
            description = stringResource(R.string.permission_usage_stats_desc),
            isComplete = isUsageStatsAccessEnabled,
            actionLabel = stringResource(R.string.permission_usage_stats_action),
            onAction = onOpenUsageStatsAccessSettings
        ),
        SetupProgressStep(
            title = stringResource(R.string.permission_media_session),
            description = stringResource(R.string.permission_media_session_desc),
            isComplete = isMediaSessionAccessEnabled,
            actionLabel = stringResource(R.string.permission_media_session_action),
            onAction = onOpenMediaSessionAccessSettings
        ),
        SetupProgressStep(
            title = stringResource(R.string.permission_microphone),
            description = stringResource(R.string.permission_microphone_desc),
            isComplete = isMicrophoneEnabled,
            actionLabel = stringResource(R.string.permission_microphone_action),
            onAction = onRequestMicrophone
        )
    )
    val completedSetupSteps = requiredSetupSteps.count { it.isComplete }
    val nextSetupStep = requiredSetupSteps.firstOrNull { !it.isComplete }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(16.dp)
    ) {
        Text(
            text = stringResource(R.string.welcome_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.app_tagline),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            InlineHelpLink(
                text = stringResource(R.string.usage_instructions),
                onClick = onOpenUsageInstructions,
                modifier = Modifier.padding(top = 3.dp)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        FirstSetupProgressCard(
            requiredSteps = requiredSetupSteps,
            optionalSteps = optionalSetupSteps,
            completedSteps = completedSetupSteps,
            totalSteps = requiredSetupSteps.size,
            nextStep = nextSetupStep,
            isHomeReady = isHomeReady,
            showDetails = showSetupDetails,
            onToggleDetails = { showSetupDetails = !showSetupDetails },
            onOpenSecondaryHelp = onOpenSecondaryHelp,
            globalMonitoringPause = globalMonitoringPause,
            onPauseGlobalMonitoring = { showGlobalPauseDialog = true },
            onResumeGlobalMonitoring = onResumeGlobalMonitoring,
            onReadyAction = onOpenControlledApps
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (isHomeReady && showHomeTimeline) {
            // Today's timeline (derived from RuleRecord)
            HomeTimelineSection()
        }
    }

    if (showGlobalPauseDialog) {
        PauseSeenotDialog(
            onDismiss = { showGlobalPauseDialog = false },
            onPauseForHalfHour = {
                onPauseGlobalMonitoring(30L * 60L * 1000L)
                showGlobalPauseDialog = false
            },
            onPauseForOneDay = {
                onPauseGlobalMonitoring(24L * 60L * 60L * 1000L)
                showGlobalPauseDialog = false
            },
            onPauseForCustomHours = { hours ->
                onPauseGlobalMonitoring(hours * 60L * 60L * 1000L)
                showGlobalPauseDialog = false
            },
            onPausePermanently = {
                onPauseGlobalMonitoring(null)
                showGlobalPauseDialog = false
            }
        )
    }

    if (showAiChoiceDialog) {
        HomeAiChoiceDialog(
            accountState = accountState,
            onDismiss = { showAiChoiceDialog = false },
            onUseSeenotAi = {
                showAiChoiceDialog = false
                val isPlus = (accountState as? SeenotAccountState.Ready)?.snapshot?.hasPlus == true
                if (isPlus) {
                    onUseSeenotAi()
                } else {
                    onOpenAccount()
                }
            },
            onUseOwnApiKey = {
                showAiChoiceDialog = false
                onOpenAiSettings()
            },
            onOpenAiOptionsHelp = {
                showAiChoiceDialog = false
                onOpenAiOptionsHelp()
            }
        )
    }
}

private data class SetupProgressStep(
    val title: String,
    val description: String,
    val offImpact: String? = null,
    val isComplete: Boolean,
    val actionLabel: String,
    val secondaryHelp: SetupSecondaryHelp? = null,
    val onAction: () -> Unit
)

@Composable
private fun FirstSetupProgressCard(
    requiredSteps: List<SetupProgressStep>,
    optionalSteps: List<SetupProgressStep>,
    completedSteps: Int,
    totalSteps: Int,
    nextStep: SetupProgressStep?,
    isHomeReady: Boolean,
    showDetails: Boolean,
    onToggleDetails: () -> Unit,
    onOpenSecondaryHelp: (SetupSecondaryHelp) -> Unit,
    globalMonitoringPause: AppMonitoringPause?,
    onPauseGlobalMonitoring: () -> Unit,
    onResumeGlobalMonitoring: () -> Unit,
    onReadyAction: () -> Unit
) {
    val progress = completedSteps.toFloat() / totalSteps.toFloat()
    val isCompactReady = isHomeReady && !showDetails
    val nextOptionalStep = optionalSteps.firstOrNull { !it.isComplete }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (isHomeReady) Icons.Default.CheckCircle else Icons.Default.Flag,
                    contentDescription = null,
                    tint = if (isHomeReady) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.secondary
                    }
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isHomeReady) {
                            stringResource(R.string.home_ready_title)
                        } else {
                            stringResource(R.string.first_setup_progress_title)
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (isHomeReady) {
                            if (globalMonitoringPause == null) {
                                stringResource(R.string.first_setup_ready_desc)
                            } else {
                                formatGlobalMonitoringPauseStatus(LocalContext.current, globalMonitoringPause)
                            }
                        } else {
                            stringResource(R.string.first_setup_progress_count, completedSteps, totalSteps)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (isHomeReady) {
                    Spacer(modifier = Modifier.width(12.dp))
                    InlineHelpLink(
                        text = if (globalMonitoringPause == null) {
                            stringResource(R.string.pause_seenot_monitoring)
                        } else {
                            stringResource(R.string.resume_seenot_monitoring)
                        },
                        onClick = if (globalMonitoringPause == null) {
                            onPauseGlobalMonitoring
                        } else {
                            onResumeGlobalMonitoring
                        }
                    )
                }
            }

            if (!isCompactReady) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.62f)
                )

                if (nextStep != null) {
                    Text(
                        text = nextStep.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = nextStep.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    nextStep.offImpact?.let { offImpact ->
                        Text(
                            text = stringResource(R.string.setup_step_off_impact_label, offImpact),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(
                        onClick = nextStep.onAction,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(nextStep.actionLabel)
                    }
                    nextStep.secondaryHelp?.let { help ->
                        SetupSecondaryHelpLink(
                            help = help,
                            onClick = { onOpenSecondaryHelp(help) },
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )
                    }
                } else if (showDetails) {
                    Text(
                        text = stringResource(R.string.first_setup_ready_next),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = onReadyAction,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.first_setup_ready_action))
                    }
                }
            }

            if (showDetails) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if (nextStep != null) {
                    RequiredSetupSection(
                        requiredSteps = requiredSteps,
                        onOpenSecondaryHelp = onOpenSecondaryHelp
                    )
                    OptionalSetupSection(
                        optionalSteps = optionalSteps,
                        nextOptionalStep = nextOptionalStep
                    )
                } else {
                    OptionalSetupSection(
                        optionalSteps = optionalSteps,
                        nextOptionalStep = nextOptionalStep
                    )
                    RequiredSetupSection(
                        requiredSteps = requiredSteps,
                        onOpenSecondaryHelp = onOpenSecondaryHelp
                    )
                }
            }

            TextButton(
                onClick = onToggleDetails,
                modifier = Modifier.align(Alignment.End)
            ) {
                Text(if (showDetails) stringResource(R.string.tap_to_collapse) else stringResource(R.string.tap_to_expand))
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    if (showDetails) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun RequiredSetupSection(
    requiredSteps: List<SetupProgressStep>,
    onOpenSecondaryHelp: (SetupSecondaryHelp) -> Unit
) {
    Text(
        text = stringResource(R.string.setup_required_section),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        requiredSteps.forEach { step ->
            SetupProgressStepRow(
                step = step,
                onOpenSecondaryHelp = onOpenSecondaryHelp
            )
        }
    }
}

@Composable
private fun OptionalSetupSection(
    optionalSteps: List<SetupProgressStep>,
    nextOptionalStep: SetupProgressStep?
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.setup_optional_section),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        if (nextOptionalStep != null) {
            TextButton(onClick = nextOptionalStep.onAction) {
                Text(stringResource(R.string.setup_optional_next_action))
            }
        }
    }
    Text(
        text = stringResource(R.string.setup_optional_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        optionalSteps.forEach { step ->
            SetupProgressStepRow(
                step = step,
                isOptional = true
            )
        }
    }
}

@Composable
private fun SetupProgressStepRow(
    step: SetupProgressStep,
    isOptional: Boolean = false,
    onOpenSecondaryHelp: ((SetupSecondaryHelp) -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !step.isComplete) { step.onAction() },
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = if (step.isComplete) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (step.isComplete) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier
                .padding(top = 2.dp)
                .size(20.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = step.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = when {
                        step.isComplete -> stringResource(R.string.permission_ready)
                        isOptional -> stringResource(R.string.permission_optional_recommended)
                        else -> stringResource(R.string.permission_not_ready)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        step.isComplete -> MaterialTheme.colorScheme.primary
                        isOptional -> MaterialTheme.colorScheme.secondary
                        else -> MaterialTheme.colorScheme.error
                    }
                )
            }
            Text(
                text = step.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!step.isComplete) {
                step.offImpact?.let { offImpact ->
                    Text(
                        text = stringResource(R.string.setup_step_off_impact_label, offImpact),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (step.secondaryHelp != null && onOpenSecondaryHelp != null) {
                    SetupSecondaryHelpLink(
                        help = step.secondaryHelp,
                        onClick = { onOpenSecondaryHelp(step.secondaryHelp) },
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SetupSecondaryHelpLink(
    help: SetupSecondaryHelp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    InlineHelpLink(
        text = stringResource(help.labelRes),
        onClick = onClick,
        modifier = modifier
    )
}

@Composable
internal fun InlineHelpLink(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.clickable(onClick = onClick)
    )
}

@Composable
private fun PauseSeenotDialog(
    onDismiss: () -> Unit,
    onPauseForHalfHour: () -> Unit,
    onPauseForOneDay: () -> Unit,
    onPauseForCustomHours: (Long) -> Unit,
    onPausePermanently: () -> Unit
) {
    var showCustomHoursDialog by rememberSaveable { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pause_seenot_monitoring_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.pause_seenot_monitoring_desc))
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

@Composable
internal fun PauseCustomHoursDialog(
    onDismiss: () -> Unit,
    onPauseForCustomHours: (Long) -> Unit
) {
    var customHours by rememberSaveable { mutableStateOf("") }
    val parsedHours = customHours.toLongOrNull()
    val isValid = parsedHours != null && parsedHours in 1L..720L

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pause_custom_hours_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.pause_custom_hours_desc))
                OutlinedTextField(
                    value = customHours,
                    onValueChange = { input ->
                        customHours = input.filter(Char::isDigit).take(3)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.pause_custom_hours_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    suffix = { Text(stringResource(R.string.pause_custom_hours_suffix)) }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { parsedHours?.let(onPauseForCustomHours) },
                enabled = isValid
            ) {
                Text(stringResource(R.string.pause_custom_hours_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

private fun formatGlobalMonitoringPauseStatus(context: Context, pause: AppMonitoringPause): String {
    return if (pause.resumeAt == null) {
        context.getString(R.string.seenot_monitoring_paused_permanently)
    } else {
        context.getString(R.string.seenot_monitoring_paused_until, formatResumeTime(context, pause.resumeAt))
    }
}

@Composable
private fun HomeAiChoiceDialog(
    accountState: SeenotAccountState,
    onDismiss: () -> Unit,
    onUseSeenotAi: () -> Unit,
    onUseOwnApiKey: () -> Unit,
    onOpenAiOptionsHelp: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(Icons.Default.AutoAwesome, contentDescription = null)
        },
        title = {
            Text(stringResource(R.string.choose_ai_plan_title))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.choose_ai_plan_home_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                InlineHelpLink(
                    text = stringResource(R.string.ai_options_help_entry),
                    onClick = onOpenAiOptionsHelp
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onUseSeenotAi,
                enabled = accountState !is SeenotAccountState.Loading
            ) {
                Text(stringResource(R.string.use_seenot_ai_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onUseOwnApiKey) {
                Text(stringResource(R.string.use_own_api_key_action))
            }
        }
    )
}

internal enum class PermissionGuideType {
    ACCESSIBILITY,
    OVERLAY,
    BATTERY,
    MEDIA_SESSION,
    USAGE_STATS
}

enum class RestrictedSettingsHelpTarget {
    ACCESSIBILITY,
    OVERLAY
}

enum class SetupSecondaryHelp(
    @StringRes val labelRes: Int,
    val restrictedSettingsTarget: RestrictedSettingsHelpTarget? = null
) {
    RESTRICTED_SETTINGS_ACCESSIBILITY(
        labelRes = R.string.restricted_settings_help_entry,
        restrictedSettingsTarget = RestrictedSettingsHelpTarget.ACCESSIBILITY
    ),
    RESTRICTED_SETTINGS_OVERLAY(
        labelRes = R.string.restricted_settings_help_entry,
        restrictedSettingsTarget = RestrictedSettingsHelpTarget.OVERLAY
    ),
    BACKGROUND_LIMITS(
        labelRes = R.string.background_limits_help_entry
    ),
    AI_OPTIONS(
        labelRes = R.string.ai_options_help_entry
    )
}

enum class AppHelpTopic {
    AI_OPTIONS,
    WRONG_JUDGMENT,
    USAGE_INSTRUCTIONS
}

internal fun PermissionGuideType.restrictedSettingsHelpTarget(): RestrictedSettingsHelpTarget? {
    return when (this) {
        PermissionGuideType.ACCESSIBILITY -> RestrictedSettingsHelpTarget.ACCESSIBILITY
        PermissionGuideType.OVERLAY -> RestrictedSettingsHelpTarget.OVERLAY
        PermissionGuideType.BATTERY,
        PermissionGuideType.MEDIA_SESSION,
        PermissionGuideType.USAGE_STATS -> null
    }
}

private const val SEENOT_APP_LOGIN_REDIRECT_URI = "seenot://auth/callback"

internal suspend fun openSeenotAccountPage(
    context: Context,
    accountApi: SeenotAccountApi,
    accountState: SeenotAccountState,
    isAccountLoginInFlight: Boolean,
    onLoginStarted: (Boolean) -> Unit
) {
    if (accountState !is SeenotAccountState.SignedOut) {
        val language = AppLocalePrefs.getLanguage(context)
        val path = if (language == AppLocalePrefs.LANG_ZH) "/zh/account/" else "/account/"
        val url = BuildConfig.SEENOT_WEBSITE_BASE_URL.trimEnd('/') + path
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        return
    }
    if (isAccountLoginInFlight) return

    onLoginStarted(true)
    runCatching {
        val started = accountApi.startAppLogin(redirectUri = SEENOT_APP_LOGIN_REDIRECT_URI)
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(started.loginUrl)))
    }.onFailure {
        Toast.makeText(
            context,
            context.getString(R.string.account_login_start_failed_toast),
            Toast.LENGTH_LONG
        ).show()
    }
    onLoginStarted(false)
}

internal fun openSeenotAccountManagementPage(
    context: Context,
    accountState: SeenotAccountState
) {
    val manageUrl = (accountState as? SeenotAccountState.Ready)
        ?.snapshot
        ?.entitlement
        ?.manageUrl
        ?.trim()
        .orEmpty()
    val targetUrl = if (manageUrl.isNotBlank()) {
        manageUrl
    } else {
        val language = AppLocalePrefs.getLanguage(context)
        val path = if (language == AppLocalePrefs.LANG_ZH) "/zh/account/" else "/account/"
        BuildConfig.SEENOT_WEBSITE_BASE_URL.trimEnd('/') + path
    }
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)))
}

internal fun openSeenotPlusPage(context: Context) {
    val language = AppLocalePrefs.getLanguage(context)
    val path = if (language == AppLocalePrefs.LANG_ZH) "/zh/#seenot-plus" else "/#seenot-plus"
    val url = BuildConfig.SEENOT_WEBSITE_BASE_URL.trimEnd('/') + path
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}

internal fun openSeenotOfficialSitePage(context: Context) {
    val language = AppLocalePrefs.getLanguage(context)
    val path = if (language == AppLocalePrefs.LANG_ZH) "/zh/" else "/"
    val url = BuildConfig.SEENOT_WEBSITE_BASE_URL.trimEnd('/') + path
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}

internal fun openExternalUrl(context: Context, url: String) {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}

internal suspend fun enableSeenotAi(
    context: Context,
    accountApi: SeenotAccountApi,
    onEnabled: () -> Unit
) {
    runCatching {
        val session = accountApi.createManagedAiSession()
        ApiConfig.saveManagedAiSession(
            apiKey = session.apiKey,
            baseUrl = session.baseUrl,
            model = session.model,
            expiresAtEpochSeconds = session.expiresAt
        )
        onEnabled()
        Toast.makeText(context, context.getString(R.string.seenot_ai_enabled_toast), Toast.LENGTH_SHORT).show()
    }.onFailure {
        Toast.makeText(
            context,
            managedAiErrorMessage(context, it),
            Toast.LENGTH_LONG
        ).show()
    }
}

internal fun managedAiErrorMessage(context: Context, error: Throwable): String {
    return when (error) {
        is SeenotManagedAiQuotaExceededException -> context.getString(R.string.seenot_ai_quota_exceeded)
        else -> error.message?.takeIf { it.isNotBlank() } ?: context.getString(R.string.seenot_ai_enable_failed)
    }
}

@Composable
internal fun PermissionGuideDialog(
    type: PermissionGuideType,
    onOpenRestrictedSettingsHelp: (() -> Unit)?,
    onDismiss: () -> Unit,
    onContinue: () -> Unit
) {
    val (titleRes, messageRes) = when (type) {
        PermissionGuideType.ACCESSIBILITY -> R.string.permission_guide_accessibility_title to R.string.permission_guide_accessibility_message
        PermissionGuideType.OVERLAY -> R.string.permission_guide_overlay_title to R.string.permission_guide_overlay_message
        PermissionGuideType.BATTERY -> R.string.permission_guide_battery_title to R.string.permission_guide_battery_message
        PermissionGuideType.MEDIA_SESSION -> R.string.permission_guide_media_session_title to R.string.permission_guide_media_session_message
        PermissionGuideType.USAGE_STATS -> R.string.permission_guide_usage_stats_title to R.string.permission_guide_usage_stats_message
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(titleRes)) },
        text = { Text(stringResource(messageRes)) },
        confirmButton = {
            Button(onClick = onContinue) {
                Text(stringResource(R.string.permission_guide_continue))
            }
        },
        dismissButton = {
            Row {
                onOpenRestrictedSettingsHelp?.let { openHelp ->
                    TextButton(onClick = openHelp) {
                        Text(stringResource(R.string.restricted_settings_help_entry))
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RestrictedSettingsHelpSheet(
    onDismiss: () -> Unit,
    onOpenAppInfo: () -> Unit,
    onOpenPermissionSettings: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.restricted_settings_help_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(R.string.restricted_settings_help_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            HelpStep(
                number = 1,
                text = stringResource(R.string.restricted_settings_help_step_app_info)
            )
            HelpStep(
                number = 2,
                text = stringResource(R.string.restricted_settings_help_step_menu)
            )
            HelpStep(
                number = 3,
                text = stringResource(R.string.restricted_settings_help_step_return)
            )
            HelpImage(
                resId = R.drawable.restricted_settings_denied,
                caption = stringResource(R.string.restricted_settings_help_denied_caption)
            )
            HelpImage(
                resId = R.drawable.restricted_settings_permission,
                caption = stringResource(R.string.restricted_settings_help_permission_caption)
            )
            HelpImage(
                resId = R.drawable.restricted_settings_app_info,
                caption = stringResource(R.string.restricted_settings_help_app_info_caption)
            )
            Button(
                onClick = onOpenAppInfo,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.restricted_settings_help_open_app_info))
            }
            TextButton(
                onClick = onOpenPermissionSettings,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text(stringResource(R.string.restricted_settings_help_back_to_permission))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BackgroundLimitsHelpSheet(
    onDismiss: () -> Unit,
    onOpenAppInfo: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.background_limits_help_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(R.string.background_limits_help_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            HelpStep(
                number = 1,
                text = stringResource(R.string.background_limits_help_step_battery)
            )
            HelpStep(
                number = 2,
                text = stringResource(R.string.background_limits_help_step_autostart)
            )
            HelpStep(
                number = 3,
                text = stringResource(R.string.background_limits_help_step_lock)
            )
            HelpImage(
                resId = R.drawable.background_limits_battery,
                caption = stringResource(R.string.background_limits_help_battery_caption)
            )
            HelpImage(
                resId = R.drawable.background_limits_app_info,
                caption = stringResource(R.string.background_limits_help_app_info_caption)
            )
            HelpImage(
                resId = R.drawable.background_limits_recent_lock,
                caption = stringResource(R.string.background_limits_help_recent_lock_caption)
            )
            Button(
                onClick = onOpenAppInfo,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.background_limits_help_open_app_info))
            }
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text(stringResource(R.string.background_limits_help_got_it))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppHelpSheet(
    topic: AppHelpTopic,
    onDismiss: () -> Unit,
    @StringRes primaryActionLabelRes: Int,
    onPrimaryAction: () -> Unit,
    @StringRes secondaryActionLabelRes: Int,
    onSecondaryAction: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(topic.titleRes),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(topic.messageRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            topic.stepResIds.forEachIndexed { index, stepRes ->
                HelpStep(
                    number = index + 1,
                    text = stringResource(stepRes)
                )
            }
            Button(
                onClick = onPrimaryAction,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(primaryActionLabelRes))
            }
            TextButton(
                onClick = onSecondaryAction,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text(stringResource(secondaryActionLabelRes))
            }
        }
    }
}

private val AppHelpTopic.titleRes: Int
    @StringRes get() = when (this) {
        AppHelpTopic.AI_OPTIONS -> R.string.ai_options_help_title
        AppHelpTopic.WRONG_JUDGMENT -> R.string.wrong_judgment_help_title
        AppHelpTopic.USAGE_INSTRUCTIONS -> R.string.usage_instructions
    }

private val AppHelpTopic.messageRes: Int
    @StringRes get() = when (this) {
        AppHelpTopic.AI_OPTIONS -> R.string.ai_options_help_message
        AppHelpTopic.WRONG_JUDGMENT -> R.string.wrong_judgment_help_message
        AppHelpTopic.USAGE_INSTRUCTIONS -> R.string.usage_instructions_help_message
    }

private val AppHelpTopic.stepResIds: List<Int>
    @StringRes get() = when (this) {
        AppHelpTopic.AI_OPTIONS -> listOf(
            R.string.ai_options_help_step_seenot_ai,
            R.string.ai_options_help_step_own_key
        )
        AppHelpTopic.WRONG_JUDGMENT -> listOf(
            R.string.wrong_judgment_help_step_current,
            R.string.wrong_judgment_help_step_records,
            R.string.wrong_judgment_help_step_result
        )
        AppHelpTopic.USAGE_INSTRUCTIONS -> listOf(
            R.string.step_1_select_apps,
            R.string.step_2_declare_intent,
            R.string.step_3_ai_guards,
            R.string.step_4_report_misreport
        )
    }

@Composable
private fun HelpStep(
    number: Int,
    text: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = MaterialTheme.shapes.small
        ) {
            Text(
                text = number.toString(),
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun HelpImage(
    resId: Int,
    caption: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Image(
            painter = painterResource(resId),
            contentDescription = caption,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
        )
        Text(
            text = caption,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

internal fun isBatteryOptimizationIgnored(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
    val powerManager = context.getSystemService(PowerManager::class.java) ?: return false
    return powerManager.isIgnoringBatteryOptimizations(context.packageName)
}

internal fun createBatteryOptimizationIntent(context: Context): Intent {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
        return Intent(Settings.ACTION_SETTINGS)
    }

    val requestIntent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
        data = Uri.parse("package:${context.packageName}")
    }
    return if (requestIntent.resolveActivity(context.packageManager) != null) {
        requestIntent
    } else {
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    }
}

internal fun createOverlaySettingsIntent(context: Context): Intent {
    return Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
        data = Uri.parse("package:${context.packageName}")
    }
}

internal fun createAppDetailsSettingsIntent(context: Context): Intent {
    return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.parse("package:${context.packageName}")
    }
}

internal fun createNotificationSettingsIntent(context: Context): Intent {
    return Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    }
}

internal fun createNotificationListenerSettingsIntent(): Intent {
    return Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
}

internal fun createUsageStatsSettingsIntent(): Intent {
    return Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
}
