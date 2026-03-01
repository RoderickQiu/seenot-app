package com.seenot.app.ui.overlay

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import kotlinx.coroutines.delay

/**
 * Voice Input Overlay - The floating window for declaring intent
 *
 * Features:
 * - Microphone button for voice input
 * - Manual text input as fallback
 * - "Continue last intent" shortcut
 * - Skip button
 * - Recording animation
 */
@Composable
fun VoiceInputOverlay(
    state: VoiceInputState,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onSubmitText: (String) -> Unit,
    onContinueLastIntent: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier
) {
    var textInput by remember { mutableStateOf("") }
    var showTextInput by remember { mutableStateOf(false) }

    // Recording animation
    val infiniteTransition = rememberInfiniteTransition(label = "recording")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(500),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header
                Text(
                    text = when (state.status) {
                        VoiceInputStatus.IDLE -> "声明你的意图"
                        VoiceInputStatus.RECORDING -> "正在录音..."
                        VoiceInputStatus.PROCESSING -> "正在解析..."
                        VoiceInputStatus.SHOWING_RESULT -> "识别结果"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(8.dp))

                // App name
                Text(
                    text = state.appDisplayName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(20.dp))

                when (state.status) {
                    VoiceInputStatus.IDLE -> {
                        // Continue last intent button
                        if (state.hasLastIntent) {
                            OutlinedButton(
                                onClick = onContinueLastIntent,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("继续使用上次意图")
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            Text(
                                text = "或",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(16.dp))
                        }

                        // Voice input button
                        Box(
                            modifier = Modifier
                                .size(80.dp)
                                .scale(scale)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                                .clickable { onStartRecording() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Mic,
                                contentDescription = "开始录音",
                                tint = Color.White,
                                modifier = Modifier.size(40.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "点击说话",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // Switch to text input
                        TextButton(onClick = { showTextInput = true }) {
                            Text("或使用文本输入")
                        }

                        // Skip button
                        TextButton(onClick = onSkip) {
                            Text("跳过", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    VoiceInputStatus.RECORDING -> {
                        // Recording indicator
                        Box(
                            modifier = Modifier
                                .size(80.dp)
                                .scale(scale)
                                .clip(CircleShape)
                                .background(Color.Red.copy(alpha = 0.8f))
                                .clickable { onStopRecording() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Stop,
                                contentDescription = "停止录音",
                                tint = Color.White,
                                modifier = Modifier.size(40.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "点击停止",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Red
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // Cancel button
                        TextButton(onClick = onSkip) {
                            Text("取消")
                        }
                    }

                    VoiceInputStatus.PROCESSING -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(48.dp)
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = "正在理解你的意图...",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    VoiceInputStatus.SHOWING_RESULT -> {
                        // Show recognized text
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        ) {
                            Text(
                                text = state.recognizedText ?: "",
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Confirm button
                        Button(
                            onClick = { onSubmitText(state.recognizedText ?: "") },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("确认")
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Try again
                        OutlinedButton(
                            onClick = { },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("再说一次")
                        }
                    }
                }

                // Text input mode
                if (showTextInput && state.status == VoiceInputStatus.IDLE) {
                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedTextField(
                        value = textInput,
                        onValueChange = { textInput = it },
                        label = { Text("输入你的意图") },
                        placeholder = { Text("例如：只看工作消息，10分钟") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = {
                            if (textInput.isNotBlank()) {
                                onSubmitText(textInput)
                                showTextInput = false
                                textInput = ""
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = textInput.isNotBlank()
                    ) {
                        Text("确认")
                    }
                }
            }
        }
    }
}

/**
 * Voice input state
 */
data class VoiceInputState(
    val appPackageName: String = "",
    val appDisplayName: String = "",
    val status: VoiceInputStatus = VoiceInputStatus.IDLE,
    val recognizedText: String? = null,
    val hasLastIntent: Boolean = false,
    val error: String? = null
)

/**
 * Voice input status
 */
enum class VoiceInputStatus {
    IDLE,
    RECORDING,
    PROCESSING,
    SHOWING_RESULT
}

/**
 * Preview provider for VoiceInputOverlay
 */
@androidx.compose.ui.tooling.preview.Preview
@Composable
fun VoiceInputOverlayPreview() {
    MaterialTheme {
        VoiceInputOverlay(
            state = VoiceInputState(
                appDisplayName = "微信",
                hasLastIntent = true
            ),
            onStartRecording = {},
            onStopRecording = {},
            onSubmitText = {},
            onContinueLastIntent = {},
            onSkip = {}
        )
    }
}
