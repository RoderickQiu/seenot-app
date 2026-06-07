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
import com.seenot.app.utils.Logger
import android.widget.Toast
import kotlinx.coroutines.launch
import com.seenot.app.ui.overlay.VoiceInputState
import com.seenot.app.ui.overlay.VoiceInputStatus
import kotlinx.coroutines.flow.collectLatest

/**
 * Main Screen - Primary UI for SeeNot app
 *
 * Features:
 * - Permission status and guidance
 * - Controlled app selection
 * - Settings access
 * - Session history (future)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    startWithVoiceInput: Boolean = false,
    voiceInputPackageName: String? = null,
    authCallbackUri: Uri? = null,
    onAuthCallbackConsumed: () -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val sessionManager = remember { SessionManager.getInstance(context) }
    val accountApi = remember { SeenotAccountApi(context) }
    val syncCoordinator = remember { SeenotSyncCoordinator(context, accountApi, sessionManager) }
    val mainScope = rememberCoroutineScope()
    var showHomeTimeline by remember { mutableStateOf(RuleRecordingPrefs.isHomeTimelineEnabled(context)) }
    var showAiSettingsDialog by remember { mutableStateOf(false) }
    var accountState by remember { mutableStateOf<SeenotAccountState>(SeenotAccountState.Loading) }
    var accountRefreshKey by remember { mutableIntStateOf(0) }
    var aiSettingsRefreshKey by remember { mutableIntStateOf(0) }
    var pendingPermissionGuide by remember { mutableStateOf<PermissionGuideType?>(null) }
    var restrictedSettingsHelpTarget by rememberSaveable { mutableStateOf<RestrictedSettingsHelpTarget?>(null) }
    var showBackgroundLimitsHelp by rememberSaveable { mutableStateOf(false) }
    var appHelpTopic by rememberSaveable { mutableStateOf<AppHelpTopic?>(null) }
    var isAccountLoginInFlight by remember { mutableStateOf(false) }
    var isAccountRefreshInFlight by remember { mutableStateOf(false) }
    var syncRefreshKey by remember { mutableIntStateOf(0) }
    var automaticVersionCheckEnabled by remember { mutableStateOf(SeenotVersionCheckPrefs.isAutomaticCheckEnabled(context)) }
    var versionCheckResponse by remember { mutableStateOf(SeenotVersionCheckPrefs.getLastVersionCheckResponse(context)) }
    var visibleVersionUpdateDialog by remember { mutableStateOf<SeenotVersionCheckResponse?>(null) }
    var isVersionCheckInFlight by remember { mutableStateOf(false) }

    // State
    var selectedTab by remember { mutableIntStateOf(0) }
    var isAccessibilityEnabled by remember { mutableStateOf(false) }
    var isOverlayEnabled by remember { mutableStateOf(false) }
    var isNotificationEnabled by remember { mutableStateOf(false) }
    var isUsageStatsAccessEnabled by remember { mutableStateOf(false) }
    var isMediaSessionAccessEnabled by remember { mutableStateOf(false) }
    var isMicrophoneEnabled by remember { mutableStateOf(false) }
    var isBatteryOptimizationIgnored by remember { mutableStateOf(false) }
    var showVoiceInput by remember { mutableStateOf(startWithVoiceInput) }
    var currentVoiceInputPackage by remember { mutableStateOf(voiceInputPackageName) }
    var controlledAppCount by remember { mutableIntStateOf(0) }
    var globalMonitoringPause by remember { mutableStateOf<AppMonitoringPause?>(null) }

    // Rule records state
    var showRuleRecordsPage by remember { mutableStateOf(false) }

    // Activity result launcher for overlay permission
    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        // Refresh overlay permission status when returning from settings
        isOverlayEnabled = Settings.canDrawOverlays(context)
    }

    val batteryOptimizationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        isBatteryOptimizationIgnored = isBatteryOptimizationIgnored(context)
    }

    val notificationSettingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        isNotificationEnabled = notificationManager?.areNotificationsEnabled() == true
    }

    val mediaSessionAccessLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        isMediaSessionAccessEnabled = MediaSessionProbe.hasNotificationListenerAccess(context)
    }

    val usageStatsAccessLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        isUsageStatsAccessEnabled = ForegroundUsageStatsReader(context).hasPermission()
    }

    // Notification permission launcher
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        isNotificationEnabled = isGranted
    }

    // Microphone permission launcher
    val microphonePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        isMicrophoneEnabled = isGranted
    }

    fun runVersionCheck(isAutomatic: Boolean) {
        if (isVersionCheckInFlight) return
        isVersionCheckInFlight = true
        mainScope.launch {
            try {
                runCatching {
                    accountApi.checkVersion()
                }.onSuccess { response ->
                    if (isAutomatic) {
                        SeenotVersionCheckPrefs.saveLastSuccessfulAutomaticCheckAt(context)
                    }
                    SeenotVersionCheckPrefs.saveLastVersionCheckResponse(context, response)
                    versionCheckResponse = response
                    if (response.updateAvailable && isAutomatic && SeenotVersionCheckPrefs.shouldPromptForUpdate(context, response)) {
                        visibleVersionUpdateDialog = response
                        SeenotVersionCheckPrefs.markUpdatePrompted(context, response)
                    } else if (!isAutomatic) {
                        if (response.updateAvailable) {
                            visibleVersionUpdateDialog = response
                        } else {
                            Toast.makeText(context, context.getString(R.string.version_check_up_to_date_toast), Toast.LENGTH_SHORT).show()
                        }
                    }
                }.onFailure { error ->
                    Logger.e("SeeNot", "Version check failed; automatic=$isAutomatic", error)
                    if (!isAutomatic) {
                        Toast.makeText(context, context.getString(R.string.version_check_failed_toast), Toast.LENGTH_SHORT).show()
                    }
                }
            } finally {
                isVersionCheckInFlight = false
            }
        }
    }

    visibleVersionUpdateDialog?.let { response ->
        VersionUpdateDialog(
            response = response,
            onDismiss = { visibleVersionUpdateDialog = null },
            onOpenDownload = response.downloadUrl
                ?.takeIf { response.updateAvailable && it.isNotBlank() }
                ?.let { url ->
                    {
                        visibleVersionUpdateDialog = null
                        openExternalUrl(context, url)
                    }
                }
        )
    }

    LaunchedEffect(Unit) {
        if (SeenotVersionCheckPrefs.isAutomaticCheckDue(context)) {
            runVersionCheck(isAutomatic = true)
        }
    }

    // Check notification permission status. Request only when the user explicitly enters the flow.
    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val notificationManager = context.getSystemService(android.app.NotificationManager::class.java)
            isNotificationEnabled = notificationManager?.areNotificationsEnabled() == true
        } else {
            isNotificationEnabled = true
        }
    }

    LaunchedEffect(Unit) {
        isMediaSessionAccessEnabled = MediaSessionProbe.hasNotificationListenerAccess(context)
    }

    LaunchedEffect(Unit) {
        isUsageStatsAccessEnabled = ForegroundUsageStatsReader(context).hasPermission()
        SeenotAccessibilityService.isUsageStatsAccessEnabled.collectLatest {
            isUsageStatsAccessEnabled = it
        }
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isAccessibilityEnabled = isSeenotAccessibilityEnabled(context) || SeenotAccessibilityService.isServiceReady.value
                isMediaSessionAccessEnabled = MediaSessionProbe.hasNotificationListenerAccess(context)
                isUsageStatsAccessEnabled = ForegroundUsageStatsReader(context).hasPermission()
                if (SeenotVersionCheckPrefs.isAutomaticCheckDue(context)) {
                    runVersionCheck(isAutomatic = true)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Check permission status
    LaunchedEffect(Unit) {
        isAccessibilityEnabled = isSeenotAccessibilityEnabled(context) || SeenotAccessibilityService.isServiceReady.value
        SeenotAccessibilityService.isServiceReady.collectLatest {
            isAccessibilityEnabled = it || isSeenotAccessibilityEnabled(context)
        }
    }

    // Check overlay permission
    LaunchedEffect(Unit) {
        isOverlayEnabled = Settings.canDrawOverlays(context)
    }

    // Check microphone permission
    LaunchedEffect(Unit) {
        isMicrophoneEnabled = context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    // Check battery optimization exemption
    LaunchedEffect(Unit) {
        isBatteryOptimizationIgnored = isBatteryOptimizationIgnored(context)
    }

    LaunchedEffect(Unit) {
        controlledAppCount = sessionManager.controlledApps.value.size
        sessionManager.controlledApps.collectLatest {
            controlledAppCount = it.size
        }
    }

    LaunchedEffect(Unit) {
        globalMonitoringPause = sessionManager.globalMonitoringPause.value
        sessionManager.globalMonitoringPause.collectLatest {
            globalMonitoringPause = it
        }
    }

    LaunchedEffect(accountRefreshKey) {
        isAccountRefreshInFlight = true
        accountState = SeenotAccountState.Loading
        accountState = accountApi.loadAccount()
        syncCoordinator.syncNowIfPlus(accountState)
        isAccountRefreshInFlight = false
    }

    LaunchedEffect(syncRefreshKey) {
        if (syncRefreshKey > 0) {
            syncCoordinator.syncNowIfPlus(accountState)
        }
    }

    LaunchedEffect(authCallbackUri) {
        val callbackUri = authCallbackUri ?: return@LaunchedEffect
        val requestId = callbackUri.getQueryParameter("request_id").orEmpty()
        val exchangeCode = callbackUri.getQueryParameter("code").orEmpty()
        if (requestId.isBlank() || exchangeCode.isBlank()) {
            onAuthCallbackConsumed()
            Toast.makeText(context, context.getString(R.string.account_login_callback_invalid), Toast.LENGTH_LONG).show()
            return@LaunchedEffect
        }

        isAccountLoginInFlight = true
        accountState = SeenotAccountState.Loading
        val exchangedState = accountApi.exchangeAppLogin(requestId = requestId, exchangeCode = exchangeCode)
        accountState = exchangedState
        isAccountLoginInFlight = false

        when (exchangedState) {
            is SeenotAccountState.Ready -> {
                syncCoordinator.syncNowIfPlus(exchangedState)
                if (exchangedState.snapshot.hasPlus) {
                    runCatching {
                        val session = accountApi.createManagedAiSession()
                        ApiConfig.saveManagedAiSession(
                            apiKey = session.apiKey,
                            baseUrl = session.baseUrl,
                            model = session.model,
                            expiresAtEpochSeconds = session.expiresAt
                        )
                    }
                }
                selectedTab = 2
                Toast.makeText(context, context.getString(R.string.account_login_success_toast), Toast.LENGTH_SHORT).show()
                accountRefreshKey++
            }
            is SeenotAccountState.Error -> {
                Toast.makeText(
                    context,
                    exchangedState.message.ifBlank { context.getString(R.string.account_login_failed_toast) },
                    Toast.LENGTH_LONG
                ).show()
            }
            SeenotAccountState.SignedOut -> {
                Toast.makeText(context, context.getString(R.string.account_login_failed_toast), Toast.LENGTH_LONG).show()
            }
            SeenotAccountState.Loading -> Unit
        }
        onAuthCallbackConsumed()
    }

    // Determine if required permissions are granted. Microphone is optional because text input works.
    val allPermissionsGranted =
        isAccessibilityEnabled &&
            isOverlayEnabled &&
            isNotificationEnabled &&
            isBatteryOptimizationIgnored
    val isAiConfigured = remember(showAiSettingsDialog, aiSettingsRefreshKey) { ApiConfig.isVisionConfigured() }
    val hasControlledApps = controlledAppCount > 0
    val isHomeReady = allPermissionsGranted && isAiConfigured && hasControlledApps

    if (showRuleRecordsPage) {
        val repository = remember { RuleRecordRepository(context) }
        RuleRecordsPage(
            repository = repository,
            onBack = { showRuleRecordsPage = false }
        )
    } else {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("SeeNot") },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
                        label = { Text(stringResource(R.string.tab_home)) },
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 }
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Apps, contentDescription = "Apps") },
                        label = { Text(stringResource(R.string.tab_apps)) },
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 }
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                        label = { Text(stringResource(R.string.tab_settings)) },
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 }
                    )
                }
            }
        ) { padding ->
            Box(modifier = Modifier.fillMaxWidth()) {
                when (selectedTab) {
                    0 -> HomeTab(
                        isAccessibilityEnabled = isAccessibilityEnabled,
                        isOverlayEnabled = isOverlayEnabled,
                        isNotificationEnabled = isNotificationEnabled,
                        isUsageStatsAccessEnabled = isUsageStatsAccessEnabled,
                        isMediaSessionAccessEnabled = isMediaSessionAccessEnabled,
                        isBatteryOptimizationIgnored = isBatteryOptimizationIgnored,
                        isMicrophoneEnabled = isMicrophoneEnabled,
                        isAiConfigured = isAiConfigured,
                        isHomeReady = isHomeReady,
                        controlledAppCount = controlledAppCount,
                        globalMonitoringPause = globalMonitoringPause,
                        onEnableAccessibility = {
                            pendingPermissionGuide = PermissionGuideType.ACCESSIBILITY
                        },
                        onEnableOverlay = {
                            pendingPermissionGuide = PermissionGuideType.OVERLAY
                        },
                        onOpenSecondaryHelp = { help ->
                            when (help) {
                                SetupSecondaryHelp.RESTRICTED_SETTINGS_ACCESSIBILITY -> {
                                    restrictedSettingsHelpTarget = RestrictedSettingsHelpTarget.ACCESSIBILITY
                                }
                                SetupSecondaryHelp.RESTRICTED_SETTINGS_OVERLAY -> {
                                    restrictedSettingsHelpTarget = RestrictedSettingsHelpTarget.OVERLAY
                                }
                                SetupSecondaryHelp.BACKGROUND_LIMITS -> {
                                    showBackgroundLimitsHelp = true
                                }
                                SetupSecondaryHelp.AI_OPTIONS -> {
                                    appHelpTopic = AppHelpTopic.AI_OPTIONS
                                }
                            }
                        },
                        onRequestIgnoreBatteryOptimizations = {
                            pendingPermissionGuide = PermissionGuideType.BATTERY
                        },
                        onOpenNotificationSettings = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !isNotificationEnabled) {
                                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                notificationSettingsLauncher.launch(createNotificationSettingsIntent(context))
                            }
                        },
                        onOpenMediaSessionAccessSettings = {
                            pendingPermissionGuide = PermissionGuideType.MEDIA_SESSION
                        },
                        onOpenUsageStatsAccessSettings = {
                            pendingPermissionGuide = PermissionGuideType.USAGE_STATS
                        },
                        onRequestMicrophone = {
                            microphonePermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                        },
                        onOpenAiSettings = { showAiSettingsDialog = true },
                        onOpenAiOptionsHelp = { appHelpTopic = AppHelpTopic.AI_OPTIONS },
                        onOpenUsageInstructions = { appHelpTopic = AppHelpTopic.USAGE_INSTRUCTIONS },
                        onOpenAccount = {
                            mainScope.launch {
                                openSeenotAccountPage(
                                    context = context,
                                    accountApi = accountApi,
                                    accountState = accountState,
                                    isAccountLoginInFlight = isAccountLoginInFlight,
                                    onLoginStarted = { isAccountLoginInFlight = it }
                                )
                            }
                        },
                        onUseSeenotAi = {
                            mainScope.launch {
                                enableSeenotAi(
                                    context = context,
                                    accountApi = accountApi,
                                    onEnabled = {
                                        aiSettingsRefreshKey++
                                        accountRefreshKey++
                                    }
                                )
                            }
                        },
                        onOpenControlledApps = { selectedTab = 1 },
                        onPauseGlobalMonitoring = { durationMs ->
                            sessionManager.pauseGlobalMonitoring(durationMs)
                        },
                        onResumeGlobalMonitoring = {
                            sessionManager.resumeGlobalMonitoring()
                        },
                        accountState = accountState,
                        showHomeTimeline = showHomeTimeline,
                        modifier = Modifier.padding(padding)
                    )
                    1 -> AppsTab(
                        modifier = Modifier.padding(padding),
                        onSyncNeeded = { syncRefreshKey++ }
                    )
                    2 -> SettingsTab(
                        modifier = Modifier.padding(padding),
                        accountState = accountState,
                        onOpenAiSettings = { showAiSettingsDialog = true },
                        onOpenAiOptionsHelp = { appHelpTopic = AppHelpTopic.AI_OPTIONS },
                        onOpenAccount = {
                            mainScope.launch {
                                openSeenotAccountPage(
                                    context = context,
                                    accountApi = accountApi,
                                    accountState = accountState,
                                    isAccountLoginInFlight = isAccountLoginInFlight,
                                    onLoginStarted = { isAccountLoginInFlight = it }
                                )
                            }
                        },
                        onSignOutAccount = {
                            mainScope.launch {
                                runCatching {
                                    accountApi.revokeCurrentDevice()
                                }
                                SeenotAccountSession.clearAccount()
                                ApiConfig.preferBringYourOwnKey()
                                accountRefreshKey++
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.account_signed_out_toast),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        onRefreshAccount = { accountRefreshKey++ },
                        onManualSync = { onComplete ->
                            mainScope.launch {
                                val result = syncCoordinator.syncNowIfPlus(accountState)
                                val summary = result.getOrNull()
                                val message = if (summary != null) {
                                    context.getString(
                                        R.string.plus_sync_result_toast,
                                        summary.uploaded,
                                        summary.downloaded,
                                        summary.pending
                                    )
                                } else {
                                    context.getString(R.string.plus_sync_failed_toast)
                                }
                                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                onComplete()
                            }
                        },
                        isAccountLoginInFlight = isAccountLoginInFlight,
                        isAccountRefreshInFlight = isAccountRefreshInFlight,
                        onHomeTimelineChanged = { showHomeTimeline = it },
                        onOpenRuleRecords = { showRuleRecordsPage = true },
                        onOpenWrongJudgmentHelp = { appHelpTopic = AppHelpTopic.WRONG_JUDGMENT },
                        automaticVersionCheckEnabled = automaticVersionCheckEnabled,
                        versionCheckResponse = versionCheckResponse,
                        isVersionCheckInFlight = isVersionCheckInFlight,
                        onAutomaticVersionCheckChanged = { enabled ->
                            automaticVersionCheckEnabled = enabled
                            SeenotVersionCheckPrefs.setAutomaticCheckEnabled(context, enabled)
                            if (enabled && SeenotVersionCheckPrefs.isAutomaticCheckDue(context)) {
                                runVersionCheck(isAutomatic = true)
                            }
                        },
                        onManualVersionCheck = { runVersionCheck(isAutomatic = false) },
                        onOpenVersionUpdate = { response -> visibleVersionUpdateDialog = response }
                    )
                }

                // Voice Input Overlay
                if (showVoiceInput && currentVoiceInputPackage != null) {
                    @Suppress("NAME_SHADOWING")
                    val context = LocalContext.current
                    @Suppress("NAME_SHADOWING")
                    val sessionManager = remember { SessionManager.getInstance(context) }
                    val pm = remember { context.packageManager }

                    VoiceInputDialog(
                        packageName = currentVoiceInputPackage!!,
                        onDismiss = {
                            showVoiceInput = false
                            currentVoiceInputPackage = null
                        },
                        onIntentConfirmed = { constraints ->
                            val pkgName = currentVoiceInputPackage!!
                            val appName = try {
                                pm.getApplicationLabel(pm.getApplicationInfo(pkgName, 0)).toString()
                            } catch (e: Exception) {
                                pkgName
                            }

                            mainScope.launch {
                                try {
                                    android.util.Log.d("MainScreen", ">>> Calling createSession for $appName")
                                    val sessionId = sessionManager.createSession(
                                        packageName = pkgName,
                                        displayName = appName,
                                        constraints = constraints
                                    )
                                    if (sessionId != null) {
                                        android.util.Log.d("MainScreen", "<<< Session created for $appName with ${constraints.size} constraints")
                                    } else {
                                        android.util.Log.w("MainScreen", "Session creation skipped for $appName due to foreground mismatch")
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.e("MainScreen", "!!! Failed to create session", e)
                                }
                            }

                            showVoiceInput = false
                            currentVoiceInputPackage = null
                        }
                    )
                }
            }
        }
    }

    if (showAiSettingsDialog) {
        AiModelSettingsDialog(
            accountState = accountState,
            onDismiss = {
                showAiSettingsDialog = false
                aiSettingsRefreshKey++
            }
        )
    }

    pendingPermissionGuide?.let { guideType ->
        PermissionGuideDialog(
            type = guideType,
            onOpenRestrictedSettingsHelp = guideType.restrictedSettingsHelpTarget()?.let { target ->
                {
                    restrictedSettingsHelpTarget = target
                    pendingPermissionGuide = null
                }
            },
            onDismiss = { pendingPermissionGuide = null },
            onContinue = {
                pendingPermissionGuide = null
                when (guideType) {
                    PermissionGuideType.ACCESSIBILITY -> {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                    PermissionGuideType.OVERLAY -> {
                        overlayPermissionLauncher.launch(
                            createOverlaySettingsIntent(context)
                        )
                    }
                    PermissionGuideType.BATTERY -> {
                        batteryOptimizationLauncher.launch(
                            createBatteryOptimizationIntent(context)
                        )
                    }
                    PermissionGuideType.MEDIA_SESSION -> {
                        mediaSessionAccessLauncher.launch(createNotificationListenerSettingsIntent())
                    }
                    PermissionGuideType.USAGE_STATS -> {
                        usageStatsAccessLauncher.launch(createUsageStatsSettingsIntent())
                    }
                }
            }
        )
    }

    restrictedSettingsHelpTarget?.let { target ->
        RestrictedSettingsHelpSheet(
            onDismiss = { restrictedSettingsHelpTarget = null },
            onOpenAppInfo = {
                context.startActivity(createAppDetailsSettingsIntent(context))
            },
            onOpenPermissionSettings = {
                when (target) {
                    RestrictedSettingsHelpTarget.ACCESSIBILITY -> {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                    RestrictedSettingsHelpTarget.OVERLAY -> {
                        overlayPermissionLauncher.launch(createOverlaySettingsIntent(context))
                    }
                }
            }
        )
    }

    if (showBackgroundLimitsHelp) {
        BackgroundLimitsHelpSheet(
            onDismiss = { showBackgroundLimitsHelp = false },
            onOpenAppInfo = {
                context.startActivity(createAppDetailsSettingsIntent(context))
            }
        )
    }

    appHelpTopic?.let { topic ->
        val isPlus = (accountState as? SeenotAccountState.Ready)?.snapshot?.hasPlus == true
        AppHelpSheet(
            topic = topic,
            onDismiss = { appHelpTopic = null },
            primaryActionLabelRes = when (topic) {
                AppHelpTopic.AI_OPTIONS -> if (isPlus) R.string.use_seenot_ai_action else R.string.open_plus_no_config_action
                AppHelpTopic.WRONG_JUDGMENT -> R.string.view_records
                AppHelpTopic.USAGE_INSTRUCTIONS -> R.string.first_setup_ready_action
            },
            onPrimaryAction = {
                appHelpTopic = null
                when (topic) {
                    AppHelpTopic.AI_OPTIONS -> {
                        if (isPlus) {
                            mainScope.launch {
                                enableSeenotAi(
                                    context = context,
                                    accountApi = accountApi,
                                    onEnabled = {
                                        aiSettingsRefreshKey++
                                        accountRefreshKey++
                                    }
                                )
                            }
                        } else {
                            mainScope.launch {
                                openSeenotAccountPage(
                                    context = context,
                                    accountApi = accountApi,
                                    accountState = accountState,
                                    isAccountLoginInFlight = isAccountLoginInFlight,
                                    onLoginStarted = { isAccountLoginInFlight = it }
                                )
                            }
                        }
                    }
                    AppHelpTopic.WRONG_JUDGMENT -> {
                        showRuleRecordsPage = true
                    }
                    AppHelpTopic.USAGE_INSTRUCTIONS -> {
                        selectedTab = 1
                    }
                }
            },
            secondaryActionLabelRes = when (topic) {
                AppHelpTopic.AI_OPTIONS -> R.string.use_own_api_key_action
                AppHelpTopic.WRONG_JUDGMENT -> R.string.background_limits_help_got_it
                AppHelpTopic.USAGE_INSTRUCTIONS -> R.string.background_limits_help_got_it
            },
            onSecondaryAction = {
                appHelpTopic = null
                if (topic == AppHelpTopic.AI_OPTIONS) {
                    showAiSettingsDialog = true
                }
            }
        )
    }
}

private fun isSeenotAccessibilityEnabled(context: Context): Boolean {
    val expectedServiceId = "${context.packageName}/com.seenot.app.service.SeenotAccessibilityService"
    val accessibilityManager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
    val enabledServiceIds = accessibilityManager
        ?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        ?.mapNotNull { it.resolveInfo?.serviceInfo }
        ?.map { "${it.packageName}/${it.name}" }
        .orEmpty()

    if (enabledServiceIds.any { it == expectedServiceId }) {
        return true
    }

    val secureSetting = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ).orEmpty()

    return secureSetting.split(':').any { candidate ->
        candidate.equals(expectedServiceId, ignoreCase = true) ||
            candidate.equals(expectedServiceId.substringAfter('/'), ignoreCase = true)
    }
}
