package com.seenot.app.ui.screens

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.seenot.app.BuildConfig
import com.seenot.app.config.ApiConfig
import com.seenot.app.config.AiProvider
import com.seenot.app.config.ApiSettings
import com.seenot.app.config.recommendedModelPresets
import com.seenot.app.config.QwenRegion
import com.seenot.app.config.SttSettings
import com.seenot.app.config.recommendedSttModelPresets
import com.seenot.app.config.selectableProviders
import com.seenot.app.config.selectableSttProviders
import com.seenot.app.config.RuleRecordingPrefs
import com.seenot.app.domain.SessionManager
import com.seenot.app.service.SeenotAccessibilityService
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
import kotlinx.coroutines.MainScope
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
    voiceInputPackageName: String? = null
) {
    val context = LocalContext.current
    var showHomeTimeline by remember { mutableStateOf(RuleRecordingPrefs.isHomeTimelineEnabled(context)) }
    var showAiSettingsDialog by remember { mutableStateOf(false) }

    // State
    var selectedTab by remember { mutableIntStateOf(0) }
    var isAccessibilityEnabled by remember { mutableStateOf(false) }
    var isOverlayEnabled by remember { mutableStateOf(false) }
    var isNotificationEnabled by remember { mutableStateOf(false) }
    var isMicrophoneEnabled by remember { mutableStateOf(false) }
    var showVoiceInput by remember { mutableStateOf(startWithVoiceInput) }
    var currentVoiceInputPackage by remember { mutableStateOf(voiceInputPackageName) }

    // Rule records state
    var showRuleRecordingSettings by remember { mutableStateOf(false) }
    var showRuleRecordsPage by remember { mutableStateOf(false) }

    // Activity result launcher for overlay permission
    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        // Refresh overlay permission status when returning from settings
        isOverlayEnabled = Settings.canDrawOverlays(context)
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

    // Request notification permission on first launch
    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val notificationManager = context.getSystemService(android.app.NotificationManager::class.java)
            isNotificationEnabled = notificationManager?.areNotificationsEnabled() == true
            if (!isNotificationEnabled) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            isNotificationEnabled = true
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

    // Determine if required permissions are granted. Microphone is optional because text input works.
    val allPermissionsGranted = isAccessibilityEnabled && isOverlayEnabled && isNotificationEnabled
    val isAiConfigured = remember(showAiSettingsDialog) { ApiConfig.isConfigured() }
    val isHomeReady = allPermissionsGranted && isAiConfigured

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
                        label = { Text("首页") },
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 }
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Apps, contentDescription = "Apps") },
                        label = { Text("应用") },
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 }
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                        label = { Text("设置") },
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
                        isMicrophoneEnabled = isMicrophoneEnabled,
                        allPermissionsGranted = allPermissionsGranted,
                        isAiConfigured = isAiConfigured,
                        isHomeReady = isHomeReady,
                        onEnableAccessibility = {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        },
                        onEnableOverlay = {
                            overlayPermissionLauncher.launch(
                                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                            )
                        },
                        onRequestMicrophone = {
                            microphonePermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                        },
                        onOpenAiSettings = { showAiSettingsDialog = true },
                        showHomeTimeline = showHomeTimeline,
                        modifier = Modifier.padding(padding)
                    )
                    1 -> AppsTab(modifier = Modifier.padding(padding))
                    2 -> SettingsTab(
                        modifier = Modifier.padding(padding),
                        aiSettingsRefreshKey = showAiSettingsDialog,
                        onOpenAiSettings = { showAiSettingsDialog = true },
                        onOpenRuleRecordingSettings = { showRuleRecordingSettings = true },
                        onOpenRuleRecords = { showRuleRecordsPage = true }
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

                            MainScope().launch {
                                try {
                                    android.util.Log.d("MainScreen", ">>> Calling createSession for $appName")
                                    sessionManager.createSession(
                                        packageName = pkgName,
                                        displayName = appName,
                                        constraints = constraints
                                    )
                                    android.util.Log.d("MainScreen", "<<< Session created for $appName with ${constraints.size} constraints")
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

    // Rule Recording Settings Dialog
    if (showRuleRecordingSettings) {
        val repository = remember { RuleRecordRepository(context) }
        RuleRecordingSettingsDialog(
            repository = repository,
            onDismiss = {
                showRuleRecordingSettings = false
                showHomeTimeline = RuleRecordingPrefs.isHomeTimelineEnabled(context)
            }
        )
    }

    if (showAiSettingsDialog) {
        AiModelSettingsDialog(
            onDismiss = { showAiSettingsDialog = false }
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
    isMicrophoneEnabled: Boolean,
    allPermissionsGranted: Boolean,
    isAiConfigured: Boolean,
    isHomeReady: Boolean,
    onEnableAccessibility: () -> Unit,
    onEnableOverlay: () -> Unit,
    onRequestMicrophone: () -> Unit,
    onOpenAiSettings: () -> Unit,
    showHomeTimeline: Boolean,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    var showCompletedConfigDetails by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(16.dp)
    ) {
        Text(
            text = "欢迎使用 SeeNot",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "AI 注意力管理助手",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Permission section - always reachable; expanded by default until required setup is complete.
        if (!isHomeReady || showCompletedConfigDetails) {
            Text(
                text = "权限状态",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Accessibility Permission
            PermissionCard(
                title = "无障碍服务",
                description = "用于检测应用切换、截图和执行手势",
                isEnabled = isAccessibilityEnabled,
                onClick = onEnableAccessibility
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Overlay Permission
            PermissionCard(
                title = "悬浮窗权限",
                description = "用于显示语音输入和会话状态悬浮窗",
                isEnabled = isOverlayEnabled,
                onClick = onEnableOverlay
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Notification Permission
            PermissionCard(
                title = "通知权限",
                description = "用于显示提醒通知和前台服务",
                isEnabled = isNotificationEnabled,
                onClick = { /* Notification permission handled automatically */ }
            )

            Spacer(modifier = Modifier.height(8.dp))

            PermissionCard(
                title = "麦克风权限（可选）",
                description = "用于语音输入声明意图",
                isEnabled = isMicrophoneEnabled,
                onClick = onRequestMicrophone
            )

            Spacer(modifier = Modifier.height(8.dp))

            Spacer(modifier = Modifier.height(24.dp))
        }

        if (!isHomeReady || showCompletedConfigDetails) {
            Text(
                text = "AI 设置",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(12.dp))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenAiSettings() }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (isAiConfigured) Icons.Default.CheckCircle else Icons.Default.Tune,
                        contentDescription = null,
                        tint = if (isAiConfigured) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.secondary
                        }
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "AI 设置",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = if (isAiConfigured) {
                                "已完成配置，点按修改 provider、模型或 API Key"
                            } else {
                                "尚未配置，点按完成 AI 设置"
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
                            text = "基础配置已完成，可以正常使用",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = if (showCompletedConfigDetails) "点按收起权限与配置入口" else "点按展开权限与配置入口",
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
                    Text(
                        text = "请先完成必要权限和 AI 配置",
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Quick Start Guide
        Text(
            text = "使用说明",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(12.dp))

        Card {
            Column(modifier = Modifier.padding(16.dp)) {
                StepItem(number = 1, text = "在「应用」页面选择要管理的 App")
                Spacer(modifier = Modifier.height(8.dp))
                StepItem(number = 2, text = "打开受控 App 时，声明你的意图")
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "例如：「只看工作消息，10分钟」。你可以用语音，也可以直接输入。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                StepItem(number = 3, text = "AI 会实时守护你的意图，并适时提醒或干预")
                Spacer(modifier = Modifier.height(8.dp))
                StepItem(number = 4, text = "若系统理解错了，可随时报误报让 AI 修正")
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
fun AppsTab(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    // Get SessionManager instance
    val sessionManager = remember { SessionManager.getInstance(context) }

    // State for controlled apps from SessionManager
    var controlledApps by remember { mutableStateOf<Set<String>>(emptySet()) }

    // State for add app dialog
    var showAddAppDialog by remember { mutableStateOf(false) }
    // State for app rules dialog
    var selectedAppForRules by remember { mutableStateOf<AppInfo?>(null) }

    // Collect controlled apps from SessionManager
    LaunchedEffect(Unit) {
        controlledApps = sessionManager.controlledApps.value
        sessionManager.controlledApps.collectLatest {
            controlledApps = it
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
                text = "受控应用",
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
                Text("添加应用")
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
                        text = "尚未添加受控应用",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "点击「添加应用」按钮选择要管理的应用",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
                        onDelete = {
                            sessionManager.removeControlledApp(app.packageName)
                        },
                        onEditRules = {
                            selectedAppForRules = app
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
                showAddAppDialog = false
            }
        )
    }

    // App Rules Dialog
    selectedAppForRules?.let { app ->
        AppRulesDialog(
            app = app,
            sessionManager = sessionManager,
            onDismiss = { selectedAppForRules = null }
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
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val uiScope = rememberCoroutineScope()
    val appHintRepo = remember { AppHintRepository(context) }

    var historyRules by remember { mutableStateOf<List<List<SessionConstraint>>>(emptyList()) }
    var presetRules by remember { mutableStateOf<List<SessionConstraint>>(emptyList()) }
    var lastIntentRules by remember { mutableStateOf<List<SessionConstraint>>(emptyList()) }
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

    LaunchedEffect(app.packageName) {
        val loadedPresetRules = sessionManager.loadPresetRules(app.packageName)
        presetRules = loadedPresetRules

        val loadedHistoryRules = sessionManager.loadIntentHistory(app.packageName)
        val presetFingerprints = loadedPresetRules.map { sessionManager.getConstraintFingerprint(listOf(it)) }.toSet()
        historyRules = loadedHistoryRules.filter { history ->
            val fingerprint = sessionManager.getConstraintFingerprint(history)
            fingerprint !in presetFingerprints
        }
        lastIntentRules = sessionManager.loadLastIntent(app.packageName).orEmpty()
        reloadHints()
    }

    val intentOptions = remember(presetRules, historyRules, lastIntentRules, hints) {
        buildHintIntentOptions(
            presetRules = presetRules,
            historyRules = historyRules,
            lastIntentRules = lastIntentRules,
            existingHints = hints
        )
    }

    if (showAddHintDialog) {
        AlertDialog(
            onDismissRequest = {
                showAddHintDialog = false
                newHintText = ""
                selectedHintScopeType = AppHintScopeType.INTENT_SPECIFIC
                selectedHintIntentId = null
                selectedHintIntentLabel = null
            },
            title = { Text("添加 AI 补充规则") },
            text = {
                Column {
                    Text(
                        text = "可选择让这条规则对整个 app 生效，或只放在某一条具体意图下面。",
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
                        placeholder = { Text("例如：不要仅因详情页露出商品卡片，就识别成推荐列表") },
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
                                buildAppGeneralScopeLabel()
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
                    Text("添加")
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
                    Text("取消")
                }
            }
        )
    }

    if (editingHint != null) {
        val hint = editingHint!!
        AlertDialog(
            onDismissRequest = {
                editingHint = null
                editingHintText = ""
                selectedHintScopeType = AppHintScopeType.INTENT_SPECIFIC
                selectedHintIntentId = null
                selectedHintIntentLabel = null
            },
            title = { Text("编辑 AI 补充规则") },
            text = {
                Column {
                    Text(
                        text = "可同时修改规则内容，以及它更适合放在哪一类里。",
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
                        placeholder = { Text("例如：不要仅因详情页露出商品卡片，就识别成推荐列表") },
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
                                buildAppGeneralScopeLabel()
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
                    Text("保存")
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
                    Text("取消")
                }
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${app.name} 的意图") },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "预设意图",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        TextButton(onClick = { showAddPresetDialog = true }) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("添加")
                        }
                    }
                }

                item {
                    if (presetRules.isEmpty()) {
                        Text(
                            text = "暂无预设意图，点击添加按钮创建",
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
                                    text = "${constraintTypeLabel(constraint.type)}: ${constraint.description}",
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
                                        presetRules = newPresets
                                        sessionManager.savePresetRules(app.packageName, newPresets)
                                    },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = if (constraint.isDefault) Icons.Filled.Star else Icons.Outlined.StarBorder,
                                        contentDescription = if (constraint.isDefault) "取消默认" else "设为默认",
                                        tint = if (constraint.isDefault) Color(0xFFFFB300) else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        val newPresets = presetRules.toMutableList().apply { removeAt(index) }
                                        presetRules = newPresets
                                        sessionManager.savePresetRules(app.packageName, newPresets)
                                    },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "删除",
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
                        text = "历史意图",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                item {
                    if (historyRules.isEmpty()) {
                        Text(
                            text = "暂无历史意图",
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
                                        text = "${constraintTypeLabel(constraint.type)}: ${constraint.description}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                            IconButton(
                                onClick = {
                                    val newHistory = historyRules.toMutableList().apply { removeAt(index) }
                                    historyRules = newHistory
                                    sessionManager.saveIntentHistory(app.packageName, newHistory)
                                },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "删除",
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
                            text = "AI 补充规则",
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
                            Text("添加")
                        }
                    }
                }

                item {
                    Text(
                        text = "补充规则分成两类：一类对整个 app 都生效，一类只用来细化某条具体意图。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                item {
                    if (hints.isEmpty()) {
                        Text(
                            text = "暂无补充规则",
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
                            text = "整个 app 都适用",
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
                            text = "只对某条意图生效",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }

                groupedIntentHints.forEach { group ->
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = displayHintIntentLabel(group.first().intentLabel),
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
                Text("关闭")
            }
        }
    )

    if (showAddPresetDialog) {
        AddPresetRuleDialog(
            onDismiss = { showAddPresetDialog = false },
            onConfirm = { newRule ->
                val newPresets = presetRules + newRule
                presetRules = newPresets
                sessionManager.savePresetRules(app.packageName, newPresets)
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
                        .map { sessionManager.getConstraintFingerprint(listOf(it)) }
                        .toMutableSet()
                    val constraintsToAdd = updatedConstraints.mapNotNull { constraint ->
                        val fingerprint = sessionManager.getConstraintFingerprint(listOf(constraint))
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
                        presetRules = newPresets
                        sessionManager.savePresetRules(app.packageName, newPresets)
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
                    presetRules = newPresets
                    sessionManager.savePresetRules(app.packageName, newPresets)
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
            text = "适用范围",
            style = MaterialTheme.typography.labelMedium
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = selectedScopeType == AppHintScopeType.APP_GENERAL,
                onClick = { onScopeTypeSelected(AppHintScopeType.APP_GENERAL) },
                label = { Text("整个 app 都适用") }
            )
            FilterChip(
                selected = selectedScopeType == AppHintScopeType.INTENT_SPECIFIC,
                onClick = { onScopeTypeSelected(AppHintScopeType.INTENT_SPECIFIC) },
                label = { Text("只对这条意图生效") }
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
                    text = "来源：${sourceLabelForHint(hint.source)}",
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
                        contentDescription = "编辑",
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
                        contentDescription = "删除",
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
        val newIntentLabel = buildIntentScopedHintLabel(updated)
        if (oldIntentId != newIntentId || buildIntentScopedHintLabel(original) != newIntentLabel) {
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

private fun displayHintIntentLabel(rawLabel: String): String {
    val match = Regex("""^(.*?)(?:\s*\((.*)\))?$""").matchEntire(rawLabel.trim()) ?: return rawLabel
    val base = match.groupValues[1].trim()
    val extrasRaw = match.groupValues.getOrNull(2)?.trim().orEmpty()
    if (extrasRaw.isBlank()) return base

    val filteredExtras = extrasRaw
        .split("/")
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .filterNot {
            it == TimeScope.SESSION.name ||
                it == TimeScope.PER_CONTENT.name ||
                it == TimeScope.CONTINUOUS.name ||
                it == TimeScope.DAILY_TOTAL.name
        }

    return if (filteredExtras.isEmpty()) {
        base
    } else {
        "$base (${filteredExtras.joinToString(" / ")})"
    }
}

private fun buildHintIntentOptions(
    presetRules: List<SessionConstraint>,
    historyRules: List<List<SessionConstraint>>,
    lastIntentRules: List<SessionConstraint>,
    existingHints: List<com.seenot.app.data.model.AppHint>
): List<HintIntentOption> {
    val options = linkedMapOf<String, HintIntentOption>()

    fun addConstraint(constraint: SessionConstraint) {
        val intentId = buildIntentScopedHintId(constraint)
        options.putIfAbsent(
            intentId,
            HintIntentOption(
                intentId = intentId,
                intentLabel = displayHintIntentLabel(buildIntentScopedHintLabel(constraint))
            )
        )
    }

    presetRules.forEach(::addConstraint)
    historyRules.flatten().forEach(::addConstraint)
    lastIntentRules.forEach(::addConstraint)
    existingHints.filter { it.scopeType == AppHintScopeType.INTENT_SPECIFIC }.forEach { hint ->
        options.putIfAbsent(
            hint.intentId,
            HintIntentOption(
                intentId = hint.intentId,
                intentLabel = displayHintIntentLabel(hint.intentLabel)
            )
        )
    }

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
            text = "当前还没有可绑定的意图，请先保留至少一个意图记录。",
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
            label = { Text("关联意图") },
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

private fun sourceLabelForHint(source: String): String {
    return when (source) {
        APP_HINT_SOURCE_MANUAL -> "手动添加"
        APP_HINT_SOURCE_FEEDBACK_GENERATED -> "纠错生成"
        APP_HINT_SOURCE_INTENT_CARRY_OVER -> "自动带入"
        else -> source
    }
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
        title = "添加预设意图",
        initialConstraint = SessionConstraint(
            id = java.util.UUID.randomUUID().toString(),
            type = ConstraintType.DENY,
            description = "",
            interventionLevel = InterventionLevel.MODERATE
        ),
        confirmText = "添加",
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
        title = "编辑预设意图",
        initialConstraint = constraint,
        confirmText = "保存",
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
        title = { Text("编辑意图") },
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
                                        ConstraintType.DENY -> "禁止"
                                        ConstraintType.TIME_CAP -> "时间限制"
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Row {
                                    IconButton(
                                        onClick = { editingIndex = index },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Edit,
                                            contentDescription = "编辑",
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
                                            contentDescription = "删除",
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
                                    "时间限制: ${minutes.toInt()} 分钟"
                                } else {
                                    "时间限制: $minutes 分钟"
                                }
                                Text(
                                    text = timeText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Text(
                                text = "干预级别: ${
                                    when (constraint.interventionLevel) {
                                        InterventionLevel.GENTLE -> "温柔"
                                        InterventionLevel.MODERATE -> "中等"
                                        InterventionLevel.STRICT -> "严格"
                                    }
                                }",
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
                Text("保存为预设")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
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
        title = "编辑意图项",
        initialConstraint = constraint,
        confirmText = "保存",
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
    var ruleType by remember(initialConstraint) { mutableStateOf(initialConstraint.type) }
    var description by remember(initialConstraint) { mutableStateOf(initialConstraint.description) }
    var timeLimitMinutes by remember(initialConstraint) {
        mutableStateOf(initialConstraint.timeLimitMs?.let { formatTimeLimitMinutes(it) } ?: "")
    }
    var timeScope by remember(initialConstraint) {
        mutableStateOf(initialConstraint.timeScope ?: TimeScope.SESSION)
    }
    var interventionLevel by remember(initialConstraint) {
        mutableStateOf(initialConstraint.interventionLevel)
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
                onInterventionLevelChange = { interventionLevel = it }
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
                        interventionLevel = interventionLevel
                    )?.let(onConfirm)
                },
                enabled = description.isNotBlank()
            ) {
                Text(confirmText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
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
    onInterventionLevelChange: (InterventionLevel) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 430.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(text = "意图类型", style = MaterialTheme.typography.labelMedium)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ConstraintType.entries.forEach { type ->
                FilterChip(
                    selected = ruleType == type,
                    onClick = { onRuleTypeChange(type) },
                    label = { Text(constraintTypeLabel(type)) }
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = description,
            onValueChange = onDescriptionChange,
            label = { Text("描述") },
            placeholder = { Text("例如：短视频、朋友圈和视频号") },
            modifier = Modifier.fillMaxWidth(),
            supportingText = {
                Text(
                    "💡 可以输入多个条件，如：朋友圈和视频号",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            },
            singleLine = true
        )

        Spacer(modifier = Modifier.height(12.dp))

        if (ruleType == ConstraintType.TIME_CAP || ruleType == ConstraintType.DENY) {
            OutlinedTextField(
                value = timeLimitMinutes,
                onValueChange = { value ->
                    onTimeLimitMinutesChange(value.filter { c -> c.isDigit() || c == '.' })
                },
                label = { Text("时间限制（分钟，可选）") },
                placeholder = { Text("例如：30 或 0.5") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            if (ruleType == ConstraintType.TIME_CAP && timeLimitMinutes.isNotBlank()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(text = "时间范围", style = MaterialTheme.typography.labelMedium)
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    TimeScope.entries.forEach { scope ->
                        FilterChip(
                            selected = timeScope == scope,
                            onClick = { onTimeScopeChange(scope) },
                            label = { Text(timeScopeLabel(scope)) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(text = "干预级别", style = MaterialTheme.typography.labelMedium)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            InterventionLevel.entries.forEach { level ->
                FilterChip(
                    selected = interventionLevel == level,
                    onClick = { onInterventionLevelChange(level) },
                    label = { Text(interventionLevelLabel(level)) }
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
                text = interventionLevelDescription(interventionLevel),
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

private fun constraintTypeLabel(type: ConstraintType): String = when (type) {
    ConstraintType.DENY -> "禁止"
    ConstraintType.TIME_CAP -> "时间限制"
}

private fun timeScopeLabel(scope: TimeScope): String = when (scope) {
    TimeScope.SESSION -> "会话级（整个会话计时）"
    TimeScope.PER_CONTENT -> "内容级（只在目标内容时计时）"
    TimeScope.CONTINUOUS -> "连续（不间断计时）"
    TimeScope.DAILY_TOTAL -> "每日累计（跨会话持久化）"
}

private fun interventionLevelLabel(level: InterventionLevel): String = when (level) {
    InterventionLevel.GENTLE -> "温柔"
    InterventionLevel.MODERATE -> "中等"
    InterventionLevel.STRICT -> "严格"
}

private fun interventionLevelDescription(level: InterventionLevel): String = when (level) {
    InterventionLevel.GENTLE -> "仅提醒：弹出提示框提醒用户，但不阻止操作。用户可以继续当前行为。"
    InterventionLevel.MODERATE -> "提醒+返回：弹出提示框，并自动返回上一个界面。适合想要中断但不需要强制禁止的场景。"
    InterventionLevel.STRICT -> "强制返回：直接返回主屏幕，强制中断当前行为。适合需要强力遏制的场景。"
}

/**
 * App Item
 */
@Composable
fun AppItem(
    app: AppInfo,
    onDelete: () -> Unit,
    onEditRules: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // App icon placeholder
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

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.name,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Edit Rules Button
            IconButton(onClick = onEditRules) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = "编辑意图",
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/**
 * Settings Tab
 */
@Composable
fun SettingsTab(
    modifier: Modifier = Modifier,
    aiSettingsRefreshKey: Boolean = false,
    onOpenAiSettings: () -> Unit = {},
    onOpenRuleRecordingSettings: () -> Unit = {},
    onOpenRuleRecords: () -> Unit = {}
) {
    val context = LocalContext.current
    val sessionManager = remember { SessionManager.getInstance(context) }

    var selectedLanguage by remember { mutableStateOf("zh") }
    var autoStart by remember { mutableStateOf(sessionManager.isAutoStartEnabled()) }
    val aiSettings = remember(aiSettingsRefreshKey) { ApiConfig.getSettings() }
    val aiPresetLabel = remember(aiSettings) {
        recommendedModelPresets(aiSettings.provider, aiSettings.qwenRegion)
            .firstOrNull { it.model == aiSettings.model }
            ?.model
            ?: aiSettings.model
    }
    val aiButtonLabel = remember(aiSettings) {
        when {
            aiSettings.model.isBlank() -> "设置模型"
            aiSettings.provider == AiProvider.DASHSCOPE ->
                "Qwen · $aiPresetLabel · ${aiSettings.qwenRegion.displayName}"
            else -> "${aiSettings.provider.displayName} · $aiPresetLabel"
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = "设置",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "AI 模型",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = onOpenAiSettings,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Tune, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(aiButtonLabel)
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Rule Records + Timeline Section
        Text(
            text = "规则记录和时间轴",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onOpenRuleRecordingSettings,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Filled.Settings, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("记录设置")
            }
            OutlinedButton(
                onClick = onOpenRuleRecords,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Filled.History, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("查看记录")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Language
        Text(
            text = "语言 / Language",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = selectedLanguage == "zh",
                onClick = { selectedLanguage = "zh" },
                label = { Text("中文") }
            )
            FilterChip(
                selected = selectedLanguage == "en",
                onClick = { selectedLanguage = "en" },
                label = { Text("English") }
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Auto-start
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = "开机自启动",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "系统启动时自动运行",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = autoStart,
                onCheckedChange = {
                    autoStart = it
                    sessionManager.setAutoStartEnabled(it)
                }
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Export Section
        Text(
            text = "导出",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.height(8.dp))

        var showLogExportDialog by remember { mutableStateOf(false) }

        OutlinedButton(
            onClick = { showLogExportDialog = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Download, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("导出")
        }

        if (showLogExportDialog) {
            ExportDialog(
                onDismiss = { showLogExportDialog = false }
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // About
        Text(
            text = "关于",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "SeeNot v1.0.0",
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = "AI-powered attention management",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiModelSettingsDialog(
    onDismiss: () -> Unit
) {
    val initialSettings = remember { ApiConfig.getSettings() }
    val initialSttSettings = remember { ApiConfig.getSttSettings() }

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

    fun baseUrlSuggestionsFor(target: AiProvider): List<Pair<String, String>> {
        return when (target) {
            AiProvider.DASHSCOPE -> QwenRegion.entries.map { it.displayName to it.baseUrl }
            AiProvider.OPENAI -> listOf("OpenAI" to AiProvider.OPENAI.defaultBaseUrl)
            AiProvider.GEMINI -> listOf("Gemini" to AiProvider.GEMINI.defaultBaseUrl)
            AiProvider.GLM -> listOf("GLM" to AiProvider.GLM.defaultBaseUrl)
            AiProvider.ANTHROPIC -> listOf("Anthropic (Legacy)" to AiProvider.ANTHROPIC.defaultBaseUrl)
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
        val savedBaseUrl = ApiConfig.getBaseUrl(target)
        val nextRegion = if (target == AiProvider.DASHSCOPE) {
            QwenRegion.entries.firstOrNull { it.baseUrl == savedBaseUrl } ?: ApiConfig.getQwenRegion()
        } else {
            defaults.qwenRegion
        }
        val nextBaseUrl = savedBaseUrl
        val nextApiKey = ApiConfig.getApiKey(target)

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
        title = { Text("AI 模型设置") },
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
                        text = { Text("视觉") }
                    )
                    Tab(
                        selected = selectedConfigTab == 1,
                        onClick = { selectedConfigTab = 1 },
                        text = { Text("语音") }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (isDevDashscopeKeyActive) {
                        Text(
                            text = "当前为开发模式，已临时提供 DashScope API Key，可用至 $devDashscopeKeyExpiryText。到期后会自动停用，你仍可在这里手动填写自己的 Key。",
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
                                value = provider.displayName,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("视觉 Provider") },
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
                                        text = { Text(candidate.displayName) },
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
                            label = { Text("API Key") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation()
                        )

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
                                label = { Text("Base URL") },
                                placeholder = { Text("https://.../v1") },
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
                                    baseUrlSuggestions.forEach { (label, value) ->
                                        DropdownMenuItem(
                                            text = { Text(label) },
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
                                label = { Text("视觉模型") },
                                placeholder = { Text("例如：qwen3.6-plus / glm-4.6v-flashx / gpt-4o-mini") },
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
                                                    if (preset.note.isBlank()) preset.model
                                                    else "${preset.model}  ${preset.note}"
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
                                label = { Text("纠偏模型") },
                                placeholder = { Text("报错后生成补充规则时使用") },
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
                                                    if (preset.note.isBlank()) preset.model
                                                    else "${preset.model}  ${preset.note}"
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
                            text = "纠偏模型不会高频使用，只在你报错后生成补充规则时参与分析。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (provider == AiProvider.DASHSCOPE) {
                            Text(
                                text = "Qwen 当前区域：${qwenRegion.displayName}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        ExposedDropdownMenuBox(
                            expanded = sttProviderExpanded,
                            onExpandedChange = { sttProviderExpanded = !sttProviderExpanded }
                        ) {
                            OutlinedTextField(
                                value = sttProvider.displayName,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("语音 Provider") },
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
                                        text = { Text(candidate.displayName) },
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
                                Text(if (sttUsesSharedProviderConfig) "API Key（共享）" else "API Key（独立）")
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
                                    Text(if (sttUsesSharedProviderConfig) "Base URL（共享）" else "Base URL（独立）")
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
                                    sttBaseUrlSuggestions.forEach { (label, value) ->
                                        DropdownMenuItem(
                                            text = { Text(label) },
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
                                    label = { Text("语音模型") },
                                    placeholder = { Text("例如：gpt-4o-mini-transcribe / glm-asr-2512") },
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
                                text = "语音输入会自动配置，无需再填语音模型。",
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

                    onDismiss()
                },
                enabled = apiKey.isNotBlank() &&
                    baseUrl.isNotBlank() &&
                    model.isNotBlank() &&
                    feedbackModel.isNotBlank() &&
                    sttApiKey.isNotBlank() &&
                    sttBaseUrl.isNotBlank() &&
                    (sttModelIsFixed || sttModel.isNotBlank())
            ) {
                Text("保存")
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
                    Text("恢复默认")
                }
                TextButton(onClick = onDismiss) {
                    Text("关闭")
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
                // Filter out system apps (except launcher)
                val isSystemApp = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                if (isSystemApp) {
                    android.util.Log.d("AppsTab", "Filtering out system app: $packageName")
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
    var selectedApp by remember { mutableStateOf<AppInfo?>(null) }
    var showAppList by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val allApps = getInstalledApps(context)
        // Filter out already added apps
        installedApps = allApps.filter { it.packageName !in existingAppPackages }
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
                    text = "添加应用",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(16.dp))

                if (installedApps.isEmpty()) {
                    Text(
                        text = "没有可添加的应用",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    // App selector button
                    OutlinedButton(
                        onClick = { showAppList = !showAppList },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = selectedApp?.name ?: "选择应用",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            Icons.Default.ArrowDropDown,
                            contentDescription = null
                        )
                    }

                    // App list
                    if (showAppList) {
                        Spacer(modifier = Modifier.height(8.dp))

                        // Search field
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("搜索...") },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            singleLine = true
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                        ) {
                            items(filteredApps) { app ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            selectedApp = app
                                            showAppList = false
                                            searchQuery = ""
                                        }
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
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("取消")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            selectedApp?.let { app ->
                                onAppSelected(app.packageName)
                            }
                        },
                        enabled = selectedApp != null
                    ) {
                        Text("添加")
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
                        VoiceRecordingState.RECORDING -> "正在录音..."
                        VoiceRecordingState.PROCESSING -> "正在处理..."
                        VoiceRecordingState.TRANSCRIBED -> "识别结果"
                        VoiceRecordingState.PARSED -> "解析完成"
                        VoiceRecordingState.ERROR -> "出现错误"
                        else -> "声明你的意图"
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "当前应用: $appDisplayName",
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
                        Text("继续使用上次意图")
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
                            Text("停止录音")
                        }
                    }
                    recordingState == VoiceRecordingState.PROCESSING -> {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "正在识别语音...",
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
                                    text = "识别内容:",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                )
                                Text(
                                    text = parsedIntent!!.text,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "解析结果:",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                )
                                parsedIntent!!.constraints.forEach { constraint ->
                                    Text(
                                        text = "• ${constraintTypeLabel(constraint.type)}: ${constraint.description}",
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
                            Text("意图已确定")
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
                            Text("正在解析意图...")
                        }
                    }
                    showTextInput -> {
                        // Text input mode
                        OutlinedTextField(
                            value = textInput,
                            onValueChange = { textInput = it },
                            label = { Text("输入你的意图") },
                            placeholder = { Text("例如：只看工作消息，10分钟") },
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
                            Text(if (recordingState == VoiceRecordingState.PROCESSING) "处理中..." else "确认")
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
                            Text("开始语音输入")
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
                            Text("使用文本输入")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Skip button
                TextButton(
                    onClick = onDismiss,
                    enabled = recordingState != VoiceRecordingState.PROCESSING
                ) {
                    Text("跳过")
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
                exportMessage = "正在导入应用配置..."
                try {
                    val importedCount = configurationExporter.importConfiguration(uri) { progress ->
                        exportMessage = progress
                    }.getOrThrow()
                    exportMessage = "导入成功！共导入 $importedCount 个应用配置"
                } catch (e: Exception) {
                    exportMessage = "导入失败: ${e.message}"
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
        title = { Text("导出") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                val availableTabs = buildList {
                    add("应用配置")
                    add("日志")
                    if (runtimeEventEnabled) {
                        add("运行事件")
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
                        text = { Text("应用配置") }
                    )
                    Tab(
                        selected = safeSelectedTab == 1,
                        onClick = {
                            selectedExportTab = 1
                            exportMessage = ""
                        },
                        text = { Text("日志") }
                    )
                    if (runtimeEventEnabled) {
                        Tab(
                            selected = safeSelectedTab == 2,
                            onClick = {
                                selectedExportTab = 2
                                exportMessage = ""
                            },
                            text = { Text("运行事件") }
                        )
                    }
                }

                if (safeSelectedTab == 0) {
                    Text(
                        text = "导出当前监控应用、预设意图、默认意图、最近意图历史和 AI 补充说明。",
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
                                    exportMessage = "正在导出应用配置..."
                                    try {
                                        val uri = configurationExporter.exportConfiguration { progress ->
                                            exportMessage = progress
                                        }
                                        if (uri != null) {
                                            configurationExporter.shareExportedFile(uri) { error ->
                                                exportMessage = error
                                            }
                                            if (!exportMessage.contains("失败")) {
                                                exportMessage = "配置导出成功！"
                                            }
                                        } else if (exportMessage.isBlank()) {
                                            exportMessage = "配置导出失败"
                                        }
                                    } catch (e: Exception) {
                                        exportMessage = "配置导出失败: ${e.message}"
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
                                Text("导出")
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
                            Text("导入")
                        }
                    }
                } else if (safeSelectedTab == 1) {
                    Text(
                        text = "分享全部日志，或按日期范围分享日志。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedTextField(
                        value = startDate,
                        onValueChange = { startDate = it },
                        label = { Text("开始日期 (yyyy-MM-dd)") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isExporting
                    )

                    OutlinedTextField(
                        value = endDate,
                        onValueChange = { endDate = it },
                        label = { Text("结束日期 (yyyy-MM-dd)") },
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
                                    exportMessage = "正在分享所有日志..."
                                    try {
                                        val success = com.seenot.app.utils.Logger.shareAllLogs(context)
                                        exportMessage = if (success) "分享成功！" else "分享失败，请检查日志"
                                        if (success) {
                                            kotlinx.coroutines.delay(1000)
                                            onDismiss()
                                        }
                                    } catch (e: Exception) {
                                        exportMessage = "分享失败: ${e.message}"
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
                                Text("分享全部")
                            }
                        }

                        Button(
                            onClick = {
                                scope.launch {
                                    isExporting = true
                                    exportMessage = "正在分享日志..."
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
                                                "分享成功！"
                                            } else {
                                                "分享失败，请检查日期格式和日志"
                                            }
                                            if (success) {
                                                kotlinx.coroutines.delay(1000)
                                                onDismiss()
                                            }
                                        } else {
                                            exportMessage = "日期格式错误"
                                        }
                                    } catch (e: Exception) {
                                        exportMessage = "分享失败: ${e.message}"
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
                                Text("分享范围")
                            }
                        }
                    }
                } else {
                    Text(
                        text = "导出结构化运行事件 JSONL，可按时间范围筛选。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedTextField(
                        value = startDate,
                        onValueChange = { startDate = it },
                        label = { Text("开始日期 (yyyy-MM-dd)") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isExporting
                    )

                    OutlinedTextField(
                        value = endDate,
                        onValueChange = { endDate = it },
                        label = { Text("结束日期 (yyyy-MM-dd)") },
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
                                    exportMessage = "正在导出运行事件..."
                                    try {
                                        val uri = runtimeEventLogger.exportAll { progress ->
                                            exportMessage = progress
                                        }
                                        if (uri != null) {
                                            runtimeEventLogger.shareExportedFile(uri) { error ->
                                                exportMessage = error
                                            }
                                            if (!exportMessage.contains("失败")) {
                                                exportMessage = "运行事件导出成功！"
                                            }
                                        }
                                    } catch (e: Exception) {
                                        exportMessage = "运行事件导出失败: ${e.message}"
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
                                Text("导出全部")
                            }
                        }

                        Button(
                            onClick = {
                                scope.launch {
                                    isExporting = true
                                    exportMessage = "正在导出运行事件..."
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
                                                if (!exportMessage.contains("失败")) {
                                                    exportMessage = "运行事件导出成功！"
                                                }
                                            }
                                        } else {
                                            exportMessage = "日期格式错误"
                                        }
                                    } catch (e: Exception) {
                                        exportMessage = "运行事件导出失败: ${e.message}"
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
                                Text("导出范围")
                            }
                        }
                    }
                }

                if (exportMessage.isNotEmpty()) {
                    Text(
                        text = exportMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (exportMessage.contains("成功")) {
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
                Text("关闭")
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
