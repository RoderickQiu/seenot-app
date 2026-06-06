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
 * App Rules Dialog - Edit historical and preset rules for an app
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRulesDialog(
    app: AppInfo,
    sessionManager: SessionManager,
    onSyncNeeded: () -> Unit = {},
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val uiScope = rememberCoroutineScope()
    val appHintRepo = remember { AppHintRepository(context) }

    var historyRules by remember { mutableStateOf<List<List<SessionConstraint>>>(emptyList()) }
    var presetRules by remember { mutableStateOf<List<SessionConstraint>>(emptyList()) }
    var lastIntentRules by remember { mutableStateOf<List<SessionConstraint>>(emptyList()) }
    var appEntryIntentMode by remember { mutableStateOf(sessionManager.getAppEntryIntentMode(app.packageName)) }
    var showAddPresetDialog by remember { mutableStateOf(false) }
    var editingRuleIndex by remember { mutableStateOf<Int?>(null) }
    var editingPresetIndex by remember { mutableStateOf<Int?>(null) }

    var hints by remember { mutableStateOf<List<com.seenot.app.data.model.AppHint>>(emptyList()) }
    var showAddHintDialog by remember { mutableStateOf(false) }
    var newHintText by remember { mutableStateOf("") }
    var editingHint by remember { mutableStateOf<com.seenot.app.data.model.AppHint?>(null) }
    var editingHintText by remember { mutableStateOf("") }
    var selectedHintScopeType by remember { mutableStateOf(AppHintScopeType.INTENT_SPECIFIC) }
    var selectedHintIntentId by remember { mutableStateOf<String?>(null) }
    var selectedHintIntentLabel by remember { mutableStateOf<String?>(null) }

    suspend fun reloadHints() {
        hints = appHintRepo.getHintsForPackage(app.packageName)
    }

    fun savePresetRulesAndSyncSession(rules: List<SessionConstraint>) {
        val previousRules = presetRules
        presetRules = rules
        sessionManager.savePresetRules(app.packageName, rules)
        uiScope.launch {
            sessionManager.syncActiveSessionConstraintEditsForApp(
                packageName = app.packageName,
                originalConstraints = previousRules,
                updatedConstraints = rules
            )
            onSyncNeeded()
        }
    }

    LaunchedEffect(app.packageName) {
        val loadedPresetRules = sessionManager.loadPresetRules(app.packageName)
        presetRules = loadedPresetRules

        val loadedHistoryRules = sessionManager.loadIntentHistory(app.packageName)
        val presetFingerprints = loadedPresetRules.map { sessionManager.getConstraintNameFingerprint(listOf(it)) }.toSet()
        historyRules = loadedHistoryRules.filter { history ->
            val fingerprint = sessionManager.getConstraintNameFingerprint(history)
            fingerprint !in presetFingerprints
        }
        lastIntentRules = sessionManager.loadLastIntent(app.packageName).orEmpty()
        appEntryIntentMode = sessionManager.getAppEntryIntentMode(app.packageName)
        reloadHints()
    }

    val intentOptionsContext = LocalContext.current
    val intentOptions = remember(presetRules, historyRules, lastIntentRules, hints) {
        buildHintIntentOptions(
            context = intentOptionsContext,
            presetRules = presetRules,
            historyRules = historyRules,
            lastIntentRules = lastIntentRules,
            existingHints = hints
        )
    }

    if (showAddHintDialog) {
        val dialogContext = LocalContext.current
        AlertDialog(
            onDismissRequest = {
                showAddHintDialog = false
                newHintText = ""
                selectedHintScopeType = AppHintScopeType.INTENT_SPECIFIC
                selectedHintIntentId = null
                selectedHintIntentLabel = null
            },
            title = { Text(stringResource(R.string.add_ai_supplement_rule)) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.rule_scope_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    HintScopeSelector(
                        selectedScopeType = selectedHintScopeType,
                        onScopeTypeSelected = { selectedHintScopeType = it }
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    if (selectedHintScopeType == AppHintScopeType.INTENT_SPECIFIC) {
                        HintIntentSelector(
                            options = intentOptions,
                            selectedIntentId = selectedHintIntentId,
                            onIntentSelected = {
                                selectedHintIntentId = it.intentId
                                selectedHintIntentLabel = it.intentLabel
                            }
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                    OutlinedTextField(
                        value = newHintText,
                        onValueChange = { newHintText = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(stringResource(R.string.rule_example_dont_identify_as_recommend)) },
                        minLines = 2,
                        maxLines = 4
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val hintTextToSave = newHintText.trim()
                        val targetScope: Triple<String?, String?, String?> = when (selectedHintScopeType) {
                            AppHintScopeType.APP_GENERAL -> Triple(
                                buildAppGeneralScopeKey(app.packageName),
                                buildAppGeneralScopeKey(app.packageName),
                                buildAppGeneralScopeLabel(dialogContext)
                            )
                            AppHintScopeType.INTENT_SPECIFIC -> Triple(
                                selectedHintIntentId,
                                selectedHintIntentId,
                                selectedHintIntentLabel
                            )
                        }
                        val (scopeKey, intentId, intentLabel) = targetScope
                        if (hintTextToSave.isNotBlank() && scopeKey != null && intentId != null && intentLabel != null) {
                            uiScope.launch {
                                appHintRepo.addHintFromFeedback(
                                    packageName = app.packageName,
                                    scopeType = selectedHintScopeType,
                                    scopeKey = scopeKey,
                                    intentId = intentId,
                                    intentLabel = intentLabel,
                                    hintText = hintTextToSave
                                )
                                reloadHints()
                            }
                        }
                        showAddHintDialog = false
                        newHintText = ""
                        selectedHintScopeType = AppHintScopeType.INTENT_SPECIFIC
                        selectedHintIntentId = null
                        selectedHintIntentLabel = null
                    },
                    enabled = selectedHintScopeType == AppHintScopeType.APP_GENERAL || selectedHintIntentId != null
                ) {
                    Text(stringResource(R.string.add))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showAddHintDialog = false
                        newHintText = ""
                        selectedHintScopeType = AppHintScopeType.INTENT_SPECIFIC
                        selectedHintIntentId = null
                        selectedHintIntentLabel = null
                    }
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (editingHint != null) {
        val hint = editingHint!!
        val editHintContext = LocalContext.current
        AlertDialog(
            onDismissRequest = {
                editingHint = null
                editingHintText = ""
                selectedHintScopeType = AppHintScopeType.INTENT_SPECIFIC
                selectedHintIntentId = null
                selectedHintIntentLabel = null
            },
            title = { Text(stringResource(R.string.edit_ai_supplement_rule)) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.rule_scope_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    HintScopeSelector(
                        selectedScopeType = selectedHintScopeType,
                        onScopeTypeSelected = { selectedHintScopeType = it }
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    if (selectedHintScopeType == AppHintScopeType.INTENT_SPECIFIC) {
                        HintIntentSelector(
                            options = intentOptions,
                            selectedIntentId = selectedHintIntentId ?: hint.intentId,
                            onIntentSelected = {
                                selectedHintIntentId = it.intentId
                                selectedHintIntentLabel = it.intentLabel
                            }
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                    OutlinedTextField(
                        value = editingHintText,
                        onValueChange = { editingHintText = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(stringResource(R.string.rule_example_dont_identify_as_recommend)) },
                        minLines = 2,
                        maxLines = 4
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val hintTextToSave = editingHintText.trim()
                        val targetScope: Triple<String, String, String> = when (selectedHintScopeType) {
                            AppHintScopeType.APP_GENERAL -> Triple(
                                buildAppGeneralScopeKey(app.packageName),
                                buildAppGeneralScopeKey(app.packageName),
                                buildAppGeneralScopeLabel(editHintContext)
                            )
                            AppHintScopeType.INTENT_SPECIFIC -> Triple(
                                selectedHintIntentId ?: hint.intentId,
                                selectedHintIntentId ?: hint.intentId,
                                selectedHintIntentLabel ?: hint.intentLabel
                            )
                        }
                        val (scopeKey, targetIntentId, targetIntentLabel) = targetScope
                        if (hintTextToSave.isNotBlank()) {
                            uiScope.launch {
                                appHintRepo.updateHint(
                                    hintId = hint.id,
                                    hintText = hintTextToSave,
                                    scopeType = selectedHintScopeType,
                                    scopeKey = scopeKey,
                                    intentId = targetIntentId,
                                    intentLabel = targetIntentLabel
                                )
                                reloadHints()
                            }
                        }
                        editingHint = null
                        editingHintText = ""
                        selectedHintScopeType = AppHintScopeType.INTENT_SPECIFIC
                        selectedHintIntentId = null
                        selectedHintIntentLabel = null
                    }
                ) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        editingHint = null
                        editingHintText = ""
                        selectedHintScopeType = AppHintScopeType.INTENT_SPECIFIC
                        selectedHintIntentId = null
                        selectedHintIntentLabel = null
                    }
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.app_intents_title, app.name)) },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    AppEntryIntentModeSection(
                        selectedMode = appEntryIntentMode,
                        presetRules = presetRules,
                        onModeSelected = { mode ->
                            if (mode == AppEntryIntentMode.ASK_EVERY_TIME && presetRules.any { it.isDefault }) {
                                val newPresets = presetRules.map { it.copy(isDefault = false) }
                                savePresetRulesAndSyncSession(newPresets)
                            }
                            if (mode == AppEntryIntentMode.USE_PRESET && presetRules.none { it.isDefault }) {
                                val firstPreset = presetRules.firstOrNull()
                                if (firstPreset != null) {
                                    val newPresets = presetRules.map { rule ->
                                        rule.copy(isDefault = rule.id == firstPreset.id)
                                    }
                                    savePresetRulesAndSyncSession(newPresets)
                                }
                            }
                            appEntryIntentMode = mode
                            sessionManager.setAppEntryIntentMode(app.packageName, mode)
                            onSyncNeeded()
                        },
                        onPresetSelected = { presetId ->
                            val newPresets = presetRules.map { rule ->
                                rule.copy(isDefault = rule.id == presetId)
                            }
                            appEntryIntentMode = AppEntryIntentMode.USE_PRESET
                            savePresetRulesAndSyncSession(newPresets)
                            sessionManager.setAppEntryIntentMode(app.packageName, AppEntryIntentMode.USE_PRESET)
                            onSyncNeeded()
                        }
                    )
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.preset_intents),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        TextButton(onClick = { showAddPresetDialog = true }) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.add_preset_intent))
                        }
                    }
                }

                item {
                    if (presetRules.isEmpty()) {
                        Text(
                            text = stringResource(R.string.no_preset_intents_yet),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                items(presetRules.size) { index ->
                    val constraint = presetRules[index]
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { editingPresetIndex = index },
                        colors = CardDefaults.cardColors(
                            containerColor = if (constraint.isDefault) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surface
                            }
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${stringResource(constraintTypeLabel(constraint.type))}: ${constraint.description}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                IconButton(
                                    onClick = {
                                        val newPresets = presetRules.map { rule ->
                                            if (rule.id == constraint.id) {
                                                rule.copy(isDefault = !rule.isDefault)
                                            } else {
                                                rule.copy(isDefault = false)
                                            }
                                        }
                                        appEntryIntentMode = if (newPresets.any { it.isDefault }) {
                                            AppEntryIntentMode.USE_PRESET
                                        } else {
                                            AppEntryIntentMode.ASK_EVERY_TIME
                                        }
                                        savePresetRulesAndSyncSession(newPresets)
                                        sessionManager.setAppEntryIntentMode(app.packageName, appEntryIntentMode)
                                        onSyncNeeded()
                                    },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = if (constraint.isDefault) Icons.Filled.Star else Icons.Outlined.StarBorder,
                                        contentDescription = if (constraint.isDefault) stringResource(R.string.unset_as_default) else stringResource(R.string.set_as_default),
                                        tint = if (constraint.isDefault) Color(0xFFFFB300) else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        val newPresets = presetRules.toMutableList().apply { removeAt(index) }
                                        if (newPresets.none { it.isDefault } && appEntryIntentMode == AppEntryIntentMode.USE_PRESET) {
                                            appEntryIntentMode = AppEntryIntentMode.ASK_EVERY_TIME
                                            sessionManager.setAppEntryIntentMode(app.packageName, appEntryIntentMode)
                                            onSyncNeeded()
                                        }
                                        savePresetRulesAndSyncSession(newPresets)
                                    },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = stringResource(R.string.delete),
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.history_intents),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                item {
                    if (historyRules.isEmpty()) {
                        Text(
                            text = stringResource(R.string.no_history_intents_yet),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                items(historyRules.size) { index ->
                    val constraints = historyRules[index]
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { editingRuleIndex = index }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                constraints.forEach { constraint ->
                                    Text(
                                        text = "${stringResource(constraintTypeLabel(constraint.type))}: ${constraint.description}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                            IconButton(
                                onClick = {
                                    val newHistory = historyRules.toMutableList().apply { removeAt(index) }
                                    historyRules = newHistory
                                    sessionManager.saveIntentHistory(app.packageName, newHistory)
                                    onSyncNeeded()
                                },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = stringResource(R.string.delete),
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.ai_supplement_rules),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        TextButton(
                            onClick = {
                                selectedHintScopeType = AppHintScopeType.INTENT_SPECIFIC
                                selectedHintIntentId = intentOptions.firstOrNull()?.intentId
                                selectedHintIntentLabel = intentOptions.firstOrNull()?.intentLabel
                                showAddHintDialog = true
                            }
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.add))
                        }
                    }
                }

                item {
                    Text(
                        text = stringResource(R.string.supplement_rules_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                item {
                    if (hints.isEmpty()) {
                        Text(
                            text = stringResource(R.string.no_supplement_rules_yet),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                val appGeneralHints = hints
                    .filter { it.scopeType == AppHintScopeType.APP_GENERAL }
                    .sortedByDescending { it.updatedAt }

                val groupedIntentHints = hints
                    .filter { it.scopeType == AppHintScopeType.INTENT_SPECIFIC }
                    .groupBy { it.scopeKey }
                    .values
                    .sortedByDescending { group -> group.maxOfOrNull { it.updatedAt } ?: 0L }

                if (appGeneralHints.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.scope_app_general),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                    items(appGeneralHints.size) { index ->
                        val hint = appGeneralHints[index]
                        HintCard(
                            hint = hint,
                            onEdit = {
                                editingHint = hint
                                editingHintText = hint.hintText
                                selectedHintScopeType = hint.scopeType
                                selectedHintIntentId = hint.intentId
                                selectedHintIntentLabel = hint.intentLabel
                            },
                            onDelete = {
                                uiScope.launch {
                                    appHintRepo.deleteHint(hint.id)
                                    reloadHints()
                                }
                            }
                        )
                    }
                }

                if (groupedIntentHints.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.specific_intent_scope),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }

                groupedIntentHints.forEach { group ->
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = displayHintIntentLabel(LocalContext.current, group.first().intentLabel),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }

                    items(group.size) { index ->
                        val hint = group[index]
                        HintCard(
                            hint = hint,
                            onEdit = {
                                editingHint = hint
                                editingHintText = hint.hintText
                                selectedHintScopeType = hint.scopeType
                                selectedHintIntentId = hint.intentId
                                selectedHintIntentLabel = hint.intentLabel
                            },
                            onDelete = {
                                uiScope.launch {
                                    appHintRepo.deleteHint(hint.id)
                                    reloadHints()
                                }
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        }
    )

    if (showAddPresetDialog) {
        AddPresetRuleDialog(
            onDismiss = { showAddPresetDialog = false },
            onConfirm = { newRule ->
                val newPresets = presetRules + newRule
                savePresetRulesAndSyncSession(newPresets)
                showAddPresetDialog = false
            }
        )
    }

    editingRuleIndex?.let { index ->
        if (index < historyRules.size) {
            EditHistoryRuleDialog(
                constraints = historyRules[index],
                onDismiss = { editingRuleIndex = null },
                onSaveAsPreset = { updatedConstraints ->
                    val originalConstraints = historyRules[index]
                    val existingFingerprints = presetRules
                        .map { sessionManager.getConstraintNameFingerprint(listOf(it)) }
                        .toMutableSet()
                    val constraintsToAdd = updatedConstraints.mapNotNull { constraint ->
                        val fingerprint = sessionManager.getConstraintNameFingerprint(listOf(constraint))
                        if (fingerprint in existingFingerprints) {
                            null
                        } else {
                            existingFingerprints += fingerprint
                            constraint.copy(
                                id = java.util.UUID.randomUUID().toString(),
                                isDefault = false,
                                isActive = true
                            )
                        }
                    }
                    if (constraintsToAdd.isNotEmpty()) {
                        val newPresets = presetRules + constraintsToAdd
                        savePresetRulesAndSyncSession(newPresets)
                        uiScope.launch {
                            sessionManager.syncActiveSessionConstraintEditsForApp(
                                packageName = app.packageName,
                                originalConstraints = originalConstraints,
                                updatedConstraints = constraintsToAdd,
                                allowPositionFallback = true
                            )
                        }
                    }
                    uiScope.launch {
                        rebindHintsForEditedConstraints(
                            appHintRepo = appHintRepo,
                            packageName = app.packageName,
                            originalConstraints = originalConstraints,
                            updatedConstraints = updatedConstraints
                        )
                        reloadHints()
                    }
                    val newHistory = historyRules.toMutableList().apply { removeAt(index) }
                    historyRules = newHistory
                    sessionManager.saveIntentHistory(app.packageName, newHistory)
                    onSyncNeeded()
                    editingRuleIndex = null
                }
            )
        }
    }

    editingPresetIndex?.let { index ->
        if (index < presetRules.size) {
            EditPresetRuleDialog(
                constraint = presetRules[index],
                onDismiss = { editingPresetIndex = null },
                onSave = { updatedConstraint ->
                    val originalConstraint = presetRules[index]
                    val newPresets = presetRules.toMutableList().apply {
                        this[index] = updatedConstraint
                    }
                    savePresetRulesAndSyncSession(newPresets)
                    uiScope.launch {
                        rebindHintsForEditedConstraints(
                            appHintRepo = appHintRepo,
                            packageName = app.packageName,
                            originalConstraints = listOf(originalConstraint),
                            updatedConstraints = listOf(updatedConstraint)
                        )
                        reloadHints()
                    }
                    editingPresetIndex = null
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppEntryIntentModeSection(
    selectedMode: AppEntryIntentMode,
    presetRules: List<SessionConstraint>,
    onModeSelected: (AppEntryIntentMode) -> Unit,
    onPresetSelected: (String) -> Unit
) {
    var presetMenuExpanded by remember { mutableStateOf(false) }
    val selectedPreset = presetRules.firstOrNull { it.isDefault } ?: presetRules.firstOrNull()

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.app_entry_behavior_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        AppEntryIntentModeRow(
            title = stringResource(R.string.entry_mode_ask),
            selected = selectedMode == AppEntryIntentMode.ASK_EVERY_TIME,
            enabled = true,
            onClick = { onModeSelected(AppEntryIntentMode.ASK_EVERY_TIME) }
        )
        AppEntryIntentModeRow(
            title = stringResource(R.string.entry_mode_preset),
            selected = selectedMode == AppEntryIntentMode.USE_PRESET,
            enabled = presetRules.isNotEmpty(),
            onClick = { onModeSelected(AppEntryIntentMode.USE_PRESET) }
        )
        if (presetRules.isEmpty()) {
            Text(
                text = stringResource(R.string.no_preset_for_entry_mode),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else if (selectedMode == AppEntryIntentMode.USE_PRESET) {
            ExposedDropdownMenuBox(
                expanded = presetMenuExpanded,
                onExpandedChange = { presetMenuExpanded = !presetMenuExpanded }
            ) {
                OutlinedTextField(
                    value = selectedPreset?.let { formatEntryPresetLabel(it) }.orEmpty(),
                    onValueChange = {},
                    readOnly = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.select_default_preset_intent)) },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = presetMenuExpanded)
                    }
                )
                ExposedDropdownMenu(
                    expanded = presetMenuExpanded,
                    onDismissRequest = { presetMenuExpanded = false }
                ) {
                    presetRules.forEach { preset ->
                        DropdownMenuItem(
                            text = { Text(formatEntryPresetLabel(preset)) },
                            onClick = {
                                onPresetSelected(preset.id)
                                presetMenuExpanded = false
                            }
                        )
                    }
                }
            }
        }
        AppEntryIntentModeRow(
            title = stringResource(R.string.entry_mode_last),
            selected = selectedMode == AppEntryIntentMode.USE_LAST_INTENT,
            enabled = true,
            onClick = { onModeSelected(AppEntryIntentMode.USE_LAST_INTENT) }
        )
    }
}

@Composable
private fun AppEntryIntentModeRow(
    title: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            enabled = enabled
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor
            )
        }
    }
}

@Composable
private fun formatEntryPresetLabel(constraint: SessionConstraint): String {
    return "${stringResource(constraintTypeLabel(constraint.type))}: ${constraint.description}"
}

private data class HintIntentOption(
    val intentId: String,
    val intentLabel: String
)

@Composable
private fun HintScopeSelector(
    selectedScopeType: AppHintScopeType,
    onScopeTypeSelected: (AppHintScopeType) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.scope_label),
            style = MaterialTheme.typography.labelMedium
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = selectedScopeType == AppHintScopeType.APP_GENERAL,
                onClick = { onScopeTypeSelected(AppHintScopeType.APP_GENERAL) },
                label = { Text(stringResource(R.string.scope_app_general)) }
            )
            FilterChip(
                selected = selectedScopeType == AppHintScopeType.INTENT_SPECIFIC,
                onClick = { onScopeTypeSelected(AppHintScopeType.INTENT_SPECIFIC) },
                label = { Text(stringResource(R.string.intent_specific_scope)) }
            )
        }
    }
}

@Composable
private fun HintCard(
    hint: com.seenot.app.data.model.AppHint,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.intent_source_label, sourceLabelForHint(context, hint.source)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = hint.hintText,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onEdit,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = stringResource(R.string.edit),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.delete),
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

private suspend fun rebindHintsForEditedConstraints(
    appHintRepo: AppHintRepository,
    packageName: String,
    originalConstraints: List<SessionConstraint>,
    updatedConstraints: List<SessionConstraint>
) {
    val updatedById = updatedConstraints.associateBy { it.id }
    originalConstraints.forEach { original ->
        val updated = updatedById[original.id] ?: return@forEach
        val oldIntentId = buildIntentScopedHintId(original)
        val newIntentId = buildIntentScopedHintId(updated)
        val newIntentLabel = buildIntentScopedHintLabel(null, updated)
        if (oldIntentId != newIntentId || buildIntentScopedHintLabel(null, original) != newIntentLabel) {
            appHintRepo.moveHintsToScope(
                packageName = packageName,
                fromScopeType = AppHintScopeType.INTENT_SPECIFIC,
                fromScopeKey = oldIntentId,
                toScopeType = AppHintScopeType.INTENT_SPECIFIC,
                toScopeKey = newIntentId,
                toIntentId = newIntentId,
                toIntentLabel = newIntentLabel
            )
        }
    }
}

private fun displayHintIntentLabel(context: Context, rawLabel: String): String {
    val match = Regex("""^(.*?)(?:\s*\((.*)\))?$""").matchEntire(rawLabel.trim()) ?: return rawLabel
    val base = localizeStoredIntentBaseLabel(context, match.groupValues[1].trim())
    val extrasRaw = match.groupValues.getOrNull(2)?.trim().orEmpty()
    if (extrasRaw.isBlank()) return base

    val filteredExtras = extrasRaw
        .split("/")
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .filterNot {
            it == TimeScope.SESSION.name ||
                it == TimeScope.PER_CONTENT.name ||
                it == TimeScope.CONTINUOUS.name
        }

    return if (filteredExtras.isEmpty()) {
        base
    } else {
        "$base (${filteredExtras.joinToString(" / ")})"
    }
}

private fun localizeStoredIntentBaseLabel(context: Context, base: String): String {
    return when {
        base.startsWith("[DENY] ") -> "[${context.getString(R.string.rule_label_deny)}] ${base.removePrefix("[DENY] ").trim()}"
        base == "[DENY]" -> "[${context.getString(R.string.rule_label_deny)}]"
        base.startsWith("[TIME_CAP] ") -> "[${context.getString(R.string.rule_label_time_cap)}] ${base.removePrefix("[TIME_CAP] ").trim()}"
        base == "[TIME_CAP]" -> "[${context.getString(R.string.rule_label_time_cap)}]"
        base.startsWith("[禁止] ") -> "[${context.getString(R.string.rule_label_deny)}] ${base.removePrefix("[禁止] ").trim()}"
        base == "[禁止]" -> "[${context.getString(R.string.rule_label_deny)}]"
        base.startsWith("[时间限制] ") -> "[${context.getString(R.string.rule_label_time_cap)}] ${base.removePrefix("[时间限制] ").trim()}"
        base == "[时间限制]" -> "[${context.getString(R.string.rule_label_time_cap)}]"
        else -> base
    }
}

private fun buildHintIntentOptions(
    context: Context,
    presetRules: List<SessionConstraint>,
    historyRules: List<List<SessionConstraint>>,
    lastIntentRules: List<SessionConstraint>,
    existingHints: List<com.seenot.app.data.model.AppHint>
): List<HintIntentOption> {
    val options = linkedMapOf<String, HintIntentOption>()
    val seenLabels = mutableSetOf<String>()

    fun addConstraint(constraint: SessionConstraint) {
        val intentId = buildIntentScopedHintId(constraint)
        val intentLabel = displayHintIntentLabel(context, buildIntentScopedHintLabel(context, constraint))
        val labelKey = intentLabel.trim().lowercase()
        if (seenLabels.add(labelKey)) {
            options[intentId] = HintIntentOption(
                intentId = intentId,
                intentLabel = intentLabel
            )
        }
    }

    fun addHintOption(hint: com.seenot.app.data.model.AppHint) {
        val intentLabel = displayHintIntentLabel(context, hint.intentLabel)
        val labelKey = intentLabel.trim().lowercase()
        if (seenLabels.add(labelKey)) {
            options[hint.intentId] = HintIntentOption(
                intentId = hint.intentId,
                intentLabel = intentLabel
            )
        }
    }

    presetRules.forEach(::addConstraint)
    historyRules.flatten().forEach(::addConstraint)
    lastIntentRules.forEach(::addConstraint)
    existingHints
        .filter { it.scopeType == AppHintScopeType.INTENT_SPECIFIC }
        .forEach(::addHintOption)

    return options.values.toList()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HintIntentSelector(
    options: List<HintIntentOption>,
    selectedIntentId: String?,
    onIntentSelected: (HintIntentOption) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = options.firstOrNull { it.intentId == selectedIntentId } ?: options.firstOrNull()

    LaunchedEffect(options, selected?.intentId, selectedIntentId) {
        if (selected != null && selectedIntentId == null) {
            onIntentSelected(selected)
        }
    }

    if (options.isEmpty()) {
        Text(
            text = stringResource(R.string.no_bindable_intent_yet),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = selected?.intentLabel.orEmpty(),
            onValueChange = {},
            readOnly = true,
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
            label = { Text(stringResource(R.string.bind_intent)) },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            }
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.intentLabel) },
                    onClick = {
                        onIntentSelected(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

private fun sourceLabelForHint(context: Context, source: String): String {
    val labelRes = when (source) {
        APP_HINT_SOURCE_MANUAL -> R.string.source_manual
        APP_HINT_SOURCE_FEEDBACK_GENERATED -> R.string.source_feedback_generated
        APP_HINT_SOURCE_INTENT_CARRY_OVER -> R.string.source_intent_carry_over
        else -> R.string.source_manual
    }
    return context.getString(labelRes)
}

/**
 * Add Preset Rule Dialog
 */
@Composable
fun AddPresetRuleDialog(
    onDismiss: () -> Unit,
    onConfirm: (SessionConstraint) -> Unit
) {
    RuleEditorDialog(
        title = stringResource(R.string.add_preset_intent_title),
        initialConstraint = SessionConstraint(
            id = java.util.UUID.randomUUID().toString(),
            type = ConstraintType.DENY,
            description = "",
            interventionLevel = InterventionLevel.MODERATE
        ),
        confirmText = stringResource(R.string.add),
        onDismiss = onDismiss,
        onConfirm = onConfirm
    )
}

/**
 * Edit Preset Rule Dialog
 */
@Composable
fun EditPresetRuleDialog(
    constraint: SessionConstraint,
    onDismiss: () -> Unit,
    onSave: (SessionConstraint) -> Unit
) {
    RuleEditorDialog(
        title = stringResource(R.string.edit_preset_intent_title),
        initialConstraint = constraint,
        confirmText = stringResource(R.string.save),
        onDismiss = onDismiss,
        onConfirm = onSave
    )
}

/**
 * Edit History Rule Dialog
 */
@Composable
fun EditHistoryRuleDialog(
    constraints: List<SessionConstraint>,
    onDismiss: () -> Unit,
    onSaveAsPreset: (List<SessionConstraint>) -> Unit
) {
    var editedConstraints by remember { mutableStateOf(constraints.toMutableList()) }
    var editingIndex by remember { mutableStateOf<Int?>(null) }

    // Edit single constraint dialog
    val editingConstraint = editingIndex?.let { editedConstraints.getOrNull(it) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_intent_title)) },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(editedConstraints.size) { index ->
                    val constraint = editedConstraints[index]
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = when (constraint.type) {
                                        ConstraintType.DENY -> stringResource(R.string.constraint_type_deny)
                                        ConstraintType.TIME_CAP -> stringResource(R.string.constraint_type_time_cap)
                                        ConstraintType.NO_MONITOR -> stringResource(R.string.constraint_type_no_monitor)
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconButton(
                                        onClick = { editingIndex = index },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Edit,
                                            contentDescription = stringResource(R.string.edit),
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            editedConstraints = editedConstraints.toMutableList().apply { removeAt(index) }
                                        },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = stringResource(R.string.delete),
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }

                            Text(
                                text = constraint.description,
                                style = MaterialTheme.typography.bodyMedium
                            )

                            if (constraint.timeLimitMs != null) {
                                val minutes = constraint.timeLimitMs / 60000.0
                                val timeText = if (minutes % 1.0 == 0.0) {
                                    stringResource(R.string.time_cap_format, minutes.toInt())
                                } else {
                                    stringResource(R.string.time_cap_format_decimal, minutes.toString())
                                }
                                Text(
                                    text = timeText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Text(
                                text = stringResource(R.string.intervention_level_label,
                                    when (constraint.interventionLevel) {
                                        InterventionLevel.GENTLE -> stringResource(R.string.intervention_gentle)
                                        InterventionLevel.MODERATE -> stringResource(R.string.intervention_moderate)
                                        InterventionLevel.STRICT -> stringResource(R.string.intervention_strict)
                                    }
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSaveAsPreset(editedConstraints) },
                enabled = editedConstraints.isNotEmpty()
            ) {
                Text(stringResource(R.string.save_as_preset))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )

    // Edit single constraint dialog
    editingConstraint?.let { constraint ->
        val constraintIndex = editingIndex!!
        EditConstraintDialog(
            constraint = constraint,
            onDismiss = { editingIndex = null },
            onSave = { updatedConstraint ->
                editedConstraints = editedConstraints.toMutableList().apply {
                    this[constraintIndex] = updatedConstraint
                }
                editingIndex = null
            }
        )
    }
}

/**
 * Edit Single Constraint Dialog
 */
@Composable
fun EditConstraintDialog(
    constraint: SessionConstraint,
    onDismiss: () -> Unit,
    onSave: (SessionConstraint) -> Unit
) {
    RuleEditorDialog(
        title = stringResource(R.string.edit_intent_item_title),
        initialConstraint = constraint,
        confirmText = stringResource(R.string.save),
        onDismiss = onDismiss,
        onConfirm = onSave
    )
}

@Composable
private fun RuleEditorDialog(
    title: String,
    initialConstraint: SessionConstraint,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: (SessionConstraint) -> Unit
) {
    val context = LocalContext.current
    val interventionLevelLocked = remember { InterventionLevelPrefs.isFixedLevelEnabled(context) }
    val lockedInterventionLevel = remember { InterventionLevelPrefs.getFixedLevel(context) }
    var ruleType by remember(initialConstraint) { mutableStateOf(initialConstraint.type) }
    var description by remember(initialConstraint) { mutableStateOf(initialConstraint.description) }
    var timeLimitMinutes by remember(initialConstraint) {
        mutableStateOf(initialConstraint.timeLimitMs?.let { formatTimeLimitMinutes(it) } ?: "")
    }
    var timeScope by remember(initialConstraint) {
        mutableStateOf(initialConstraint.timeScope ?: TimeScope.SESSION)
    }
    var interventionLevel by remember(initialConstraint) {
        mutableStateOf(
            if (interventionLevelLocked) lockedInterventionLevel else initialConstraint.interventionLevel
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            RuleEditorForm(
                ruleType = ruleType,
                onRuleTypeChange = { ruleType = it },
                description = description,
                onDescriptionChange = { description = it },
                timeLimitMinutes = timeLimitMinutes,
                onTimeLimitMinutesChange = { timeLimitMinutes = it },
                timeScope = timeScope,
                onTimeScopeChange = { timeScope = it },
                interventionLevel = interventionLevel,
                onInterventionLevelChange = { interventionLevel = it },
                interventionLevelLocked = interventionLevelLocked,
                lockedInterventionLevel = lockedInterventionLevel
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    buildEditedConstraint(
                        baseConstraint = initialConstraint,
                        ruleType = ruleType,
                        description = description,
                        timeLimitMinutes = timeLimitMinutes,
                        timeScope = timeScope,
                        interventionLevel = if (interventionLevelLocked) {
                            lockedInterventionLevel
                        } else {
                            interventionLevel
                        }
                    )?.let(onConfirm)
                },
                enabled = description.isNotBlank()
            ) {
                Text(confirmText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun RuleEditorForm(
    ruleType: ConstraintType,
    onRuleTypeChange: (ConstraintType) -> Unit,
    description: String,
    onDescriptionChange: (String) -> Unit,
    timeLimitMinutes: String,
    onTimeLimitMinutesChange: (String) -> Unit,
    timeScope: TimeScope,
    onTimeScopeChange: (TimeScope) -> Unit,
    interventionLevel: InterventionLevel,
    onInterventionLevelChange: (InterventionLevel) -> Unit,
    interventionLevelLocked: Boolean,
    lockedInterventionLevel: InterventionLevel
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 430.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(text = stringResource(R.string.intent_type), style = MaterialTheme.typography.labelMedium)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ConstraintType.entries.filter { it != ConstraintType.NO_MONITOR }.forEach { type ->
                FilterChip(
                    selected = ruleType == type,
                    onClick = { onRuleTypeChange(type) },
                    label = { Text(stringResource(constraintTypeLabel(type))) }
                )
            }
        }

        if (ruleType == ConstraintType.DENY) {
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                )
            ) {
                Text(
                    text = stringResource(R.string.deny_allowlist_usage_tip),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = description,
            onValueChange = onDescriptionChange,
            label = { Text(stringResource(R.string.description_label)) },
            placeholder = { Text(stringResource(R.string.description_hint)) },
            modifier = Modifier.fillMaxWidth(),
            supportingText = {
                Text(
                    stringResource(R.string.description_hint_tip),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            },
            minLines = 1,
            maxLines = 4
        )

        Spacer(modifier = Modifier.height(12.dp))

        if (ruleType == ConstraintType.TIME_CAP || ruleType == ConstraintType.DENY) {
            OutlinedTextField(
                value = timeLimitMinutes,
                onValueChange = { value ->
                    onTimeLimitMinutesChange(value.filter { c -> c.isDigit() || c == '.' })
                },
                label = { Text(stringResource(R.string.time_limit_minutes_hint)) },
                placeholder = { Text(stringResource(R.string.time_limit_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            if (ruleType == ConstraintType.TIME_CAP && timeLimitMinutes.isNotBlank()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(text = stringResource(R.string.time_scope_label), style = MaterialTheme.typography.labelMedium)
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    TimeScope.entries.forEach { scope ->
                        FilterChip(
                            selected = timeScope == scope,
                            onClick = { onTimeScopeChange(scope) },
                            label = { Text(stringResource(timeScopeLabel(scope))) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(text = stringResource(R.string.intervention_level_title), style = MaterialTheme.typography.labelMedium)
        if (interventionLevelLocked) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(
                    R.string.intervention_level_locked_summary,
                    stringResource(interventionLevelLabel(lockedInterventionLevel))
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            InterventionLevel.entries.forEach { level ->
                FilterChip(
                    selected = interventionLevel == level,
                    onClick = { onInterventionLevelChange(level) },
                    enabled = !interventionLevelLocked,
                    label = { Text(stringResource(interventionLevelLabel(level))) }
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Text(
                text = stringResource(interventionLevelDescription(interventionLevel)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(12.dp)
            )
        }
    }
}

private fun buildEditedConstraint(
    baseConstraint: SessionConstraint,
    ruleType: ConstraintType,
    description: String,
    timeLimitMinutes: String,
    timeScope: TimeScope,
    interventionLevel: InterventionLevel
): SessionConstraint? {
    if (description.isBlank()) return null

    val timeLimitMs = if (timeLimitMinutes.isNotBlank()) {
        timeLimitMinutes.toDoubleOrNull()?.times(60 * 1000)?.toLong()
    } else {
        null
    }

    return baseConstraint.copy(
        type = ruleType,
        description = description,
        timeLimitMs = timeLimitMs,
        timeScope = if (timeLimitMs != null) timeScope else null,
        interventionLevel = interventionLevel
    )
}

private fun formatTimeLimitMinutes(timeLimitMs: Long): String {
    val minutes = timeLimitMs / 60000.0
    return if (minutes % 1.0 == 0.0) minutes.toInt().toString() else minutes.toString()
}

@StringRes
internal fun constraintTypeLabel(type: ConstraintType): Int = when (type) {
    ConstraintType.DENY -> R.string.constraint_type_deny
    ConstraintType.TIME_CAP -> R.string.constraint_type_time_cap
    ConstraintType.NO_MONITOR -> R.string.constraint_type_no_monitor
}

@StringRes
private fun timeScopeLabel(scope: TimeScope): Int = when (scope) {
    TimeScope.SESSION -> R.string.time_scope_session
    TimeScope.PER_CONTENT -> R.string.time_scope_per_content
    TimeScope.CONTINUOUS -> R.string.time_scope_continuous
}

@StringRes
internal fun interventionLevelLabel(level: InterventionLevel): Int = when (level) {
    InterventionLevel.GENTLE -> R.string.intervention_gentle
    InterventionLevel.MODERATE -> R.string.intervention_moderate
    InterventionLevel.STRICT -> R.string.intervention_strict
}

@StringRes
private fun interventionLevelDescription(level: InterventionLevel): Int = when (level) {
    InterventionLevel.GENTLE -> R.string.intervention_gentle_desc
    InterventionLevel.MODERATE -> R.string.intervention_moderate_desc
    InterventionLevel.STRICT -> R.string.intervention_strict_desc
}

@StringRes
internal fun interventionLevelBriefDescription(level: InterventionLevel): Int = when (level) {
    InterventionLevel.GENTLE -> R.string.intervention_gentle_brief_desc
    InterventionLevel.MODERATE -> R.string.intervention_moderate_brief_desc
    InterventionLevel.STRICT -> R.string.intervention_strict_brief_desc
}
