package com.seenot.app.ui.overlay

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.seenot.app.R
import com.seenot.app.data.model.ConstraintType
import com.seenot.app.data.model.TimeScope
import kotlin.math.roundToInt

/**
 * SessionStatusHUD - The floating window that displays session status
 *
 * Two states:
 * - Minimal: small floating circle with time or status icon
 * - Expanded: full rule list, countdown timer, color coding, voice button, pause/end
 */
@Composable
fun SessionStatusHud(
    state: SessionHudState,
    onToggleExpand: () -> Unit,
    onPauseResume: () -> Unit,
    onEndSession: () -> Unit,
    onVoiceInput: () -> Unit,
    onConstraintToggle: (String) -> Unit,
    onConstraintModify: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }

    // Animation for violation warning
    val scale by animateFloatAsState(
        targetValue = if (state.isViolating) 1.2f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "violation_scale"
    )

    val backgroundColor by animateColorAsState(
        targetValue = if (state.isViolating) {
            Color(0xFFFFEBEE) // Light red when violating
        } else {
            Color(0xFFFFFFFF) // White normally
        },
        label = "background_color"
    )

    val timeColor = calculateTimeColor(
        state.totalTimeRemainingMs,
        state.totalTimeLimitMs
    )

    if (state.isExpanded) {
        // Expanded state - full rule list
        ExpandedHud(
            state = state,
            timeColor = timeColor,
            backgroundColor = backgroundColor,
            onToggleExpand = onToggleExpand,
            onPauseResume = onPauseResume,
            onEndSession = onEndSession,
            onVoiceInput = onVoiceInput,
            onConstraintToggle = onConstraintToggle,
            onConstraintModify = onConstraintModify,
            modifier = modifier
                .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        offsetX += dragAmount.x
                        offsetY += dragAmount.y
                    }
                }
        )
    } else {
        // Minimal state - floating circle
        MinimalHud(
            state = state,
            timeColor = timeColor,
            backgroundColor = backgroundColor,
            scale = scale,
            onClick = onToggleExpand,
            modifier = modifier
                .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        offsetX += dragAmount.x
                        offsetY += dragAmount.y
                    }
                }
        )
    }
}

/**
 * Minimal HUD - small floating circle
 */
@Composable
private fun MinimalHud(
    state: SessionHudState,
    timeColor: Color,
    backgroundColor: Color,
    scale: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(56.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(backgroundColor)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        // Show time if available, otherwise show icon
        if (state.totalTimeRemainingMs != null) {
            Text(
                text = formatTimeRemaining(state.totalTimeRemainingMs),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = timeColor
            )
        } else {
            // Show constraint status icon
            val hasViolation = state.constraints.any { it.isViolating }
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(
                        color = if (hasViolation) Color(0xFFF44336) else Color(0xFF4CAF50),
                        shape = CircleShape
                    )
            )
        }
    }
}

/**
 * Expanded HUD - full rule list with controls
 */
@Composable
private fun ExpandedHud(
    state: SessionHudState,
    timeColor: Color,
    backgroundColor: Color,
    onToggleExpand: () -> Unit,
    onPauseResume: () -> Unit,
    onEndSession: () -> Unit,
    onVoiceInput: () -> Unit,
    onConstraintToggle: (String) -> Unit,
    onConstraintModify: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .width(320.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = backgroundColor
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 8.dp
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Header with app name and close button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = state.appDisplayName,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Session Active",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                }

                Row {
                    // Voice input button
                    IconButton(
                        onClick = onVoiceInput,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "Add intent",
                            tint = Color(0xFF2196F3)
                        )
                    }

                    // Close/minimize button
                    IconButton(
                        onClick = onToggleExpand,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Minimize",
                            tint = Color.Gray
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Countdown timer (if time limit exists)
            if (state.totalTimeRemainingMs != null && state.totalTimeLimitMs != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = timeColor.copy(alpha = 0.1f),
                            shape = RoundedCornerShape(8.dp)
                        )
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = formatTimeRemaining(state.totalTimeRemainingMs),
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold,
                        color = timeColor
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
            }

            // Constraint list
            if (state.constraints.isNotEmpty()) {
                Text(
                    text = "Constraints",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.Gray
                )

                Spacer(modifier = Modifier.height(8.dp))

                state.constraints.forEach { constraint ->
                    ConstraintItem(
                        constraint = constraint,
                        onToggle = { onConstraintToggle(constraint.id) },
                        onModify = { onConstraintModify(constraint.id) }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            // Violation message
            if (state.isViolating && state.violationMessage != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = Color(0xFFF44336).copy(alpha = 0.1f),
                            shape = RoundedCornerShape(8.dp)
                        )
                        .padding(12.dp)
                ) {
                    Text(
                        text = state.violationMessage,
                        fontSize = 14.sp,
                        color = Color(0xFFF44336)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                // Pause/Resume button
                IconButton(
                    onClick = onPauseResume,
                    modifier = Modifier
                        .size(48.dp)
                        .background(
                            color = Color(0xFFEEEEEE),
                            shape = CircleShape
                        )
                ) {
                    Icon(
                        imageVector = if (state.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        contentDescription = if (state.isPaused) "Resume" else "Pause"
                    )
                }

                // End session button
                IconButton(
                    onClick = onEndSession,
                    modifier = Modifier
                        .size(48.dp)
                        .background(
                            color = Color(0xFFF44336).copy(alpha = 0.1f),
                            shape = CircleShape
                        )
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "End session",
                        tint = Color(0xFFF44336)
                    )
                }
            }
        }
    }
}

/**
 * Single constraint item in the expanded HUD
 */
@Composable
private fun ConstraintItem(
    constraint: ConstraintUiModel,
    onToggle: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onModify: () -> Unit
) {
    val typeColor = getConstraintTypeColor(constraint.type, constraint.isViolating)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .background(
                color = if (constraint.isViolating) {
                    Color(0xFFF44336).copy(alpha = 0.05f)
                } else if (!constraint.isActive) {
                    Color.Gray.copy(alpha = 0.1f)
                } else {
                    Color.Transparent
                },
                shape = RoundedCornerShape(8.dp)
            )
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Type icon
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(
                    color = typeColor.copy(alpha = 0.1f),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = getConstraintTypeIcon(constraint.type),
                color = typeColor,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Description
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = constraint.description,
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = if (constraint.isActive) Color.Black else Color.Gray
            )

            if (constraint.timeRemainingMs != null && constraint.timeLimitMs != null) {
                val scopeText = when (constraint.timeScope) {
                    TimeScope.SESSION -> stringResource(R.string.time_scope_session_short)
                    TimeScope.PER_CONTENT -> stringResource(R.string.time_scope_per_content_short)
                    TimeScope.CONTINUOUS -> stringResource(R.string.time_scope_continuous)
                    null -> ""
                }
                Text(
                    text = if (scopeText.isNotEmpty()) {
                        "$scopeText ${formatTimeRemaining(constraint.timeRemainingMs)}"
                    } else {
                        formatTimeRemaining(constraint.timeRemainingMs)
                    },
                    fontSize = 12.sp,
                    color = calculateTimeColor(constraint.timeRemainingMs, constraint.timeLimitMs)
                )
            }
        }

        // Active indicator
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(
                    color = if (constraint.isActive) Color(0xFF4CAF50) else Color.Gray,
                    shape = CircleShape
                )
        )
    }
}
