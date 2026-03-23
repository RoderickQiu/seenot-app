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
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.seenot.app.domain.SessionManager
import com.seenot.app.service.SeenotAccessibilityService
import com.seenot.app.ai.voice.VoiceInputManager
import com.seenot.app.ai.voice.VoiceRecordingState
import com.seenot.app.ai.parser.AppInfo
import com.seenot.app.ui.overlay.VoiceInputOverlay
import com.seenot.app.data.repository.AppHintRepository
import com.seenot.app.data.repository.RuleRecordRepository
import com.seenot.app.data.model.ConstraintType
import com.seenot.app.data.model.InterventionLevel
import com.seenot.app.data.model.TimeScope
import com.seenot.app.domain.SessionConstraint
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
            Box(modifier = Modifier.fillMaxWidth()) {
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
            .fillMaxWidth()
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
    var showAIRuleAssistantDialog by remember { mutableStateOf(false) }

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

        // AI Assistant FAB - positioned at bottom right
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            contentAlignment = Alignment.BottomEnd
        ) {
            FloatingActionButton(
                onClick = { showAIRuleAssistantDialog = true },
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ) {
                Icon(Icons.Default.Psychology, contentDescription = "AI助手")
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

    // AI Rule Assistant Dialog
    if (showAIRuleAssistantDialog) {
        AIRuleAssistantDialog(
            context = context,
            sessionManager = sessionManager,
            monitoredApps = controlledAppList,
            onDismiss = { showAIRuleAssistantDialog = false }
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
    var historyRules by remember { mutableStateOf<List<List<SessionConstraint>>>(emptyList()) }
    var presetRules by remember { mutableStateOf<List<SessionConstraint>>(emptyList()) }
    var showAddPresetDialog by remember { mutableStateOf(false) }
    var editingRuleIndex by remember { mutableStateOf<Int?>(null) }
    var editingPresetIndex by remember { mutableStateOf<Int?>(null) }

    // Hints state
    var hints by remember { mutableStateOf<List<com.seenot.app.data.model.AppHint>>(emptyList()) }
    var showAddHintDialog by remember { mutableStateOf(false) }
    var newHintText by remember { mutableStateOf("") }
    var editingHint by remember { mutableStateOf<com.seenot.app.data.model.AppHint?>(null) }
    var editingHintText by remember { mutableStateOf("") }
    val context = LocalContext.current
    val appHintRepo = remember { com.seenot.app.data.repository.AppHintRepository(context) }

    // Load rules
    LaunchedEffect(app.packageName) {
        val loadedPresetRules = sessionManager.loadPresetRules(app.packageName)
        presetRules = loadedPresetRules

        // Filter out history rules that duplicate preset rules
        val loadedHistoryRules = sessionManager.loadIntentHistory(app.packageName)
        val presetFingerprints = loadedPresetRules.map { sessionManager.getConstraintFingerprint(listOf(it)) }.toSet()
        historyRules = loadedHistoryRules.filter { history ->
            val fingerprint = sessionManager.getConstraintFingerprint(history)
            fingerprint !in presetFingerprints
        }

        // Load hints for this app
        hints = appHintRepo.getHintsForPackage(app.packageName)
    }

    // Add Hint Dialog
    if (showAddHintDialog) {
        AlertDialog(
            onDismissRequest = {
                showAddHintDialog = false
                newHintText = ""
            },
            title = { Text("添加AI补充说明") },
            text = {
                Column {
                    Text(
                        text = "告诉AI这个应用的特殊使用场景，帮助AI更准确判断：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = newHintText,
                        onValueChange = { newHintText = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("例如：我只用这个APP看新闻，不刷短视频") },
                        minLines = 2,
                        maxLines = 4
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newHintText.isNotBlank()) {
                            kotlinx.coroutines.GlobalScope.launch {
                                appHintRepo.addHintFromFeedback(app.packageName, newHintText)
                                hints = appHintRepo.getHintsForPackage(app.packageName)
                            }
                        }
                        showAddHintDialog = false
                        newHintText = ""
                    }
                ) {
                    Text("添加")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showAddHintDialog = false
                    newHintText = ""
                }) {
                    Text("取消")
                }
            }
        )
    }

    // Edit Hint Dialog
    if (editingHint != null) {
        val hint = editingHint!!
        AlertDialog(
            onDismissRequest = {
                editingHint = null
                editingHintText = ""
            },
            title = { Text("编辑AI补充说明") },
            text = {
                Column {
                    Text(
                        text = "修改AI补充说明，帮助AI更准确判断：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = editingHintText,
                        onValueChange = { editingHintText = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("例如：我只用这个APP看新闻，不刷短视频") },
                        minLines = 2,
                        maxLines = 4
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (editingHintText.isNotBlank()) {
                            kotlinx.coroutines.GlobalScope.launch {
                                appHintRepo.updateHintText(hint.id, editingHintText)
                                hints = appHintRepo.getHintsForPackage(app.packageName)
                            }
                        }
                        editingHint = null
                        editingHintText = ""
                    }
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    editingHint = null
                    editingHintText = ""
                }) {
                    Text("取消")
                }
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${app.name} 的规则") },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Preset Rules Section
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "预设规则",
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
                            text = "暂无预设规则，点击添加按钮创建",
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
                                    text = "${constraint.type.name}: ${constraint.description}",
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
                        text = "历史规则",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                item {
                    if (historyRules.isEmpty()) {
                        Text(
                            text = "暂无历史规则",
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
                                        text = "${constraint.type.name}: ${constraint.description}",
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

                // AI Hints Section
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "AI 补充说明",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        TextButton(onClick = { showAddHintDialog = true }) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("添加")
                        }
                    }
                }

                item {
                    if (hints.isEmpty()) {
                        Text(
                            text = "暂无补充说明，添加后可帮助AI更准确判断",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                items(hints.size) { index ->
                    val hint = hints[index]
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                editingHint = hint
                                editingHintText = hint.hintText
                            },
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
                            Text(
                                text = hint.hintText,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(
                                    onClick = {
                                        editingHint = hint
                                        editingHintText = hint.hintText
                                    },
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
                                        kotlinx.coroutines.GlobalScope.launch {
                                            appHintRepo.deleteHint(hint.id)
                                            hints = appHintRepo.getHintsForPackage(app.packageName)
                                        }
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
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )

    // Add Preset Rule Dialog
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

    // Edit Rule Dialog
    editingRuleIndex?.let { index ->
        if (index < historyRules.size) {
            EditHistoryRuleDialog(
                constraints = historyRules[index],
                onDismiss = { editingRuleIndex = null },
                onSave = { updatedConstraints ->
                    val newHistory = historyRules.toMutableList().apply {
                        this[index] = updatedConstraints
                    }
                    historyRules = newHistory
                    sessionManager.saveIntentHistory(app.packageName, newHistory)
                    editingRuleIndex = null
                }
            )
        }
    }

    // Edit Preset Rule Dialog
    editingPresetIndex?.let { index ->
        if (index < presetRules.size) {
            EditPresetRuleDialog(
                constraint = presetRules[index],
                onDismiss = { editingPresetIndex = null },
                onSave = { updatedConstraint ->
                    val newPresets = presetRules.toMutableList().apply {
                        this[index] = updatedConstraint
                    }
                    presetRules = newPresets
                    sessionManager.savePresetRules(app.packageName, newPresets)
                    editingPresetIndex = null
                }
            )
        }
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
    var ruleType by remember { mutableStateOf(ConstraintType.DENY) }
    var description by remember { mutableStateOf("") }
    var timeLimitMinutes by remember { mutableStateOf("") }
    var timeScope by remember { mutableStateOf(TimeScope.SESSION) }
    var interventionLevel by remember { mutableStateOf(InterventionLevel.MODERATE) }
    var showConflictWarning by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加预设规则") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(text = "规则类型", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ConstraintType.entries.forEach { type ->
                        FilterChip(
                            selected = ruleType == type,
                            onClick = { 
                                ruleType = type
                                showConflictWarning = false
                            },
                            label = {
                                Text(
                                    when (type) {
                                        ConstraintType.DENY -> "禁止"
                                        ConstraintType.TIME_CAP -> "时间限制"
                                    }
                                )
                            }
                        )
                    }
                }

                if (showConflictWarning) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Text(
                            text = "⚠️ 注意：DENY（禁止）和 TIME_CAP（时间限制）可以同时存在。如果已有其他类型约束，新约束将替换它。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Description
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
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

                // Time Limit
                if (ruleType == ConstraintType.TIME_CAP || ruleType == ConstraintType.DENY) {
                    OutlinedTextField(
                        value = timeLimitMinutes,
                        onValueChange = { timeLimitMinutes = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("时间限制（分钟，可选）") },
                        placeholder = { Text("例如：30 或 0.5") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    // Time Scope (only if time limit is set)
                    if (ruleType == ConstraintType.TIME_CAP && timeLimitMinutes.isNotBlank()) {
                        Text(text = "时间范围", style = MaterialTheme.typography.labelMedium)
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            TimeScope.entries.forEach { scope ->
                                FilterChip(
                                    selected = timeScope == scope,
                                    onClick = { timeScope = scope },
                                    label = {
                                        Text(
                                            when (scope) {
                                                TimeScope.SESSION -> "会话级（整个会话计时）"
                                                TimeScope.PER_CONTENT -> "内容级（只在目标内容时计时）"
                                                TimeScope.CONTINUOUS -> "连续（不间断计时）"
                                                TimeScope.DAILY_TOTAL -> "每日累计（跨会话持久化）"
                                            }
                                        )
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }

                // Intervention Level
                Text(text = "干预级别", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    InterventionLevel.entries.forEach { level ->
                        FilterChip(
                            selected = interventionLevel == level,
                            onClick = { interventionLevel = level },
                            label = {
                                Text(
                                    when (level) {
                                        InterventionLevel.GENTLE -> "温柔"
                                        InterventionLevel.MODERATE -> "中等"
                                        InterventionLevel.STRICT -> "严格"
                                    }
                                )
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Intervention level description
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Text(
                        text = when (interventionLevel) {
                            InterventionLevel.GENTLE -> "仅提醒：弹出提示框提醒用户，但不阻止操作。用户可以继续当前行为。"
                            InterventionLevel.MODERATE -> "提醒+返回：弹出提示框，并自动返回上一个界面。适合想要中断但不需要强制禁止的场景。"
                            InterventionLevel.STRICT -> "强制返回：直接返回主屏幕，强制中断当前行为。适合需要强力遏制的场景。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (description.isNotBlank()) {
                        val timeLimitMs = if (timeLimitMinutes.isNotBlank()) {
                            timeLimitMinutes.toDoubleOrNull()?.times(60 * 1000)?.toLong()
                        } else null

                        onConfirm(
                            SessionConstraint(
                                id = java.util.UUID.randomUUID().toString(),
                                type = ruleType,
                                description = description,
                                timeLimitMs = timeLimitMs,
                                timeScope = if (timeLimitMs != null) timeScope else null,
                                interventionLevel = interventionLevel
                            )
                        )
                    }
                },
                enabled = description.isNotBlank()
            ) {
                Text("添加")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
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
    var ruleType by remember { mutableStateOf(constraint.type) }
    var description by remember { mutableStateOf(constraint.description) }
    var timeLimitMinutes by remember { mutableStateOf(constraint.timeLimitMs?.let { (it / 60000.0).toString() } ?: "") }
    var timeScope by remember { mutableStateOf(constraint.timeScope ?: TimeScope.SESSION) }
    var interventionLevel by remember { mutableStateOf(constraint.interventionLevel) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑预设规则") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(text = "规则类型", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ConstraintType.entries.forEach { type ->
                        FilterChip(
                            selected = ruleType == type,
                            onClick = { ruleType = type },
                            label = {
                                Text(
                                    when (type) {
                                        ConstraintType.DENY -> "禁止"
                                        ConstraintType.TIME_CAP -> "时间限制"
                                    }
                                )
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
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
                        onValueChange = { timeLimitMinutes = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("时间限制（分钟，可选）") },
                        placeholder = { Text("例如：30 或 0.5") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    if (ruleType == ConstraintType.TIME_CAP && timeLimitMinutes.isNotBlank()) {
                        Text(text = "时间范围", style = MaterialTheme.typography.labelMedium)
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            TimeScope.entries.forEach { scope ->
                                FilterChip(
                                    selected = timeScope == scope,
                                    onClick = { timeScope = scope },
                                    label = {
                                        Text(
                                            when (scope) {
                                                TimeScope.SESSION -> "会话级（整个会话计时）"
                                                TimeScope.PER_CONTENT -> "内容级（只在目标内容时计时）"
                                                TimeScope.CONTINUOUS -> "连续（不间断计时）"
                                                TimeScope.DAILY_TOTAL -> "每日累计（跨会话持久化）"
                                            }
                                        )
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }

                // Intervention Level
                Text(text = "干预级别", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    InterventionLevel.entries.forEach { level ->
                        FilterChip(
                            selected = interventionLevel == level,
                            onClick = { interventionLevel = level },
                            label = {
                                Text(
                                    when (level) {
                                        InterventionLevel.GENTLE -> "温柔"
                                        InterventionLevel.MODERATE -> "中等"
                                        InterventionLevel.STRICT -> "严格"
                                    }
                                )
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Intervention level description
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Text(
                        text = when (interventionLevel) {
                            InterventionLevel.GENTLE -> "仅提醒：弹出提示框提醒用户，但不阻止操作。用户可以继续当前行为。"
                            InterventionLevel.MODERATE -> "提醒+返回：弹出提示框，并自动返回上一个界面。适合想要中断但不需要强制禁止的场景。"
                            InterventionLevel.STRICT -> "强制返回：直接返回主屏幕，强制中断当前行为。适合需要强力遏制的场景。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (description.isNotBlank()) {
                        val timeLimitMs = if (timeLimitMinutes.isNotBlank()) {
                            timeLimitMinutes.toDoubleOrNull()?.times(60 * 1000)?.toLong()
                        } else null

                        onSave(
                            SessionConstraint(
                                id = constraint.id,
                                type = ruleType,
                                description = description,
                                timeLimitMs = timeLimitMs,
                                timeScope = if (timeLimitMs != null) timeScope else null,
                                interventionLevel = interventionLevel,
                                isActive = constraint.isActive
                            )
                        )
                    }
                },
                enabled = description.isNotBlank()
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

/**
 * Edit History Rule Dialog
 */
@Composable
fun EditHistoryRuleDialog(
    constraints: List<SessionConstraint>,
    onDismiss: () -> Unit,
    onSave: (List<SessionConstraint>) -> Unit
) {
    var editedConstraints by remember { mutableStateOf(constraints.toMutableList()) }
    var editingIndex by remember { mutableStateOf<Int?>(null) }

    // Edit single constraint dialog
    val editingConstraint = editingIndex?.let { editedConstraints.getOrNull(it) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑规则") },
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

                item {
                    TextButton(
                        onClick = { onSave(editedConstraints) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("保存更改")
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
    var ruleType by remember { mutableStateOf(constraint.type) }
    var description by remember { mutableStateOf(constraint.description) }
    var timeLimitMinutes by remember {
        mutableStateOf(constraint.timeLimitMs?.let { (it / 60000).toString() } ?: "")
    }
    var timeScope by remember { mutableStateOf(constraint.timeScope ?: TimeScope.SESSION) }
    var interventionLevel by remember { mutableStateOf(constraint.interventionLevel) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑规则项") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(text = "规则类型", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ConstraintType.entries.forEach { type ->
                        FilterChip(
                            selected = ruleType == type,
                            onClick = { ruleType = type },
                            label = {
                                Text(
                                    when (type) {
                                        ConstraintType.DENY -> "禁止"
                                        ConstraintType.TIME_CAP -> "时间限制"
                                    }
                                )
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
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
                        onValueChange = { timeLimitMinutes = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("时间限制（分钟，可选）") },
                        placeholder = { Text("例如：30 或 0.5") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    if (ruleType == ConstraintType.TIME_CAP && timeLimitMinutes.isNotBlank()) {
                        Text(text = "时间范围", style = MaterialTheme.typography.labelMedium)
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            TimeScope.entries.forEach { scope ->
                                FilterChip(
                                    selected = timeScope == scope,
                                    onClick = { timeScope = scope },
                                    label = {
                                        Text(
                                            when (scope) {
                                                TimeScope.SESSION -> "会话级（整个会话计时）"
                                                TimeScope.PER_CONTENT -> "内容级（只在目标内容时计时）"
                                                TimeScope.CONTINUOUS -> "连续（不间断计时）"
                                                TimeScope.DAILY_TOTAL -> "每日累计（跨会话持久化）"
                                            }
                                        )
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }

                // Intervention Level
                Text(text = "干预级别", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    InterventionLevel.entries.forEach { level ->
                        FilterChip(
                            selected = interventionLevel == level,
                            onClick = { interventionLevel = level },
                            label = {
                                Text(
                                    when (level) {
                                        InterventionLevel.GENTLE -> "温柔"
                                        InterventionLevel.MODERATE -> "中等"
                                        InterventionLevel.STRICT -> "严格"
                                    }
                                )
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Intervention level description
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Text(
                        text = when (interventionLevel) {
                            InterventionLevel.GENTLE -> "仅提醒：弹出提示框提醒用户，但不阻止操作。"
                            InterventionLevel.MODERATE -> "提醒+返回：弹出提示框，并自动返回上一个界面。"
                            InterventionLevel.STRICT -> "强制返回：直接返回主屏幕，强制中断当前行为。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (description.isNotBlank()) {
                        val timeLimitMs = if (timeLimitMinutes.isNotBlank()) {
                            timeLimitMinutes.toDoubleOrNull()?.times(60 * 1000)?.toLong()
                        } else null

                        onSave(
                            constraint.copy(
                                type = ruleType,
                                description = description,
                                timeLimitMs = timeLimitMs,
                                timeScope = if (timeLimitMs != null) timeScope else null,
                                interventionLevel = interventionLevel,
                                isDefault = constraint.isDefault
                            )
                        )
                    }
                },
                enabled = description.isNotBlank()
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
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
                    contentDescription = "编辑规则",
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
    onOpenRuleRecordingSettings: () -> Unit = {},
    onOpenRuleRecords: () -> Unit = {}
) {
    val context = LocalContext.current
    val sessionManager = remember { SessionManager.getInstance(context) }

    var selectedLanguage by remember { mutableStateOf("zh") }
    var autoStart by remember { mutableStateOf(sessionManager.isAutoStartEnabled()) }

    Column(
        modifier = modifier
            .fillMaxWidth()
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

        // Logs Section
        Text(
            text = "日志管理",
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
            Text("导出日志")
        }

        if (showLogExportDialog) {
            LogExportDialog(
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

@Composable
fun LogExportDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var startDate by remember { mutableStateOf("") }
    var endDate by remember { mutableStateOf("") }
    var isExporting by remember { mutableStateOf(false) }
    var exportMessage by remember { mutableStateOf("") }

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

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "分享日志",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                // Start date
                OutlinedTextField(
                    value = startDate,
                    onValueChange = { startDate = it },
                    label = { Text("开始日期 (yyyy-MM-dd)") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isExporting
                )

                // End date
                OutlinedTextField(
                    value = endDate,
                    onValueChange = { endDate = it },
                    label = { Text("结束日期 (yyyy-MM-dd)") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isExporting
                )

                // Export message
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

                // Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Export all logs button
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                isExporting = true
                                exportMessage = "正在分享所有日志..."

                                try {
                                    val success = com.seenot.app.utils.Logger.shareAllLogs(context)
                                    exportMessage = if (success) {
                                        "分享成功！"
                                    } else {
                                        "分享失败，请检查日志"
                                    }
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

                    // Export range button
                    Button(
                        onClick = {
                            scope.launch {
                                isExporting = true
                                exportMessage = "正在分享日志..."

                                try {
                                    val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
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

                // Close button
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("关闭")
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
