package com.seenot.app.ui.screens

import android.net.Uri
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateUtils
import android.app.NotificationManager
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import com.seenot.app.service.SeenotAccessibilityService
import com.seenot.app.service.MediaSessionProbe
import com.seenot.app.ai.voice.VoiceInputManager
import com.seenot.app.ai.voice.VoiceRecordingState
import com.seenot.app.ai.parser.AppInfo
import com.seenot.app.ui.overlay.FloatingIndicatorOverlay
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
    var isAccountLoginInFlight by remember { mutableStateOf(false) }
    var isAccountRefreshInFlight by remember { mutableStateOf(false) }
    var syncRefreshKey by remember { mutableIntStateOf(0) }
    var automaticVersionCheckEnabled by remember { mutableStateOf(SeenotVersionCheckPrefs.isAutomaticCheckEnabled(context)) }
    var versionCheckResponse by remember { mutableStateOf<SeenotVersionCheckResponse?>(null) }
    var isVersionCheckInFlight by remember { mutableStateOf(false) }

    // State
    var selectedTab by remember { mutableIntStateOf(0) }
    var isAccessibilityEnabled by remember { mutableStateOf(false) }
    var isOverlayEnabled by remember { mutableStateOf(false) }
    var isNotificationEnabled by remember { mutableStateOf(false) }
    var isMediaSessionAccessEnabled by remember { mutableStateOf(false) }
    var isMicrophoneEnabled by remember { mutableStateOf(false) }
    var isBatteryOptimizationIgnored by remember { mutableStateOf(false) }
    var showVoiceInput by remember { mutableStateOf(startWithVoiceInput) }
    var currentVoiceInputPackage by remember { mutableStateOf(voiceInputPackageName) }
    var controlledAppCount by remember { mutableIntStateOf(0) }

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
                    versionCheckResponse = response
                    if (!isAutomatic) {
                        val message = if (response.updateAvailable) {
                            context.getString(R.string.version_check_update_available_toast, response.latestVersion)
                        } else {
                            context.getString(R.string.version_check_up_to_date_toast)
                        }
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    }
                }.onFailure {
                    if (!isAutomatic) {
                        Toast.makeText(context, context.getString(R.string.version_check_failed_toast), Toast.LENGTH_SHORT).show()
                    }
                }
            } finally {
                isVersionCheckInFlight = false
            }
        }
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

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isMediaSessionAccessEnabled = MediaSessionProbe.hasNotificationListenerAccess(context)
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
        isAccessibilityEnabled = SeenotAccessibilityService.isServiceReady.value
        SeenotAccessibilityService.isServiceReady.collectLatest {
            isAccessibilityEnabled = it
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
    val isVoiceConfigured = remember(showAiSettingsDialog, aiSettingsRefreshKey) { ApiConfig.isVoiceConfigured() }
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
                        isMediaSessionAccessEnabled = isMediaSessionAccessEnabled,
                        isBatteryOptimizationIgnored = isBatteryOptimizationIgnored,
                        isMicrophoneEnabled = isMicrophoneEnabled,
                        isAiConfigured = isAiConfigured,
                        isVoiceConfigured = isVoiceConfigured,
                        isHomeReady = isHomeReady,
                        controlledAppCount = controlledAppCount,
                        onEnableAccessibility = {
                            pendingPermissionGuide = PermissionGuideType.ACCESSIBILITY
                        },
                        onEnableOverlay = {
                            pendingPermissionGuide = PermissionGuideType.OVERLAY
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
                        onRequestMicrophone = {
                            microphonePermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                        },
                        onOpenAiSettings = { showAiSettingsDialog = true },
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
                        onOpenVersionDownload = { url -> openExternalUrl(context, url) }
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
            onDismiss = { pendingPermissionGuide = null },
            onContinue = {
                pendingPermissionGuide = null
                when (guideType) {
                    PermissionGuideType.ACCESSIBILITY -> {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                    PermissionGuideType.OVERLAY -> {
                        overlayPermissionLauncher.launch(
                            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
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
                }
            }
        )
    }
}

/**
 * Home Tab - Permission status and quick actions
 */
@Composable
fun HomeTab(
    isAccessibilityEnabled: Boolean,
    isOverlayEnabled: Boolean,
    isNotificationEnabled: Boolean,
    isMediaSessionAccessEnabled: Boolean,
    isBatteryOptimizationIgnored: Boolean,
    isMicrophoneEnabled: Boolean,
    isAiConfigured: Boolean,
    isVoiceConfigured: Boolean,
    isHomeReady: Boolean,
    controlledAppCount: Int,
    onEnableAccessibility: () -> Unit,
    onEnableOverlay: () -> Unit,
    onRequestIgnoreBatteryOptimizations: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onOpenMediaSessionAccessSettings: () -> Unit,
    onRequestMicrophone: () -> Unit,
    onOpenAiSettings: () -> Unit,
    onOpenAccount: () -> Unit,
    onUseSeenotAi: () -> Unit,
    onOpenControlledApps: () -> Unit,
    accountState: SeenotAccountState,
    showHomeTimeline: Boolean,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    var showCompletedConfigDetails by rememberSaveable { mutableStateOf(false) }
    val permissionsReady = isAccessibilityEnabled && isOverlayEnabled && isNotificationEnabled && isBatteryOptimizationIgnored
    val hasControlledApps = controlledAppCount > 0

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

        Text(
            text = stringResource(R.string.app_tagline),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Permission section - always reachable; expanded by default until required setup is complete.
        if (!isHomeReady || showCompletedConfigDetails) {
            Text(
                text = stringResource(R.string.permission_status),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Accessibility Permission
            PermissionCard(
                title = stringResource(R.string.permission_accessibility),
                description = stringResource(R.string.permission_accessibility_desc),
                isEnabled = isAccessibilityEnabled,
                onClick = onEnableAccessibility
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Overlay Permission
            PermissionCard(
                title = stringResource(R.string.permission_overlay),
                description = stringResource(R.string.permission_overlay_desc),
                isEnabled = isOverlayEnabled,
                onClick = onEnableOverlay
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Notification Permission
            PermissionCard(
                title = stringResource(R.string.permission_notification),
                description = stringResource(R.string.permission_notification_desc),
                isEnabled = isNotificationEnabled,
                onClick = onOpenNotificationSettings
            )

            Spacer(modifier = Modifier.height(8.dp))

            PermissionCard(
                title = stringResource(R.string.permission_battery_optimization),
                description = stringResource(R.string.permission_battery_optimization_desc),
                isEnabled = isBatteryOptimizationIgnored,
                onClick = onRequestIgnoreBatteryOptimizations
            )

            Spacer(modifier = Modifier.height(8.dp))

            PermissionCard(
                title = stringResource(R.string.permission_media_session_optional),
                description = stringResource(R.string.permission_media_session_desc),
                isEnabled = isMediaSessionAccessEnabled,
                readyLabel = stringResource(R.string.permission_optional_ready),
                notReadyLabel = stringResource(R.string.permission_optional_recommended),
                onClick = onOpenMediaSessionAccessSettings
            )

            Spacer(modifier = Modifier.height(8.dp))

            PermissionCard(
                title = stringResource(R.string.permission_microphone_optional),
                description = stringResource(R.string.permission_microphone_desc),
                isEnabled = isMicrophoneEnabled,
                onClick = onRequestMicrophone
            )

            Spacer(modifier = Modifier.height(8.dp))

            Spacer(modifier = Modifier.height(24.dp))
        }

        if (!isHomeReady || showCompletedConfigDetails) {
            Text(
                text = stringResource(R.string.ai_capability_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(12.dp))

            HomeAiChoiceCard(
                isAiConfigured = isAiConfigured,
                isVoiceConfigured = isVoiceConfigured,
                accountState = accountState,
                onOpenAiSettings = onOpenAiSettings,
                onOpenAccount = onOpenAccount,
                onUseSeenotAi = onUseSeenotAi
            )

            Spacer(modifier = Modifier.height(24.dp))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenControlledApps() }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (hasControlledApps) Icons.Default.CheckCircle else Icons.Default.Apps,
                        contentDescription = null,
                        tint = if (hasControlledApps) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.secondary
                        }
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.controlled_apps_setup_title),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = if (hasControlledApps) {
                                stringResource(R.string.controlled_apps_setup_ready, controlledAppCount)
                            } else {
                                stringResource(R.string.controlled_apps_setup_missing)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }

        // Status Summary
        if (isHomeReady) {
            Card(
                modifier = Modifier.clickable {
                    showCompletedConfigDetails = !showCompletedConfigDetails
                },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.basic_config_complete),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = if (showCompletedConfigDetails) stringResource(R.string.tap_to_collapse) else stringResource(R.string.tap_to_expand),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Icon(
                        if (showCompletedConfigDetails) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        } else {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = when {
                                permissionsReady && isAiConfigured && !hasControlledApps ->
                                    stringResource(R.string.one_step_left_add_app)
                                else -> stringResource(R.string.please_complete_permissions_and_ai_config)
                            },
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Quick Start Guide
        Text(
            text = stringResource(R.string.usage_instructions),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(12.dp))

        Card {
            Column(modifier = Modifier.padding(16.dp)) {
                StepItem(number = 1, text = stringResource(R.string.step_1_select_apps))
                Spacer(modifier = Modifier.height(8.dp))
                StepItem(number = 2, text = stringResource(R.string.step_2_declare_intent))
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.step_2_intent_example),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                StepItem(number = 3, text = stringResource(R.string.step_3_ai_guards))
                Spacer(modifier = Modifier.height(8.dp))
                StepItem(number = 4, text = stringResource(R.string.step_4_report_misreport))
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (isHomeReady && showHomeTimeline) {
            // Today's timeline (derived from RuleRecord)
            HomeTimelineSection()
        }
    }
}

/**
 * Permission Card
 */
@Composable
fun PermissionCard(
    title: String,
    description: String,
    isEnabled: Boolean,
    readyLabel: String? = null,
    notReadyLabel: String? = null,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (isEnabled) Icons.Default.CheckCircle else Icons.Default.Cancel,
                contentDescription = null,
                tint = if (isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = if (isEnabled) {
                        readyLabel ?: stringResource(R.string.permission_ready)
                    } else {
                        notReadyLabel ?: stringResource(R.string.permission_not_ready)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null
            )
        }
    }
}

@Composable
private fun HomeAiChoiceCard(
    isAiConfigured: Boolean,
    isVoiceConfigured: Boolean,
    accountState: SeenotAccountState,
    onOpenAiSettings: () -> Unit,
    onOpenAccount: () -> Unit,
    onUseSeenotAi: () -> Unit
) {
    val isPlus = (accountState as? SeenotAccountState.Ready)?.snapshot?.hasPlus == true
    val usesSeenotAi = ApiConfig.getAiSource() == AiSource.SEENOT_AI
    val title = when {
        usesSeenotAi -> stringResource(R.string.seenot_ai_enabled_title)
        isAiConfigured -> stringResource(R.string.own_key_ready_title)
        else -> stringResource(R.string.choose_ai_plan_title)
    }
    val description = when {
        usesSeenotAi -> stringResource(R.string.seenot_ai_enabled_desc)
        isPlus && !isAiConfigured -> stringResource(R.string.plus_can_enable_seenot_ai_desc)
        isAiConfigured && isVoiceConfigured -> stringResource(R.string.own_key_ready_home_desc_with_voice)
        isAiConfigured -> stringResource(R.string.own_key_ready_home_desc)
        else -> stringResource(R.string.choose_ai_plan_home_desc)
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (isAiConfigured) Icons.Default.CheckCircle else Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = if (isAiConfigured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = if (isPlus) onUseSeenotAi else onOpenAccount,
                    modifier = Modifier.weight(1f),
                    enabled = accountState !is SeenotAccountState.Loading
                ) {
                    Text(
                        if (isPlus) {
                            stringResource(R.string.use_seenot_ai_action)
                        } else {
                            stringResource(R.string.open_plus_no_config_action)
                        }
                    )
                }
                OutlinedButton(
                    onClick = onOpenAiSettings,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.use_own_api_key_action))
                }
            }
        }
    }
}

private enum class PermissionGuideType {
    ACCESSIBILITY,
    OVERLAY,
    BATTERY,
    MEDIA_SESSION
}

private const val SEENOT_APP_LOGIN_REDIRECT_URI = "seenot://auth/callback"

private suspend fun openSeenotAccountPage(
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

private fun openSeenotAccountManagementPage(
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

private fun openSeenotPlusPage(context: Context) {
    val language = AppLocalePrefs.getLanguage(context)
    val path = if (language == AppLocalePrefs.LANG_ZH) "/zh/#seenot-plus" else "/#seenot-plus"
    val url = BuildConfig.SEENOT_WEBSITE_BASE_URL.trimEnd('/') + path
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}

private fun openSeenotOfficialSitePage(context: Context) {
    val language = AppLocalePrefs.getLanguage(context)
    val path = if (language == AppLocalePrefs.LANG_ZH) "/zh/" else "/"
    val url = BuildConfig.SEENOT_WEBSITE_BASE_URL.trimEnd('/') + path
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}

private fun openExternalUrl(context: Context, url: String) {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}

private suspend fun enableSeenotAi(
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
            context.getString(R.string.seenot_ai_enable_failed),
            Toast.LENGTH_LONG
        ).show()
    }
}

@Composable
private fun PermissionGuideDialog(
    type: PermissionGuideType,
    onDismiss: () -> Unit,
    onContinue: () -> Unit
) {
    val (titleRes, messageRes) = when (type) {
        PermissionGuideType.ACCESSIBILITY -> R.string.permission_guide_accessibility_title to R.string.permission_guide_accessibility_message
        PermissionGuideType.OVERLAY -> R.string.permission_guide_overlay_title to R.string.permission_guide_overlay_message
        PermissionGuideType.BATTERY -> R.string.permission_guide_battery_title to R.string.permission_guide_battery_message
        PermissionGuideType.MEDIA_SESSION -> R.string.permission_guide_media_session_title to R.string.permission_guide_media_session_message
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
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

private fun isBatteryOptimizationIgnored(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
    val powerManager = context.getSystemService(PowerManager::class.java) ?: return false
    return powerManager.isIgnoringBatteryOptimizations(context.packageName)
}

private fun createBatteryOptimizationIntent(context: Context): Intent {
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

private fun createNotificationSettingsIntent(context: Context): Intent {
    return Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    }
}

private fun createNotificationListenerSettingsIntent(): Intent {
    return Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
}

/**
 * Step Item
 */
@Composable
fun StepItem(number: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.primary
        ) {
            Text(
                text = "$number",
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.labelMedium
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = text, style = MaterialTheme.typography.bodyMedium)
    }
}

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
private fun constraintTypeLabel(type: ConstraintType): Int = when (type) {
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
private fun interventionLevelLabel(level: InterventionLevel): Int = when (level) {
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
private fun interventionLevelBriefDescription(level: InterventionLevel): Int = when (level) {
    InterventionLevel.GENTLE -> R.string.intervention_gentle_brief_desc
    InterventionLevel.MODERATE -> R.string.intervention_moderate_brief_desc
    InterventionLevel.STRICT -> R.string.intervention_strict_brief_desc
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
    onPausePermanently: () -> Unit
) {
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
}

private fun formatMonitoringPauseStatus(context: Context, pause: AppMonitoringPause): String {
    val resumeAt = pause.resumeAt
    if (resumeAt == null) {
        return context.getString(R.string.app_monitoring_paused_permanently)
    }
    val formattedTime = DateUtils.formatDateTime(
        context,
        resumeAt,
        DateUtils.FORMAT_SHOW_DATE or
            DateUtils.FORMAT_SHOW_TIME or
            DateUtils.FORMAT_ABBREV_MONTH or
            DateUtils.FORMAT_ABBREV_RELATIVE
    )
    return context.getString(R.string.app_monitoring_paused_until, formattedTime)
}

/**
 * Settings Tab
 */
@Composable
fun SettingsTab(
    modifier: Modifier = Modifier,
    accountState: SeenotAccountState = SeenotAccountState.SignedOut,
    onOpenAiSettings: () -> Unit = {},
    onOpenAccount: () -> Unit = {},
    onSignOutAccount: () -> Unit = {},
    onRefreshAccount: () -> Unit = {},
    onManualSync: (onComplete: () -> Unit) -> Unit = { onComplete -> onComplete() },
    isAccountLoginInFlight: Boolean = false,
    isAccountRefreshInFlight: Boolean = false,
    onHomeTimelineChanged: (Boolean) -> Unit = {},
    onOpenRuleRecords: () -> Unit = {},
    automaticVersionCheckEnabled: Boolean = true,
    versionCheckResponse: SeenotVersionCheckResponse? = null,
    isVersionCheckInFlight: Boolean = false,
    onAutomaticVersionCheckChanged: (Boolean) -> Unit = {},
    onManualVersionCheck: () -> Unit = {},
    onOpenVersionDownload: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val sessionManager = remember { SessionManager.getInstance(context) }
    val repository = remember { RuleRecordRepository(context) }
    val scope = rememberCoroutineScope()

    var autoStart by remember { mutableStateOf(sessionManager.isAutoStartEnabled()) }
    var saveRuleRecords by remember { mutableStateOf(RuleRecordingPrefs.isEnabled(context)) }
    var showHomeTimeline by remember { mutableStateOf(RuleRecordingPrefs.isHomeTimelineEnabled(context)) }
    var hideCompactHudText by remember { mutableStateOf(RuleRecordingPrefs.isCompactHudTextHidden(context)) }
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
                                    (context as? android.app.Activity)?.let { activity ->
                                        activity.finish()
                                        activity.startActivity(activity.intent)
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
                title = stringResource(R.string.compact_hud_indicator_only),
                summary = stringResource(R.string.compact_hud_indicator_only_desc),
                checked = hideCompactHudText,
                onCheckedChange = {
                    hideCompactHudText = it
                    RuleRecordingPrefs.setCompactHudTextHidden(context, it)
                    sessionManager.enqueueGlobalPreferencesSync()
                    FloatingIndicatorOverlay.refreshCurrentOverlay()
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
            title = stringResource(R.string.about_section),
            description = null
        ) {
            AboutSection(
                automaticVersionCheckEnabled = automaticVersionCheckEnabled,
                versionCheckResponse = versionCheckResponse,
                isVersionCheckInFlight = isVersionCheckInFlight,
                onAutomaticVersionCheckChanged = onAutomaticVersionCheckChanged,
                onManualVersionCheck = onManualVersionCheck,
                onOpenVersionDownload = onOpenVersionDownload,
                onOpenOfficialSite = { openSeenotOfficialSitePage(context) },
                onOpenGithub = { openExternalUrl(context, "https://github.com/RoderickQiu/seenot-app") },
                onOpenCreatorHomepage = { openExternalUrl(context, "https://r-q.name/") }
            )
        }
    }
}

@Composable
private fun AboutSection(
    automaticVersionCheckEnabled: Boolean,
    versionCheckResponse: SeenotVersionCheckResponse?,
    isVersionCheckInFlight: Boolean,
    onAutomaticVersionCheckChanged: (Boolean) -> Unit,
    onManualVersionCheck: () -> Unit,
    onOpenVersionDownload: (String) -> Unit,
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
        onOpenVersionDownload = onOpenVersionDownload
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
    onOpenVersionDownload: (String) -> Unit
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

        val downloadUrl = response?.downloadUrl?.takeIf { response.updateAvailable && it.isNotBlank() }
        if (downloadUrl != null) {
            OutlinedButton(
                onClick = { onOpenVersionDownload(downloadUrl) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.version_check_download_action))
            }
        }
    }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiModelSettingsDialog(
    accountState: SeenotAccountState,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val accountApi = remember { SeenotAccountApi(context) }
    val scope = rememberCoroutineScope()
    val initialSettings = remember { ApiConfig.getOwnKeySettings() }
    val initialSttSettings = remember { ApiConfig.getSttSettings() }
    val hasPlus = (accountState as? SeenotAccountState.Ready)?.snapshot?.hasPlus == true
    val hasOwnVisionSetup = initialSettings.apiKey.isNotBlank() &&
        initialSettings.baseUrl.isNotBlank() &&
        initialSettings.model.isNotBlank()
    var preferredAiSource by remember {
        mutableStateOf(
            if (ApiConfig.getAiSource() == AiSource.SEENOT_AI) {
                AiSource.SEENOT_AI
            } else {
                AiSource.BRING_YOUR_OWN_KEY
            }
        )
    }
    val canToggleAiSource = hasPlus && hasOwnVisionSetup
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
    val sttModelIsFixed = sttProvider == AiProvider.DASHSCOPE || sttProvider == AiProvider.GEMINI
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

                Spacer(modifier = Modifier.height(12.dp))

                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(R.string.ai_setup_intro),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (canToggleAiSource) {
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

                        if (sttProvider == AiProvider.DASHSCOPE || sttProvider == AiProvider.GEMINI) {
                            Text(
                                text = stringResource(R.string.voice_input_auto_config_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
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
                                        AiProvider.GEMINI -> "gemini-2.5-flash-preview-tts"
                                        else -> sttModel.trim()
                                    },
                                    apiKey = sttApiKey.trim(),
                                    baseUrl = sttBaseUrl.trim()
                                )
                            )

                            if (canToggleAiSource) {
                                when (preferredAiSource) {
                                    AiSource.SEENOT_AI -> {
                                        if (!ApiConfig.isManagedAiActive()) {
                                            val session = accountApi.createManagedAiSession()
                                            ApiConfig.saveManagedAiSession(
                                                apiKey = session.apiKey,
                                                baseUrl = session.baseUrl,
                                                model = session.model,
                                                expiresAtEpochSeconds = session.expiresAt
                                            )
                                        }
                                    }
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
                                it.message ?: context.getString(R.string.seenot_ai_enable_failed),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        isSaving = false
                    }
                },
                enabled = !isSaving &&
                    apiKey.isNotBlank() &&
                    baseUrl.isNotBlank() &&
                    model.isNotBlank() &&
                    feedbackModel.isNotBlank()
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
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.close))
                }
            }
        }
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
