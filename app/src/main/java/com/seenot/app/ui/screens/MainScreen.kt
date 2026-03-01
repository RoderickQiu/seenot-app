package com.seenot.app.ui.screens

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.seenot.app.domain.SessionManager
import com.seenot.app.service.SeenotAccessibilityService
import com.seenot.app.ai.voice.VoiceInputManager
import com.seenot.app.ai.voice.VoiceRecordingState
import com.seenot.app.ui.overlay.VoiceInputOverlay
import com.seenot.app.data.repository.RuleRecordRepository
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

    // Determine if all permissions are granted
    val allPermissionsGranted = isAccessibilityEnabled && isOverlayEnabled &&
        isNotificationEnabled && isMicrophoneEnabled

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
            Box(modifier = Modifier.fillMaxSize()) {
                when (selectedTab) {
                    0 -> HomeTab(
                        isAccessibilityEnabled = isAccessibilityEnabled,
                        isOverlayEnabled = isOverlayEnabled,
                        isNotificationEnabled = isNotificationEnabled,
                        isMicrophoneEnabled = isMicrophoneEnabled,
                        allPermissionsGranted = allPermissionsGranted,
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
                        modifier = Modifier.padding(padding)
                    )
                    1 -> AppsTab(modifier = Modifier.padding(padding))
                    2 -> SettingsTab(
                        modifier = Modifier.padding(padding),
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
            onDismiss = { showRuleRecordingSettings = false }
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
    onEnableAccessibility: () -> Unit,
    onEnableOverlay: () -> Unit,
    onRequestMicrophone: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
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

        // Permission section - only show if not all permissions granted
        if (!allPermissionsGranted) {
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

            // Microphone Permission
            PermissionCard(
                title = "麦克风权限",
                description = "用于语音输入声明意图",
                isEnabled = isMicrophoneEnabled,
                onClick = onRequestMicrophone
            )

            Spacer(modifier = Modifier.height(24.dp))
        }

        // Status Summary
        if (allPermissionsGranted) {
            Card(
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
                    Text(
                        text = "所有权限已开启，可以正常使用",
                        style = MaterialTheme.typography.bodyLarge
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
                        text = "请开启所有必要权限",
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
                StepItem(number = 2, text = "打开受控 App 时，语音声明你的意图")
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "例如：「只看工作消息，10分钟」",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                StepItem(number = 3, text = "AI 会实时守护你的意图，发现违规时提醒或干预")
            }
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

    Column(modifier = modifier.fillMaxSize()) {
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
                    .fillMaxSize()
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
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(controlledAppList) { app ->
                    AppItem(
                        app = app,
                        onDelete = {
                            sessionManager.removeControlledApp(app.packageName)
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
}

/**
 * App Item
 */
@Composable
fun AppItem(
    app: AppInfo,
    onDelete: () -> Unit
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
    onOpenRuleRecordingSettings: () -> Unit = {},
    onOpenRuleRecords: () -> Unit = {}
) {
    var selectedLanguage by remember { mutableStateOf("zh") }
    var reuseStrategy by remember { mutableStateOf("ask") }
    var autoStart by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "设置",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Rule Records Section
        Text(
            text = "规则记录",
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

        // Intent Reuse Strategy
        Text(
            text = "意图复用策略",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.height(8.dp))
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = reuseStrategy == "ask",
                    onClick = { reuseStrategy = "ask" }
                )
                Text("每次询问")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = reuseStrategy == "reuse",
                    onClick = { reuseStrategy = "reuse" }
                )
                Text("默认复用上次意图")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = reuseStrategy == "new",
                    onClick = { reuseStrategy = "new" }
                )
                Text("默认新建意图")
            }
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
                onCheckedChange = { autoStart = it }
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

/**
 * App Info
 */
data class AppInfo(
    val name: String,
    val packageName: String
)

/**
 * Get installed apps (excluding system apps and SeeNot itself)
 */
private fun getInstalledApps(context: android.content.Context): List<AppInfo> {
    val pm = context.packageManager

    // First, get all packages without filtering to see the total count
    val allPackages = pm.getInstalledPackages(PackageManager.GET_META_DATA)
    android.util.Log.d("AppsTab", "Total installed packages: ${allPackages.size}")

    val apps = allPackages
        .filter { packageInfo ->
            // Filter out system apps
            val appInfo = packageInfo.applicationInfo
            appInfo != null && (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) == 0
        }
        .map { packageInfo ->
            val appInfo = packageInfo.applicationInfo!!
            AppInfo(
                name = pm.getApplicationLabel(appInfo).toString(),
                packageName = packageInfo.packageName
            )
        }
        .filter { it.packageName != context.packageName }

    android.util.Log.d("AppsTab", "Filtered user apps: ${apps.size}")
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
                                        text = "• ${constraint.type.name}: ${constraint.description}",
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

private fun getAppDisplayName(context: android.content.Context, packageName: String): String {
    return try {
        val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
        context.packageManager.getApplicationLabel(appInfo).toString()
    } catch (e: Exception) {
        packageName
    }
}
