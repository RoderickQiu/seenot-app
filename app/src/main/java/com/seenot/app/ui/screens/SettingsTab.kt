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
 * Settings Tab
 */
@Composable
fun SettingsTab(
    modifier: Modifier = Modifier,
    accountState: SeenotAccountState = SeenotAccountState.SignedOut,
    onOpenAiSettings: () -> Unit = {},
    onOpenAiOptionsHelp: () -> Unit = {},
    onOpenAccount: () -> Unit = {},
    onSignOutAccount: () -> Unit = {},
    onRefreshAccount: () -> Unit = {},
    onManualSync: (onComplete: () -> Unit) -> Unit = { onComplete -> onComplete() },
    isAccountLoginInFlight: Boolean = false,
    isAccountRefreshInFlight: Boolean = false,
    onHomeTimelineChanged: (Boolean) -> Unit = {},
    onOpenRuleRecords: () -> Unit = {},
    onOpenWrongJudgmentHelp: () -> Unit = {},
    automaticVersionCheckEnabled: Boolean = true,
    versionCheckResponse: SeenotVersionCheckResponse? = null,
    isVersionCheckInFlight: Boolean = false,
    onAutomaticVersionCheckChanged: (Boolean) -> Unit = {},
    onManualVersionCheck: () -> Unit = {},
    onOpenVersionUpdate: (SeenotVersionCheckResponse) -> Unit = {}
) {
    val context = LocalContext.current
    val sessionManager = remember { SessionManager.getInstance(context) }
    val repository = remember { RuleRecordRepository(context) }
    val scope = rememberCoroutineScope()

    var autoStart by remember { mutableStateOf(sessionManager.isAutoStartEnabled()) }
    var saveRuleRecords by remember { mutableStateOf(RuleRecordingPrefs.isEnabled(context)) }
    var showHomeTimeline by remember { mutableStateOf(RuleRecordingPrefs.isHomeTimelineEnabled(context)) }
    var showAnalysisResultToast by remember { mutableStateOf(RuleRecordingPrefs.isAnalysisResultToastEnabled(context)) }
    var intentReminderEnabled by remember { mutableStateOf(IntentReminderPrefs.isEnabled(context)) }
    var noMonitorReminderEnabled by remember { mutableStateOf(NoMonitorReminderPrefs.isEnabled(context)) }
    var intentReminderDelayMs by remember { mutableLongStateOf(IntentReminderPrefs.getDelayMs(context)) }
    var intentReminderDropdownExpanded by remember { mutableStateOf(false) }
    var nonGentleAllowIgnoreOnce by remember {
        mutableStateOf(InterventionDialogPrefs.isNonGentleAllowIgnoreOnceEnabled(context))
    }
    var fixedInterventionEnabled by remember { mutableStateOf(InterventionLevelPrefs.isFixedLevelEnabled(context)) }
    var fixedInterventionLevel by remember { mutableStateOf(InterventionLevelPrefs.getFixedLevel(context)) }
    var fixedInterventionDropdownExpanded by remember { mutableStateOf(false) }
    var screenshotMode by remember { mutableStateOf(RuleRecordingPrefs.getScreenshotMode(context)) }
    var screenshotDropdownExpanded by remember { mutableStateOf(false) }
    var showLogExportDialog by remember { mutableStateOf(false) }
    var showShareExperienceDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val screenshotModeOptions = listOf(
        RuleRecordingPrefs.ScreenshotMode.ALL to R.string.screenshot_mode_all,
        RuleRecordingPrefs.ScreenshotMode.MATCHED_ONLY to R.string.screenshot_mode_matched_only,
        RuleRecordingPrefs.ScreenshotMode.NONE to R.string.screenshot_mode_none
    )
    val selectedScreenshotLabel = screenshotModeOptions.find { it.first == screenshotMode }?.second?.let { context.getString(it) }
        ?: context.getString(screenshotModeOptions.first().second)
    val ownAiSettings = remember { ApiConfig.getOwnKeySettings() }
    val ownAiPresetLabel = remember(ownAiSettings) {
        recommendedModelPresets(ownAiSettings.provider, ownAiSettings.qwenRegion)
            .firstOrNull { it.model == ownAiSettings.model }
            ?.model
            ?: ownAiSettings.model
    }
    val aiButtonLabel = remember(ownAiSettings, ownAiPresetLabel, context) {
        when {
            ownAiSettings.model.isBlank() -> context.getString(R.string.model_not_set)
            ownAiSettings.provider == AiProvider.DASHSCOPE -> "Qwen · $ownAiPresetLabel"
            else -> "${context.getString(ownAiSettings.provider.displayNameResId)} · $ownAiPresetLabel"
        }
    }
    val intentReminderOptions = remember {
        IntentReminderPrefs.supportedDelayOptionsMs
    }
    val selectedIntentReminderDelayLabel = remember(intentReminderDelayMs, context) {
        IntentReminderPrefs.formatDelayLabel(context, intentReminderDelayMs)
    }
    val selectedFixedInterventionLabel = remember(fixedInterventionLevel, context) {
        context.getString(interventionLevelLabel(fixedInterventionLevel))
    }

    var selectedLanguage by remember { mutableStateOf(AppLocalePrefs.getLanguage(context)) }
    var languageDropdownExpanded by remember { mutableStateOf(false) }
    val languageOptions = listOf(
        AppLocalePrefs.LANG_ZH to R.string.language_option_zh,
        AppLocalePrefs.LANG_EN to R.string.language_option_en
    )
    val selectedLanguageLabel = languageOptions.find { it.first == selectedLanguage }?.second?.let { context.getString(it) }
        ?: context.getString(R.string.language_option_zh)
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.clear_records_title)) },
            text = { Text(stringResource(R.string.clear_records_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            repository.clearAllRecords()
                        }
                        showDeleteConfirm = false
                    }
                ) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(24.dp))

        ServiceStatusSection(
            accountState = accountState,
            aiButtonLabel = aiButtonLabel,
            isAiConfigured = ApiConfig.isVisionConfigured(),
            onSignOutAccount = onSignOutAccount,
            onOpenAccount = onOpenAccount,
            onRefreshAccount = onRefreshAccount,
            onManualSync = onManualSync,
            isAccountLoginInFlight = isAccountLoginInFlight,
            isAccountRefreshInFlight = isAccountRefreshInFlight,
            onOpenAiSettings = onOpenAiSettings,
            onOpenAiOptionsHelp = onOpenAiOptionsHelp,
            onOpenPlus = { openSeenotPlusPage(context) }
        )

        Spacer(modifier = Modifier.height(24.dp))

        SettingsSectionCard(
            title = stringResource(R.string.experience_settings),
            description = stringResource(R.string.experience_settings_desc)
        ) {
            Box {
                SettingsDropdownRow(
                    title = stringResource(R.string.language),
                    summary = "",
                    value = selectedLanguageLabel,
                    expanded = languageDropdownExpanded,
                    onClick = { languageDropdownExpanded = !languageDropdownExpanded }
                )
                DropdownMenu(
                    expanded = languageDropdownExpanded,
                    onDismissRequest = { languageDropdownExpanded = false },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    languageOptions.forEach { (code, labelResId) ->
                        DropdownMenuItem(
                            text = { Text(stringResource(labelResId)) },
                            onClick = {
                                if (code != selectedLanguage) {
                                    selectedLanguage = code
                                    AppLocalePrefs.setLanguage(context, code)
                                    // Restart activity to apply language change
                                    (context as? Activity)?.let { activity ->
                                        activity.finish()
                                        activity.startActivity(buildLanguageRestartIntent(activity))
                                    }
                                }
                                languageDropdownExpanded = false
                            }
                        )
                    }
                }
            }
            HorizontalDivider()
            SettingsSwitchRow(
                title = stringResource(R.string.show_today_timeline),
                summary = stringResource(R.string.show_today_timeline_desc),
                checked = showHomeTimeline,
                onCheckedChange = {
                    showHomeTimeline = it
                    RuleRecordingPrefs.setHomeTimelineEnabled(context, it)
                    sessionManager.enqueueGlobalPreferencesSync()
                    onHomeTimelineChanged(it)
                }
            )
            HorizontalDivider()
            SettingsSwitchRow(
                title = stringResource(R.string.auto_start_on_boot),
                summary = stringResource(R.string.auto_start_on_boot_desc),
                checked = autoStart,
                onCheckedChange = {
                    autoStart = it
                    sessionManager.setAutoStartEnabled(it)
                }
            )
            HorizontalDivider()
            SettingsSwitchRow(
                title = stringResource(R.string.intent_reminder_enabled_title),
                summary = stringResource(R.string.intent_reminder_enabled_desc),
                checked = intentReminderEnabled,
                onCheckedChange = {
                    intentReminderEnabled = it
                    IntentReminderPrefs.setEnabled(context, it)
                }
            )
            HorizontalDivider()
            SettingsSwitchRow(
                title = stringResource(R.string.no_monitor_reminder_enabled_title),
                summary = stringResource(R.string.no_monitor_reminder_enabled_desc),
                checked = noMonitorReminderEnabled,
                onCheckedChange = {
                    noMonitorReminderEnabled = it
                    NoMonitorReminderPrefs.setEnabled(context, it)
                }
            )
            if (intentReminderEnabled) {
                HorizontalDivider()
                Box {
                    SettingsDropdownRow(
                        title = stringResource(R.string.intent_reminder_delay_title),
                        summary = stringResource(R.string.intent_reminder_delay_desc),
                        value = selectedIntentReminderDelayLabel,
                        expanded = intentReminderDropdownExpanded,
                        onClick = { intentReminderDropdownExpanded = !intentReminderDropdownExpanded }
                    )
                    DropdownMenu(
                        expanded = intentReminderDropdownExpanded,
                        onDismissRequest = { intentReminderDropdownExpanded = false },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        intentReminderOptions.forEach { delayMs ->
                            DropdownMenuItem(
                                text = { Text(IntentReminderPrefs.formatDelayLabel(context, delayMs)) },
                                onClick = {
                                    intentReminderDelayMs = delayMs
                                    IntentReminderPrefs.setDelayMs(context, delayMs)
                                    intentReminderDropdownExpanded = false
                                }
                            )
                        }
                    }
                }
            }
            HorizontalDivider()
            SettingsSwitchRow(
                title = stringResource(R.string.non_gentle_allow_ignore_once_title),
                summary = stringResource(R.string.non_gentle_allow_ignore_once_desc),
                checked = nonGentleAllowIgnoreOnce,
                onCheckedChange = {
                    nonGentleAllowIgnoreOnce = it
                    InterventionDialogPrefs.setNonGentleAllowIgnoreOnceEnabled(context, it)
                    sessionManager.enqueueGlobalPreferencesSync()
                }
            )
            HorizontalDivider()
            SettingsSwitchRow(
                title = stringResource(R.string.fixed_intervention_level_title),
                summary = stringResource(R.string.fixed_intervention_level_desc),
                checked = fixedInterventionEnabled,
                onCheckedChange = {
                    fixedInterventionEnabled = it
                    InterventionLevelPrefs.setFixedLevelEnabled(context, it)
                    sessionManager.enqueueGlobalPreferencesSync()
                    scope.launch {
                        sessionManager.refreshRunningSessionInterventionLevels()
                    }
                }
            )
            if (fixedInterventionEnabled) {
                HorizontalDivider()
                Box {
                    SettingsDropdownRow(
                        title = stringResource(R.string.fixed_intervention_level_choice_title),
                        summary = stringResource(R.string.fixed_intervention_level_choice_desc),
                        value = selectedFixedInterventionLabel,
                        expanded = fixedInterventionDropdownExpanded,
                        onClick = { fixedInterventionDropdownExpanded = !fixedInterventionDropdownExpanded }
                    )
                    DropdownMenu(
                        expanded = fixedInterventionDropdownExpanded,
                        onDismissRequest = { fixedInterventionDropdownExpanded = false },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        InterventionLevel.entries.forEach { level ->
                            DropdownMenuItem(
                                text = {
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(text = stringResource(interventionLevelLabel(level)))
                                        Text(
                                            text = stringResource(interventionLevelBriefDescription(level)),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                onClick = {
                                    fixedInterventionLevel = level
                                    InterventionLevelPrefs.setFixedLevel(context, level)
                                    sessionManager.enqueueGlobalPreferencesSync()
                                    scope.launch {
                                        sessionManager.refreshRunningSessionInterventionLevels()
                                    }
                                    fixedInterventionDropdownExpanded = false
                                }
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        SettingsSectionCard(
            title = stringResource(R.string.judgment_records),
            description = stringResource(R.string.judgment_records_desc)
        ) {
            SettingsSwitchRow(
                title = stringResource(R.string.save_judgment_records),
                summary = stringResource(R.string.save_judgment_records_desc),
                checked = saveRuleRecords,
                onCheckedChange = {
                    saveRuleRecords = it
                    RuleRecordingPrefs.setEnabled(context, it)
                    sessionManager.enqueueGlobalPreferencesSync()
                }
            )
            if (saveRuleRecords) {
                HorizontalDivider()
                Box {
                    SettingsDropdownRow(
                        title = stringResource(R.string.screenshot_mode_label),
                        summary = stringResource(R.string.screenshot_mode_desc),
                        value = selectedScreenshotLabel,
                        expanded = screenshotDropdownExpanded,
                        onClick = { screenshotDropdownExpanded = !screenshotDropdownExpanded }
                    )
                    DropdownMenu(
                        expanded = screenshotDropdownExpanded,
                        onDismissRequest = { screenshotDropdownExpanded = false },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        screenshotModeOptions.forEach { (mode, labelResId) ->
                            DropdownMenuItem(
                                text = { Text(stringResource(labelResId)) },
                                onClick = {
                                    screenshotMode = mode
                                    RuleRecordingPrefs.setScreenshotMode(context, mode)
                                    sessionManager.enqueueGlobalPreferencesSync()
                                    screenshotDropdownExpanded = false
                                }
                            )
                        }
                    }
                }
            }
            HorizontalDivider()
            SettingsSwitchRow(
                title = stringResource(R.string.notify_each_judgment),
                summary = stringResource(R.string.notify_each_judgment_desc),
                checked = showAnalysisResultToast,
                onCheckedChange = {
                    showAnalysisResultToast = it
                    RuleRecordingPrefs.setAnalysisResultToastEnabled(context, it)
                    sessionManager.enqueueGlobalPreferencesSync()
                }
            )
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onOpenRuleRecords,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.History, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.view_records))
                }
                OutlinedButton(
                    onClick = { showDeleteConfirm = true },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.clear_records))
                }
            }
            InlineHelpLink(
                text = stringResource(R.string.wrong_judgment_help_entry),
                onClick = onOpenWrongJudgmentHelp,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        SettingsSectionCard(
            title = stringResource(R.string.export_section),
            description = stringResource(R.string.export_section_desc)
        ) {
            OutlinedButton(
                onClick = { showLogExportDialog = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Download, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.export_data))
            }
        }

        if (showLogExportDialog) {
            ExportDialog(
                onDismiss = { showLogExportDialog = false }
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        SettingsSectionCard(
            title = stringResource(R.string.support_seenot_section),
            description = stringResource(R.string.support_seenot_section_desc)
        ) {
            SettingsActionRow(
                icon = Icons.AutoMirrored.Filled.Send,
                title = stringResource(R.string.share_experience_title),
                summary = stringResource(R.string.share_experience_desc),
                onClick = { showShareExperienceDialog = true }
            )
        }

        if (showShareExperienceDialog) {
            ShareExperienceDialog(
                onDismiss = { showShareExperienceDialog = false }
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        SettingsSectionCard(
            title = stringResource(R.string.about_section),
            description = null
        ) {
            AboutSection(
                automaticVersionCheckEnabled = automaticVersionCheckEnabled,
                versionCheckResponse = versionCheckResponse,
                isVersionCheckInFlight = isVersionCheckInFlight,
                onAutomaticVersionCheckChanged = onAutomaticVersionCheckChanged,
                onManualVersionCheck = onManualVersionCheck,
                onOpenVersionUpdate = onOpenVersionUpdate,
                onOpenOfficialSite = { openSeenotOfficialSitePage(context) },
                onOpenGithub = { openExternalUrl(context, "https://github.com/RoderickQiu/seenot-app") },
                onOpenCreatorHomepage = { openExternalUrl(context, "https://r-q.name/") }
            )
        }
    }
}

@Composable
private fun ShareExperienceDialog(
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
        },
        title = {
            Text(stringResource(R.string.share_experience_dialog_title))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.share_experience_dialog_intro),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = stringResource(R.string.share_experience_dialog_review),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = stringResource(R.string.share_experience_dialog_limit),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        }
    )
}

@Composable
private fun AboutSection(
    automaticVersionCheckEnabled: Boolean,
    versionCheckResponse: SeenotVersionCheckResponse?,
    isVersionCheckInFlight: Boolean,
    onAutomaticVersionCheckChanged: (Boolean) -> Unit,
    onManualVersionCheck: () -> Unit,
    onOpenVersionUpdate: (SeenotVersionCheckResponse) -> Unit,
    onOpenOfficialSite: () -> Unit,
    onOpenGithub: () -> Unit,
    onOpenCreatorHomepage: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.about_app_version, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.about_app_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    HorizontalDivider()

    SettingsSwitchRow(
        title = stringResource(R.string.automatic_update_check_title),
        summary = stringResource(R.string.automatic_update_check_desc),
        checked = automaticVersionCheckEnabled,
        onCheckedChange = onAutomaticVersionCheckChanged
    )

    VersionCheckStatusRow(
        response = versionCheckResponse,
        isChecking = isVersionCheckInFlight,
        onManualVersionCheck = onManualVersionCheck,
        onOpenVersionUpdate = onOpenVersionUpdate
    )

    HorizontalDivider()

    AboutLinkRow(
        icon = Icons.Filled.Public,
        title = stringResource(R.string.about_official_site),
        subtitle = "seenot.site",
        onClick = onOpenOfficialSite
    )
    AboutLinkRow(
        icon = Icons.Filled.Code,
        title = stringResource(R.string.about_github),
        subtitle = stringResource(R.string.about_github_desc),
        onClick = onOpenGithub
    )
    AboutLinkRow(
        icon = Icons.Filled.Person,
        title = stringResource(R.string.about_creator_homepage),
        subtitle = "r-q.name",
        onClick = onOpenCreatorHomepage
    )
}

@Composable
private fun VersionCheckStatusRow(
    response: SeenotVersionCheckResponse?,
    isChecking: Boolean,
    onManualVersionCheck: () -> Unit,
    onOpenVersionUpdate: (SeenotVersionCheckResponse) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = if (response?.updateAvailable == true) Icons.Filled.SystemUpdate else Icons.Filled.Update,
                contentDescription = null,
                tint = if (response?.updateAvailable == true) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = versionCheckStatusTitle(response),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = versionCheckStatusSummary(response),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(
                onClick = onManualVersionCheck,
                enabled = !isChecking
            ) {
                Text(
                    if (isChecking) {
                        stringResource(R.string.version_check_checking_action)
                    } else {
                        stringResource(R.string.version_check_now_action)
                    }
                )
            }
        }

        if (response?.updateAvailable == true) {
            OutlinedButton(
                onClick = { onOpenVersionUpdate(response) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.version_check_download_action, response.latestVersion))
            }
        }
    }
}

@Composable
internal fun VersionUpdateDialog(
    response: SeenotVersionCheckResponse,
    onDismiss: () -> Unit,
    onOpenDownload: (() -> Unit)?
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(Icons.Filled.SystemUpdate, contentDescription = null)
        },
        title = {
            Text(stringResource(R.string.version_update_dialog_title, response.latestVersion))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = response.message?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.version_update_dialog_default_message),
                    style = MaterialTheme.typography.bodyMedium
                )
                if (!response.publishedAt.isNullOrBlank()) {
                    Text(
                        text = stringResource(R.string.version_update_dialog_published_at, response.publishedAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            if (onOpenDownload != null) {
                TextButton(onClick = onOpenDownload) {
                    Text(stringResource(R.string.version_update_dialog_download_action))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.version_update_dialog_later_action))
            }
        }
    )
}

@Composable
private fun versionCheckStatusTitle(response: SeenotVersionCheckResponse?): String {
    return when {
        response?.updateAvailable == true -> stringResource(R.string.version_check_update_available_title)
        response != null -> stringResource(R.string.version_check_up_to_date_title)
        else -> stringResource(R.string.version_check_status_title)
    }
}

@Composable
private fun versionCheckStatusSummary(response: SeenotVersionCheckResponse?): String {
    return when {
        response?.updateAvailable == true && !response.message.isNullOrBlank() -> response.message
        response?.updateAvailable == true -> stringResource(R.string.version_check_update_available_desc, response.latestVersion)
        response != null -> stringResource(R.string.version_check_up_to_date_desc)
        else -> stringResource(R.string.version_check_status_desc)
    }
}

@Composable
private fun SettingsActionRow(
    icon: ImageVector,
    title: String,
    summary: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AboutLinkRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    description: String?,
    content: @Composable ColumnScope.() -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            if (!description.isNullOrBlank()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            content()
        }
    }
}

internal fun buildLanguageRestartIntent(activity: Activity): Intent {
    return Intent(activity, activity::class.java)
}

@Composable
private fun ServiceStatusSection(
    accountState: SeenotAccountState,
    aiButtonLabel: String,
    isAiConfigured: Boolean,
    onSignOutAccount: () -> Unit,
    onOpenAccount: () -> Unit,
    onRefreshAccount: () -> Unit,
    onManualSync: (onComplete: () -> Unit) -> Unit,
    isAccountLoginInFlight: Boolean,
    isAccountRefreshInFlight: Boolean,
    onOpenAiSettings: () -> Unit,
    onOpenAiOptionsHelp: () -> Unit,
    onOpenPlus: () -> Unit
) {
    val snapshot = (accountState as? SeenotAccountState.Ready)?.snapshot
    val isPlus = snapshot?.hasPlus == true
    val usesSeenotAi = ApiConfig.getAiSource() == AiSource.SEENOT_AI
    val usesOwnSetup = isAiConfigured && !usesSeenotAi
    var accountMenuExpanded by remember { mutableStateOf(false) }
    var syncRefreshTicker by remember { mutableIntStateOf(0) }
    var manualSyncInFlight by remember { mutableStateOf(false) }
    val lastSyncedAtMs = remember(syncRefreshTicker, accountState) {
        SeenotAccountSession.getLastSyncedAtMs()
    }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (isPlus) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (isPlus) Icons.Default.WorkspacePremium else Icons.Default.Person,
                    contentDescription = null,
                    tint = if (isPlus) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = primaryServiceTitle(usesSeenotAi, usesOwnSetup),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = serviceStatusDescription(accountState, usesSeenotAi, isAiConfigured),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val topBadgeLabel = when {
                    isPlus -> stringResource(R.string.plus_badge)
                    snapshot != null -> stringResource(R.string.free_badge)
                    else -> null
                }
                if (topBadgeLabel != null) {
                    Spacer(modifier = Modifier.width(12.dp))
                    ServicePill(label = topBadgeLabel)
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ServiceStatusRow(
                    icon = Icons.Default.AccountCircle,
                    title = stringResource(R.string.account_status_label),
                    value = accountStatusLabel(accountState),
                    supporting = accountStatusSupporting(accountState),
                    actionContent = {
                        if (accountState is SeenotAccountState.SignedOut) {
                            Button(
                                onClick = onOpenAccount,
                                enabled = !isAccountLoginInFlight
                            ) {
                                Text(
                                    text = if (isAccountLoginInFlight) {
                                        stringResource(R.string.account_login_starting_action)
                                    } else {
                                        stringResource(R.string.sign_in_account_action)
                                    }
                                )
                            }
                        } else if (accountState is SeenotAccountState.Error) {
                            Button(
                                onClick = onRefreshAccount,
                                enabled = !isAccountRefreshInFlight
                            ) {
                                Text(
                                    text = if (isAccountRefreshInFlight) {
                                        stringResource(R.string.account_refreshing_action)
                                    } else {
                                        stringResource(R.string.account_refresh_action)
                                    }
                                )
                            }
                        } else if (accountState is SeenotAccountState.Ready) {
                            val accountEmail = accountState.snapshot.auth.user.email
                                ?.trim()
                                ?.takeIf { it.isNotBlank() }
                                ?: stringResource(R.string.account_email_unavailable)
                            Box {
                                Button(onClick = { accountMenuExpanded = true }) {
                                    Text(text = stringResource(R.string.account_actions_action))
                                }
                                DropdownMenu(
                                    expanded = accountMenuExpanded,
                                    onDismissRequest = { accountMenuExpanded = false }
                                ) {
                                    DropdownMenuItem(
                                        text = {
                                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                Text(stringResource(R.string.account_email_label))
                                                Text(
                                                    text = accountEmail,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        },
                                        onClick = {},
                                        enabled = false
                                    )
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                stringResource(
                                                    if (isPlus) {
                                                        R.string.renew_plus_action
                                                    } else {
                                                        R.string.subscribe_plus_action
                                                    }
                                                )
                                            )
                                        },
                                        onClick = {
                                            accountMenuExpanded = false
                                            onOpenAccount()
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.manage_or_delete_account_action)) },
                                        onClick = {
                                            accountMenuExpanded = false
                                            onOpenAccount()
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.sign_out_local_account_action)) },
                                        onClick = {
                                            accountMenuExpanded = false
                                            onSignOutAccount()
                                        }
                                    )
                                }
                            }
                        }
                    }
                )
                HorizontalDivider()
                if (isPlus) {
                    ServiceStatusRow(
                        icon = Icons.Default.Sync,
                        title = stringResource(R.string.plus_sync_status_label),
                        value = "",
                        supporting = syncStatusSupporting(lastSyncedAtMs),
                        actionContent = {
                            Button(
                                onClick = {
                                    manualSyncInFlight = true
                                    onManualSync {
                                        manualSyncInFlight = false
                                        syncRefreshTicker++
                                    }
                                },
                                enabled = !manualSyncInFlight
                            ) {
                                Text(
                                    text = if (manualSyncInFlight) {
                                        stringResource(R.string.plus_syncing_action)
                                    } else {
                                        stringResource(R.string.plus_sync_now_action)
                                    }
                                )
                            }
                        }
                    )
                    HorizontalDivider()
                }
                ServiceStatusRow(
                    icon = Icons.Default.AutoAwesome,
                    title = stringResource(R.string.ai_plan_label),
                    value = "",
                    supporting = aiSourceSupporting(usesSeenotAi, isAiConfigured, aiButtonLabel),
                    actionLabel = stringResource(R.string.adjust_ai_setup_action),
                    onActionClick = onOpenAiSettings
                )
                InlineHelpLink(
                    text = stringResource(R.string.view_ai_options_help),
                    onClick = onOpenAiOptionsHelp,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }

        }
    }

    if (!isPlus) {
        Spacer(modifier = Modifier.height(12.dp))
        PlusUpgradeHintCard(
            onOpenPlus = onOpenPlus,
            enabled = accountState !is SeenotAccountState.Loading
        )
    }
}

@Composable
private fun ServiceStatusRow(
    icon: ImageVector,
    title: String,
    value: String,
    supporting: String,
    actionLabel: String? = null,
    onActionClick: (() -> Unit)? = null,
    actionContent: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.labelLarge)
            Text(text = supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (actionContent != null) {
            Spacer(modifier = Modifier.width(12.dp))
            actionContent()
        } else if (!actionLabel.isNullOrBlank() && onActionClick != null) {
            Spacer(modifier = Modifier.width(12.dp))
            Button(onClick = onActionClick) {
                Text(text = actionLabel)
            }
        } else {
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun ServicePill(label: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun primaryServiceTitle(usesSeenotAi: Boolean, usesOwnSetup: Boolean): String {
    return when {
        usesSeenotAi -> stringResource(R.string.seenot_ai_enabled_title)
        usesOwnSetup -> stringResource(R.string.local_setup_active_title)
        else -> stringResource(R.string.choose_ai_plan_title)
    }
}

@Composable
private fun serviceStatusDescription(
    accountState: SeenotAccountState,
    usesSeenotAi: Boolean,
    isAiConfigured: Boolean
): String {
    return when {
        usesSeenotAi -> stringResource(R.string.service_status_seenot_ai_desc)
        (accountState as? SeenotAccountState.Ready)?.snapshot?.hasPlus == true -> stringResource(R.string.service_status_plus_desc)
        isAiConfigured -> stringResource(R.string.service_status_own_key_desc)
        accountState is SeenotAccountState.Loading -> stringResource(R.string.service_status_loading_desc)
        else -> stringResource(R.string.service_status_local_desc)
    }
}

@Composable
private fun PlusUpgradeHintCard(
    onOpenPlus: () -> Unit,
    enabled: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.WorkspacePremium,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.plus_hint_title),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(R.string.plus_hint_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(
                onClick = onOpenPlus,
                enabled = enabled
            ) {
                Text(stringResource(R.string.learn_plus_action))
            }
        }
    }
}

@Composable
private fun accountStatusLabel(accountState: SeenotAccountState): String {
    return when (accountState) {
        SeenotAccountState.Loading -> stringResource(R.string.status_loading)
        SeenotAccountState.SignedOut -> ""
        is SeenotAccountState.Error -> stringResource(R.string.status_needs_refresh)
        is SeenotAccountState.Ready -> if (accountState.snapshot.hasPlus) stringResource(R.string.plus_badge) else stringResource(R.string.free_badge)
    }
}

@Composable
private fun accountStatusSupporting(accountState: SeenotAccountState): String {
    return when (accountState) {
        SeenotAccountState.Loading -> stringResource(R.string.account_status_loading_desc)
        SeenotAccountState.SignedOut -> stringResource(R.string.account_status_signed_out_desc)
        is SeenotAccountState.Error -> {
            val fallback = stringResource(R.string.account_status_update_problem_desc)
            accountState.message.takeIf { it.isNotBlank() } ?: fallback
        }
        is SeenotAccountState.Ready -> {
            val entitlement = accountState.snapshot.entitlement
            if (accountState.snapshot.hasPlus) {
                entitlement?.currentPeriodEnd?.let {
                    stringResource(R.string.account_status_plus_until_desc, formatEntitlementDate(it))
                } ?: stringResource(R.string.account_status_plus_desc)
            } else if (entitlement != null) {
                stringResource(
                    R.string.account_status_free_with_devices_desc,
                    entitlement.linkedDeviceCount,
                    entitlement.deviceLimit
                )
            } else {
                stringResource(R.string.account_status_free_desc)
            }
        }
    }
}

@Composable
private fun syncStatusSupporting(lastSyncedAtMs: Long): String {
    if (lastSyncedAtMs <= 0L) {
        return stringResource(R.string.plus_sync_never_desc)
    }
    val context = LocalContext.current
    val formattedTime = DateUtils.formatDateTime(
        context,
        lastSyncedAtMs,
        DateUtils.FORMAT_SHOW_DATE or
            DateUtils.FORMAT_SHOW_TIME or
            DateUtils.FORMAT_ABBREV_MONTH or
            DateUtils.FORMAT_ABBREV_RELATIVE
    )
    return stringResource(R.string.plus_sync_last_synced_desc, formattedTime)
}

private fun formatEntitlementDate(value: String): String {
    val instant = runCatching { Instant.parse(value) }.getOrNull() ?: return value
    return DateTimeFormatter
        .ofLocalizedDate(FormatStyle.MEDIUM)
        .withZone(ZoneId.systemDefault())
        .format(instant)
}

@Composable
private fun aiSourceLabel(usesSeenotAi: Boolean, isAiConfigured: Boolean): String {
    return when {
        usesSeenotAi -> stringResource(R.string.seenot_ai_label)
        isAiConfigured -> stringResource(R.string.own_api_key_label)
        else -> stringResource(R.string.not_enabled_label)
    }
}

@Composable
private fun aiSourceSupporting(usesSeenotAi: Boolean, isAiConfigured: Boolean, aiButtonLabel: String): String {
    return when {
        usesSeenotAi -> stringResource(R.string.ai_source_seenot_ai_desc)
        isAiConfigured -> stringResource(R.string.ai_source_own_key_desc, aiButtonLabel)
        else -> stringResource(R.string.ai_source_missing_desc)
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun SettingsDropdownRow(
    title: String,
    summary: String,
    value: String,
    expanded: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Icon(
                imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
