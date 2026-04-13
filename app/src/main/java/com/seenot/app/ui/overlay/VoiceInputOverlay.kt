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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.seenot.app.R
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
                        VoiceInputStatus.IDLE -> stringResource(R.string.voice_input_title)
                        VoiceInputStatus.RECORDING -> stringResource(R.string.recording)
                        VoiceInputStatus.PROCESSING -> stringResource(R.string.processing)
                        VoiceInputStatus.SHOWING_RESULT -> stringResource(R.string.state_transcribed)
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
                                Text(stringResource(R.string.continue_last_intent))
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            Text(
                                text = stringResource(R.string.common_or),
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
                                contentDescription = stringResource(R.string.start_recording),
                                tint = Color.White,
                                modifier = Modifier.size(40.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = stringResource(R.string.voice_tap_to_speak),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // Switch to text input
                        TextButton(onClick = { showTextInput = true }) {
                            Text(stringResource(R.string.voice_or_use_text_input))
                        }

                        // Skip button
                        TextButton(onClick = onSkip) {
                            Text(stringResource(R.string.skip), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                contentDescription = stringResource(R.string.stop_recording),
                                tint = Color.White,
                                modifier = Modifier.size(40.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = stringResource(R.string.voice_tap_to_stop),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Red
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // Cancel button
                        TextButton(onClick = onSkip) {
                            Text(stringResource(R.string.cancel))
                        }
                    }

                    VoiceInputStatus.PROCESSING -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(48.dp)
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = stringResource(R.string.understanding_intent),
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
                            Text(stringResource(R.string.confirm))
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Try again
                        OutlinedButton(
                            onClick = { },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.try_again))
                        }
                    }
                }

                // Text input mode
                if (showTextInput && state.status == VoiceInputStatus.IDLE) {
                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedTextField(
                        value = textInput,
                        onValueChange = { textInput = it },
                        label = { Text(stringResource(R.string.input_intent_title)) },
                        placeholder = { Text(stringResource(R.string.input_intent_hint)) },
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
                        Text(stringResource(R.string.confirm))
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

