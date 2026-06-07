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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AiModelSettingsDialog(
    accountState: SeenotAccountState,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val accountApi = remember { SeenotAccountApi(context) }
    val scope = rememberCoroutineScope()
    val initialSettings = remember { ApiConfig.getOwnKeySettings() }
    val initialSttSettings = remember { ApiConfig.getSttSettings() }
    val hasPlus = (accountState as? SeenotAccountState.Ready)?.snapshot?.hasPlus == true
    var preferredAiSource by remember {
        mutableStateOf(
            if (ApiConfig.getAiSource() == AiSource.SEENOT_AI) {
                AiSource.SEENOT_AI
            } else {
                AiSource.BRING_YOUR_OWN_KEY
            }
        )
    }
    val showAiSourceSelector = shouldShowAiSourceSelector(hasPlus)
    val showOwnKeySettings = !showAiSourceSelector || preferredAiSource == AiSource.BRING_YOUR_OWN_KEY
    var isSaving by remember { mutableStateOf(false) }

    var provider by remember { mutableStateOf(initialSettings.provider) }
    var apiKey by remember { mutableStateOf(initialSettings.apiKey) }
    var baseUrl by remember { mutableStateOf(initialSettings.baseUrl) }
    var model by remember { mutableStateOf(initialSettings.model) }
    var feedbackModel by remember { mutableStateOf(initialSettings.feedbackModel) }
    var qwenRegion by remember { mutableStateOf(initialSettings.qwenRegion) }

    var sttProvider by remember { mutableStateOf(initialSttSettings.provider) }
    var sttModel by remember { mutableStateOf(initialSttSettings.model) }
    var sttApiKey by remember { mutableStateOf(initialSttSettings.apiKey) }
    var sttBaseUrl by remember { mutableStateOf(initialSttSettings.baseUrl) }

    var selectedConfigTab by remember { mutableIntStateOf(0) }
    var providerExpanded by remember { mutableStateOf(false) }
    var modelExpanded by remember { mutableStateOf(false) }
    var feedbackModelExpanded by remember { mutableStateOf(false) }
    var baseUrlExpanded by remember { mutableStateOf(false) }
    var sttProviderExpanded by remember { mutableStateOf(false) }
    var sttModelExpanded by remember { mutableStateOf(false) }
    var sttBaseUrlExpanded by remember { mutableStateOf(false) }
    var showAdvancedVisionSettings by remember { mutableStateOf(false) }

    val recommendedPresets = remember(provider, qwenRegion) {
        recommendedModelPresets(provider, qwenRegion)
    }
    val sttRecommendedPresets = remember(sttProvider) {
        recommendedSttModelPresets(sttProvider)
    }
    val providerOptions = remember(provider) { selectableProviders(provider) }
    val sttProviderOptions = remember(sttProvider) { selectableSttProviders(sttProvider) }
    val sttModelIsFixed = sttProvider == AiProvider.DASHSCOPE
    val sttUsesSharedProviderConfig = sttProvider != AiProvider.CUSTOM
    val devDashscopeKeyValidUntilEpochMs = BuildConfig.DEVELOPMENT_DASHSCOPE_KEY_VALID_UNTIL_EPOCH_MS
    val isDevDashscopeKeyActive = BuildConfig.ENABLE_DEVELOPMENT_MODE &&
        BuildConfig.DASHSCOPE_API_KEY.isNotBlank() &&
        devDashscopeKeyValidUntilEpochMs > System.currentTimeMillis()
    val devDashscopeKeyExpiryText = remember(devDashscopeKeyValidUntilEpochMs) {
        if (devDashscopeKeyValidUntilEpochMs <= 0L) {
            ""
        } else {
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(devDashscopeKeyValidUntilEpochMs))
        }
    }

    var modelInput by remember {
        mutableStateOf(
            recommendedPresets.firstOrNull { it.model == initialSettings.model }?.model ?: initialSettings.model
        )
    }
    var feedbackModelInput by remember {
        mutableStateOf(
            recommendedPresets.firstOrNull { it.model == initialSettings.feedbackModel }?.model
                ?: initialSettings.feedbackModel
        )
    }
    var sttModelInput by remember {
        mutableStateOf(
            sttRecommendedPresets.firstOrNull { it.model == initialSttSettings.model }?.model
                ?: initialSttSettings.model
        )
    }

    fun baseUrlSuggestionsFor(target: AiProvider): List<Pair<Int, String>> {
        return when (target) {
            AiProvider.DASHSCOPE -> QwenRegion.entries.map { it.displayNameResId to it.baseUrl }
            AiProvider.OPENAI -> listOf(R.string.provider_openai to AiProvider.OPENAI.defaultBaseUrl)
            AiProvider.GEMINI -> listOf(R.string.provider_gemini to AiProvider.GEMINI.defaultBaseUrl)
            AiProvider.GLM -> listOf(R.string.provider_glm to AiProvider.GLM.defaultBaseUrl)
            AiProvider.ANTHROPIC -> listOf(R.string.provider_anthropic to AiProvider.ANTHROPIC.defaultBaseUrl)
            AiProvider.CUSTOM -> emptyList()
        }
    }

    fun defaultSttModel(target: AiProvider): String {
        return when (target) {
            AiProvider.DASHSCOPE -> "fun-asr-realtime"
            AiProvider.OPENAI -> "gpt-4o-mini-transcribe"
            AiProvider.GEMINI -> "gemini-2.5-flash-preview-tts"
            AiProvider.GLM -> "glm-asr-2512"
            AiProvider.CUSTOM -> "whisper-1"
            AiProvider.ANTHROPIC -> "fun-asr-realtime"
        }
    }

    fun applyProviderDefaults(target: AiProvider) {
        val defaults = ApiSettings.defaults(target)
        val savedBaseUrl = ApiConfig.getOwnBaseUrl(target)
        val nextRegion = if (target == AiProvider.DASHSCOPE) {
            QwenRegion.entries.firstOrNull { it.baseUrl == savedBaseUrl } ?: ApiConfig.getQwenRegion()
        } else {
            defaults.qwenRegion
        }
        val nextBaseUrl = savedBaseUrl
        val nextApiKey = ApiConfig.getOwnApiKey(target)

        provider = target
        qwenRegion = nextRegion
        baseUrl = nextBaseUrl
        model = defaults.model
        modelInput = recommendedModelPresets(target, nextRegion)
            .firstOrNull { it.model == defaults.model }
            ?.model
            ?: defaults.model
        feedbackModel = defaults.feedbackModel
        feedbackModelInput = recommendedModelPresets(target, nextRegion)
            .firstOrNull { it.model == defaults.feedbackModel }
            ?.model
            ?: defaults.feedbackModel
        apiKey = nextApiKey

        if (target == sttProvider && target != AiProvider.CUSTOM) {
            sttApiKey = nextApiKey
            sttBaseUrl = nextBaseUrl
        }
    }

    fun applySttDefaults(target: AiProvider) {
        val presets = recommendedSttModelPresets(target)
        val fallbackModel = presets.firstOrNull()?.model ?: defaultSttModel(target)

        sttProvider = target
        sttModel = fallbackModel
        sttModelInput = presets.firstOrNull { it.model == fallbackModel }?.model ?: fallbackModel
        if (target == provider && target != AiProvider.CUSTOM) {
            sttApiKey = apiKey
            sttBaseUrl = baseUrl
        } else {
            sttApiKey = ApiConfig.getSttApiKey(target)
            sttBaseUrl = ApiConfig.getSttBaseUrl(target)
        }
    }

    val baseUrlSuggestions = remember(provider) { baseUrlSuggestionsFor(provider) }
    val sttBaseUrlSuggestions = remember(sttProvider) { baseUrlSuggestionsFor(sttProvider) }

    @Composable
    fun VisionBaseUrlField() {
        ExposedDropdownMenuBox(
            expanded = baseUrlExpanded,
            onExpandedChange = {
                if (baseUrlSuggestions.isNotEmpty()) baseUrlExpanded = !baseUrlExpanded
            }
        ) {
            OutlinedTextField(
                value = baseUrl,
                onValueChange = {
                    baseUrl = it
                    if (provider == sttProvider && provider != AiProvider.CUSTOM) {
                        sttBaseUrl = it
                    }
                },
                label = { Text(stringResource(R.string.base_url)) },
                placeholder = { Text(stringResource(R.string.base_url_placeholder)) },
                trailingIcon = {
                    if (baseUrlSuggestions.isNotEmpty()) {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = baseUrlExpanded)
                    }
                },
                modifier = Modifier
                    .menuAnchor()
                    .fillMaxWidth(),
                singleLine = true
            )
            if (baseUrlSuggestions.isNotEmpty()) {
                ExposedDropdownMenu(
                    expanded = baseUrlExpanded,
                    onDismissRequest = { baseUrlExpanded = false }
                ) {
                    baseUrlSuggestions.forEach { (labelResId, value) ->
                        DropdownMenuItem(
                            text = { Text(stringResource(labelResId)) },
                            onClick = {
                                baseUrlExpanded = false
                                baseUrl = value
                                if (provider == sttProvider && provider != AiProvider.CUSTOM) {
                                    sttBaseUrl = value
                                }
                                if (provider == AiProvider.DASHSCOPE) {
                                    qwenRegion = QwenRegion.entries.firstOrNull { it.baseUrl == value }
                                        ?: qwenRegion
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ai_model_settings_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (showAiSourceSelector) {
                        Text(
                            text = stringResource(R.string.ai_source_selector_title),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = preferredAiSource == AiSource.SEENOT_AI,
                                onClick = { preferredAiSource = AiSource.SEENOT_AI },
                                label = { Text(stringResource(R.string.ai_source_selector_seenot)) }
                            )
                            FilterChip(
                                selected = preferredAiSource == AiSource.BRING_YOUR_OWN_KEY,
                                onClick = { preferredAiSource = AiSource.BRING_YOUR_OWN_KEY },
                                label = { Text(stringResource(R.string.ai_source_selector_local)) }
                            )
                        }
                        Text(
                            text = stringResource(R.string.ai_source_selector_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (showOwnKeySettings) {
                        TabRow(selectedTabIndex = selectedConfigTab) {
                            Tab(
                                selected = selectedConfigTab == 0,
                                onClick = { selectedConfigTab = 0 },
                                text = { Text(stringResource(R.string.vision_tab)) }
                            )
                            Tab(
                                selected = selectedConfigTab == 1,
                                onClick = { selectedConfigTab = 1 },
                                text = { Text(stringResource(R.string.voice_tab)) }
                            )
                        }

                        Text(
                            text = stringResource(R.string.ai_setup_intro),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (isDevDashscopeKeyActive) {
                            Text(
                                text = stringResource(R.string.dev_mode_dashscope_temp_key, devDashscopeKeyExpiryText),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }

                        if (selectedConfigTab == 0) {
                            ExposedDropdownMenuBox(
                                expanded = providerExpanded,
                                onExpandedChange = { providerExpanded = !providerExpanded }
                            ) {
                                OutlinedTextField(
                                    value = context.getString(provider.displayNameResId),
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text(stringResource(R.string.vision_provider)) },
                                    trailingIcon = {
                                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerExpanded)
                                    },
                                    modifier = Modifier
                                        .menuAnchor()
                                        .fillMaxWidth()
                                )
                                ExposedDropdownMenu(
                                    expanded = providerExpanded,
                                    onDismissRequest = { providerExpanded = false }
                                ) {
                                    providerOptions.forEach { candidate ->
                                        DropdownMenuItem(
                                            text = { Text(context.getString(candidate.displayNameResId)) },
                                            onClick = {
                                                providerExpanded = false
                                                applyProviderDefaults(candidate)
                                            }
                                        )
                                    }
                                }
                            }

                            OutlinedTextField(
                                value = apiKey,
                                onValueChange = {
                                    apiKey = it
                                    if (provider == sttProvider && provider != AiProvider.CUSTOM) {
                                        sttApiKey = it
                                    }
                                },
                                label = { Text(stringResource(R.string.api_key)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation()
                            )

                            ExposedDropdownMenuBox(
                                expanded = modelExpanded,
                                onExpandedChange = {
                                    if (recommendedPresets.isNotEmpty()) modelExpanded = !modelExpanded
                                }
                            ) {
                                OutlinedTextField(
                                    value = modelInput,
                                    onValueChange = {
                                        modelInput = it
                                        model = it
                                    },
                                    label = { Text(stringResource(R.string.vision_model)) },
                                    placeholder = { Text(stringResource(R.string.vision_model_hint)) },
                                    trailingIcon = {
                                        if (recommendedPresets.isNotEmpty()) {
                                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelExpanded)
                                        }
                                    },
                                    modifier = Modifier
                                        .menuAnchor()
                                        .fillMaxWidth(),
                                    singleLine = true
                                )
                                if (recommendedPresets.isNotEmpty()) {
                                    ExposedDropdownMenu(
                                        expanded = modelExpanded,
                                        onDismissRequest = { modelExpanded = false }
                                    ) {
                                        recommendedPresets.forEach { preset ->
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        when {
                                                            preset.noteResId != null -> "${preset.model}  ${stringResource(preset.noteResId)}"
                                                            preset.note.isNotBlank() -> "${preset.model}  ${preset.note}"
                                                            else -> preset.model
                                                        }
                                                    )
                                                },
                                                onClick = {
                                                    modelExpanded = false
                                                    model = preset.model
                                                    modelInput = preset.model
                                                }
                                            )
                                        }
                                    }
                                }
                            }

                            if (provider == AiProvider.CUSTOM) {
                                VisionBaseUrlField()
                            }

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showAdvancedVisionSettings = !showAdvancedVisionSettings },
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.advanced_settings),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.weight(1f)
                                )
                                Icon(
                                    imageVector = if (showAdvancedVisionSettings) {
                                        Icons.Default.ExpandLess
                                    } else {
                                        Icons.Default.ExpandMore
                                    },
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        if (showAdvancedVisionSettings) {
                            if (provider != AiProvider.CUSTOM) {
                                VisionBaseUrlField()
                            }
                        }

                        if (showAdvancedVisionSettings) {
                            ExposedDropdownMenuBox(
                                expanded = feedbackModelExpanded,
                                onExpandedChange = {
                                    if (recommendedPresets.isNotEmpty()) {
                                        feedbackModelExpanded = !feedbackModelExpanded
                                    }
                                }
                            ) {
                                OutlinedTextField(
                                    value = feedbackModelInput,
                                    onValueChange = {
                                        feedbackModelInput = it
                                        feedbackModel = it
                                    },
                                    label = { Text(stringResource(R.string.bias_model)) },
                                    placeholder = { Text(stringResource(R.string.bias_model_hint)) },
                                    trailingIcon = {
                                        if (recommendedPresets.isNotEmpty()) {
                                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = feedbackModelExpanded)
                                        }
                                    },
                                    modifier = Modifier
                                        .menuAnchor()
                                        .fillMaxWidth(),
                                    singleLine = true
                                )
                                if (recommendedPresets.isNotEmpty()) {
                                    ExposedDropdownMenu(
                                        expanded = feedbackModelExpanded,
                                        onDismissRequest = { feedbackModelExpanded = false }
                                    ) {
                                        recommendedPresets.forEach { preset ->
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        when {
                                                            preset.noteResId != null -> "${preset.model}  ${stringResource(preset.noteResId)}"
                                                            preset.note.isNotBlank() -> "${preset.model}  ${preset.note}"
                                                            else -> preset.model
                                                        }
                                                    )
                                                },
                                                onClick = {
                                                    feedbackModelExpanded = false
                                                    feedbackModel = preset.model
                                                    feedbackModelInput = preset.model
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                            Text(
                                text = stringResource(R.string.bias_model_not_highfreq_usage),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (provider == AiProvider.DASHSCOPE) {
                            Text(
                                text = stringResource(R.string.qwen_region_label, context.getString(qwenRegion.displayNameResId)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        Text(
                            text = stringResource(R.string.voice_optional_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        ExposedDropdownMenuBox(
                            expanded = sttProviderExpanded,
                            onExpandedChange = { sttProviderExpanded = !sttProviderExpanded }
                        ) {
                            OutlinedTextField(
                                value = context.getString(sttProvider.displayNameResId),
                                onValueChange = {},
                                readOnly = true,
                                label = { Text(stringResource(R.string.voice_provider)) },
                                trailingIcon = {
                                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = sttProviderExpanded)
                                },
                                modifier = Modifier
                                    .menuAnchor()
                                    .fillMaxWidth()
                            )
                            ExposedDropdownMenu(
                                expanded = sttProviderExpanded,
                                onDismissRequest = { sttProviderExpanded = false }
                            ) {
                                sttProviderOptions.forEach { candidate ->
                                    DropdownMenuItem(
                                        text = { Text(context.getString(candidate.displayNameResId)) },
                                        onClick = {
                                            sttProviderExpanded = false
                                            applySttDefaults(candidate)
                                        }
                                    )
                                }
                            }
                        }

                        OutlinedTextField(
                            value = sttApiKey,
                            onValueChange = {
                                sttApiKey = it
                                if (sttProvider == provider && sttProvider != AiProvider.CUSTOM) {
                                    apiKey = it
                                }
                            },
                            label = {
                                Text(if (sttUsesSharedProviderConfig) stringResource(R.string.api_key_shared) else stringResource(R.string.api_key_independent))
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation()
                        )

                        ExposedDropdownMenuBox(
                            expanded = sttBaseUrlExpanded,
                            onExpandedChange = {
                                if (sttBaseUrlSuggestions.isNotEmpty()) sttBaseUrlExpanded = !sttBaseUrlExpanded
                            }
                        ) {
                            OutlinedTextField(
                                value = sttBaseUrl,
                                onValueChange = {
                                    sttBaseUrl = it
                                    if (sttProvider == provider && sttProvider != AiProvider.CUSTOM) {
                                        baseUrl = it
                                    }
                                },
                                label = {
                                    Text(if (sttUsesSharedProviderConfig) stringResource(R.string.base_url_shared) else stringResource(R.string.base_url_independent))
                                },
                                placeholder = { Text("https://.../v1") },
                                trailingIcon = {
                                    if (sttBaseUrlSuggestions.isNotEmpty()) {
                                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = sttBaseUrlExpanded)
                                    }
                                },
                                modifier = Modifier
                                    .menuAnchor()
                                    .fillMaxWidth(),
                                singleLine = true
                            )
                            if (sttBaseUrlSuggestions.isNotEmpty()) {
                                ExposedDropdownMenu(
                                    expanded = sttBaseUrlExpanded,
                                    onDismissRequest = { sttBaseUrlExpanded = false }
                                ) {
                                    sttBaseUrlSuggestions.forEach { (labelResId, value) ->
                                        DropdownMenuItem(
                                            text = { Text(stringResource(labelResId)) },
                                            onClick = {
                                                sttBaseUrlExpanded = false
                                                sttBaseUrl = value
                                                if (sttProvider == provider && sttProvider != AiProvider.CUSTOM) {
                                                    baseUrl = value
                                                }
                                                if (sttProvider == AiProvider.DASHSCOPE) {
                                                    qwenRegion = QwenRegion.entries.firstOrNull { it.baseUrl == value }
                                                        ?: qwenRegion
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        if (!sttModelIsFixed) {
                            ExposedDropdownMenuBox(
                                expanded = sttModelExpanded,
                                onExpandedChange = {
                                    if (sttRecommendedPresets.isNotEmpty()) sttModelExpanded = !sttModelExpanded
                                }
                            ) {
                                OutlinedTextField(
                                    value = sttModelInput,
                                    onValueChange = {
                                        sttModelInput = it
                                        sttModel = it
                                    },
                                    label = { Text(stringResource(R.string.voice_model)) },
                                    placeholder = { Text(stringResource(R.string.voice_model_hint)) },
                                    trailingIcon = {
                                        if (sttRecommendedPresets.isNotEmpty()) {
                                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = sttModelExpanded)
                                        }
                                    },
                                    modifier = Modifier
                                        .menuAnchor()
                                        .fillMaxWidth(),
                                    singleLine = true
                                )
                                if (sttRecommendedPresets.isNotEmpty()) {
                                    ExposedDropdownMenu(
                                        expanded = sttModelExpanded,
                                        onDismissRequest = { sttModelExpanded = false }
                                    ) {
                                        sttRecommendedPresets.forEach { preset ->
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        if (preset.note.isBlank()) preset.model
                                                        else "${preset.model}  ${preset.note}"
                                                    )
                                                },
                                                onClick = {
                                                    sttModelExpanded = false
                                                    sttModel = preset.model
                                                    sttModelInput = preset.model
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        if (sttProvider == AiProvider.DASHSCOPE) {
                            Text(
                                text = stringResource(R.string.voice_input_auto_config_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                    }
                }
            }
        }
        },
        confirmButton = {
            Button(
                onClick = {
                    scope.launch {
                        isSaving = true
                        runCatching {
                            if (showAiSourceSelector && preferredAiSource == AiSource.SEENOT_AI) {
                                if (!ApiConfig.isManagedAiActive()) {
                                    val session = accountApi.createManagedAiSession()
                                    ApiConfig.saveManagedAiSession(
                                        apiKey = session.apiKey,
                                        baseUrl = session.baseUrl,
                                        model = session.model,
                                        expiresAtEpochSeconds = session.expiresAt
                                    )
                                }
                            } else {
                                val normalizedRegion = if (provider == AiProvider.DASHSCOPE) {
                                    QwenRegion.entries.firstOrNull { it.baseUrl == baseUrl.trim() } ?: qwenRegion
                                } else {
                                    qwenRegion
                                }
                                val normalizedSttRegion = if (sttProvider == AiProvider.DASHSCOPE) {
                                    QwenRegion.entries.firstOrNull { it.baseUrl == sttBaseUrl.trim() }
                                } else {
                                    null
                                }

                                ApiConfig.saveSettings(
                                    ApiSettings(
                                        provider = provider,
                                        apiKey = apiKey.trim(),
                                        baseUrl = baseUrl.trim(),
                                        model = model.trim(),
                                        feedbackModel = feedbackModel.trim().ifBlank { model.trim() },
                                        qwenRegion = normalizedRegion
                                    )
                                )

                                if (sttProvider != AiProvider.CUSTOM) {
                                    ApiConfig.setApiKey(sttProvider, sttApiKey.trim())
                                    ApiConfig.setBaseUrl(sttProvider, sttBaseUrl.trim())
                                    normalizedSttRegion?.let(ApiConfig::setQwenRegion)
                                }

                                ApiConfig.saveSttSettings(
                                    SttSettings(
                                        provider = sttProvider,
                                        model = when (sttProvider) {
                                            AiProvider.DASHSCOPE -> "fun-asr-realtime"
                                            else -> sttModel.trim()
                                        },
                                        apiKey = sttApiKey.trim(),
                                        baseUrl = sttBaseUrl.trim()
                                    )
                                )

                                when (preferredAiSource) {
                                    AiSource.SEENOT_AI -> Unit
                                    AiSource.BRING_YOUR_OWN_KEY -> ApiConfig.preferBringYourOwnKey()
                                }
                            }
                        }.onSuccess {
                            Toast.makeText(
                                context,
                                context.getString(
                                    if (ApiConfig.isVoiceConfigured()) {
                                        R.string.save_ai_settings_ready
                                    } else {
                                        R.string.save_ai_settings_ready_voice_missing
                                    }
                                ),
                                Toast.LENGTH_SHORT
                            ).show()
                            onDismiss()
                        }.onFailure {
                            Toast.makeText(
                                context,
                                managedAiErrorMessage(context, it),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        isSaving = false
                    }
                },
                enabled = !isSaving && canSaveAiModelSettings(
                    selectedSource = preferredAiSource,
                    showAiSourceSelector = showAiSourceSelector,
                    apiKey = apiKey,
                    baseUrl = baseUrl,
                    model = model,
                    feedbackModel = feedbackModel
                )
            ) {
                Text(
                    if (isSaving) {
                        stringResource(R.string.status_loading)
                    } else {
                        stringResource(R.string.save)
                    }
                )
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (showOwnKeySettings) {
                    TextButton(
                        onClick = {
                            if (selectedConfigTab == 0) {
                                applyProviderDefaults(provider)
                            } else {
                                applySttDefaults(sttProvider)
                            }
                        }
                    ) {
                        Text(stringResource(R.string.restore_defaults))
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.close))
                }
            }
        }
    )
}

internal fun shouldShowAiSourceSelector(hasPlus: Boolean): Boolean = hasPlus

internal fun canSaveAiModelSettings(
    selectedSource: AiSource,
    showAiSourceSelector: Boolean,
    apiKey: String,
    baseUrl: String,
    model: String,
    feedbackModel: String
): Boolean {
    if (showAiSourceSelector && selectedSource == AiSource.SEENOT_AI) {
        return true
    }
    return apiKey.isNotBlank() &&
        baseUrl.isNotBlank() &&
        model.isNotBlank() &&
        feedbackModel.isNotBlank()
}
