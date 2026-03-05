package com.seenot.app.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.seenot.app.ai.parser.AppInfo
import com.seenot.app.ai.parser.ChatMessage
import com.seenot.app.ai.parser.ExecutionResult
import com.seenot.app.ai.parser.IntentParser
import com.seenot.app.ai.voice.VoiceInputManager
import com.seenot.app.domain.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * AI Rule Assistant - Agent-powered natural language interface
 *
 * Uses ReAct pattern: LLM decides which actions to take to accomplish user goals.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AIRuleAssistantDialog(
    context: Context,
    sessionManager: SessionManager,
    monitoredApps: List<AppInfo>,
    onDismiss: () -> Unit
) {
    var inputText by remember { mutableStateOf("") }
    var messages by remember { mutableStateOf(listOf<ChatMessage>()) }
    var isProcessing by remember { mutableStateOf(false) }
    var selectedApp by remember { mutableStateOf<AppInfo?>(null) }

    // Voice input state
    var isRecording by remember { mutableStateOf(false) }
    var voiceInputManager by remember { mutableStateOf<VoiceInputManager?>(null) }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            isRecording = true
        }
    }

    // Initialize and show welcome message
    LaunchedEffect(Unit) {
        voiceInputManager = VoiceInputManager(context)
        messages = listOf(
            ChatMessage(
                role = "assistant",
                content = """你好！我是 SeeNot AI 助手 🤖

我可以帮你管理应用规则，比如：
• "微信禁止看短视频"
• "把抖音的时间限制改成10分钟"
• "查看微信有哪些规则"
• "删除微博的娱乐规则"

直接告诉我你想做什么，我会自动执行。"""
            )
        )
    }

    // Voice input control
    LaunchedEffect(isRecording) {
        voiceInputManager?.let { manager ->
            if (isRecording) manager.startRecording() else manager.stopRecording()
        }
    }

    // Listen for voice results
    LaunchedEffect(voiceInputManager, messages) {
        voiceInputManager?.recognizedText?.collect { text ->
            if (!text.isNullOrBlank() && !isRecording) {
                // Execute request
                messages = messages + ChatMessage(role = "user", content = text)
                isProcessing = true

                CoroutineScope(Dispatchers.Main).launch {
                    try {
                        val parser = IntentParser(context)
                        val result = parser.planAndExecute(
                            userMessage = text,
                            conversationHistory = messages,
                            selectedApp = selectedApp,
                            availableApps = monitoredApps,
                            sessionManager = sessionManager
                        )

                        when (result) {
                            is ExecutionResult.Success -> {
                                messages = messages + ChatMessage(role = "assistant", content = result.response)
                            }
                            is ExecutionResult.Error -> {
                                messages = messages + ChatMessage(role = "assistant", content = "出错了: ${result.message}")
                            }
                        }
                    } catch (e: Exception) {
                        messages = messages + ChatMessage(role = "assistant", content = "抱歉，出错了: ${e.message}")
                    }
                    isProcessing = false
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            voiceInputManager?.cancelRecording()
            voiceInputManager?.release()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Top bar
                TopAppBar(
                    title = { Text("AI 助手") },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "关闭")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )

                // Selected app indicator
                selectedApp?.let { app ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.PhoneAndroid, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = "当前: ${app.name}", style = MaterialTheme.typography.bodyMedium)
                            Spacer(modifier = Modifier.weight(1f))
                            TextButton(onClick = { selectedApp = null }) { Text("清除") }
                        }
                    }
                }

                // Chat messages
                val listState = rememberLazyListState()
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(messages) { message ->
                        ChatMessageItem(message = message)
                    }

                    if (isProcessing) {
                        item {
                            Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("思考中...", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }

                // Input area
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Voice button
                    IconButton(
                        onClick = {
                            if (isRecording) {
                                isRecording = false
                            } else {
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                    isRecording = true
                                } else {
                                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }
                        }
                    ) {
                        Icon(
                            if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                            contentDescription = "语音输入",
                            tint = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                    }

                    // Text input
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("告诉我你想做什么...") },
                        enabled = !isProcessing,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                if (inputText.isNotBlank() && !isProcessing) {
                                    val text = inputText
                                    messages = messages + ChatMessage(role = "user", content = text)
                                    isProcessing = true
                                    inputText = ""

                                    CoroutineScope(Dispatchers.Main).launch {
                                        try {
                                            val parser = IntentParser(context)
                                            val result = parser.planAndExecute(
                                                userMessage = text,
                                                conversationHistory = messages,
                                                selectedApp = selectedApp,
                                                availableApps = monitoredApps,
                                                sessionManager = sessionManager
                                            )

                                            when (result) {
                                                is ExecutionResult.Success -> {
                                                    messages = messages + ChatMessage(role = "assistant", content = result.response)
                                                }
                                                is ExecutionResult.Error -> {
                                                    messages = messages + ChatMessage(role = "assistant", content = "出错了: ${result.message}")
                                                }
                                            }
                                        } catch (e: Exception) {
                                            messages = messages + ChatMessage(role = "assistant", content = "抱歉，出错了: ${e.message}")
                                        }
                                        isProcessing = false
                                    }
                                }
                            }
                        ),
                        singleLine = true
                    )

                    // Send button
                    IconButton(
                        onClick = {
                            if (inputText.isNotBlank() && !isProcessing) {
                                val text = inputText
                                messages = messages + ChatMessage(role = "user", content = text)
                                isProcessing = true
                                inputText = ""

                                CoroutineScope(Dispatchers.Main).launch {
                                    try {
                                        val parser = IntentParser(context)
                                        val result = parser.planAndExecute(
                                            userMessage = text,
                                            conversationHistory = messages,
                                            selectedApp = selectedApp,
                                            availableApps = monitoredApps,
                                            sessionManager = sessionManager
                                        )

                                        when (result) {
                                            is ExecutionResult.Success -> {
                                                messages = messages + ChatMessage(role = "assistant", content = result.response)
                                            }
                                            is ExecutionResult.Error -> {
                                                messages = messages + ChatMessage(role = "assistant", content = "出错了: ${result.message}")
                                            }
                                        }
                                    } catch (e: Exception) {
                                        messages = messages + ChatMessage(role = "assistant", content = "抱歉，出错了: ${e.message}")
                                    }
                                    isProcessing = false
                                }
                            }
                        }
                    ) {
                        @Suppress("DEPRECATION")
                        Icon(Icons.Default.Send, contentDescription = "发送")
                    }
                }
            }
        }
    }
}

@Composable
fun ChatMessageItem(message: ChatMessage) {
    val isUser = message.role == "user"
    val backgroundColor = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val alignment = if (isUser) Alignment.End else Alignment.Start

    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = alignment) {
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(backgroundColor)
                .padding(12.dp)
        ) {
            Text(message.content, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
