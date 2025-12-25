package com.roderickqiu.seenot.components

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roderickqiu.seenot.R
import com.roderickqiu.seenot.data.MonitoringApp
import com.roderickqiu.seenot.ui.theme.YellowGrey40
import com.roderickqiu.seenot.ui.theme.YellowGrey80
import com.roderickqiu.seenot.utils.RuleFormatter

@Composable
fun MonitoringAppItem(
    app: MonitoringApp,
    onDeleteApp: (String) -> Unit,
    onEditApp: (MonitoringApp) -> Unit
) {
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    val isDarkTheme = isSystemInDarkTheme()
    var showMenu by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }

    // Unified color scheme
    val iconColor = if (app.isEnabled) {
        colorScheme.secondaryContainer
    } else {
        colorScheme.outline.copy(alpha = 0.6f) // Gray color for disabled state
    }

    val textColor = if (app.isEnabled) {
        colorScheme.onSecondary
    } else {
        colorScheme.onSurface.copy(alpha = 0.6f)
    }

    // Auto-generate icon (take first character of app name)
    val icon = app.name.take(1)

    // Auto-add status suffix
    val displayName = if (!app.isEnabled) {
        "${app.name} ${context.getString(R.string.status_disabled)}"
    } else {
        app.name
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // App icon
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(iconColor),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = icon,
                    color = textColor,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            // App info
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = displayName,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (app.isEnabled) colorScheme.onSurface else colorScheme.onSurface.copy(
                        alpha = 0.6f
                    )
                )

                Spacer(modifier = Modifier.height(4.dp))

                app.rules.forEach { rule ->
                    Text(
                        text = RuleFormatter.formatRuleForList(context, rule),
                        fontSize = 14.sp,
                        color = colorScheme.onSurface.copy(alpha = 0.7f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                    )
                }
            }

            // More options dropdown menu
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = context.getString(R.string.more_options),
                        tint = colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(context.getString(R.string.edit)) },
                        onClick = {
                            showMenu = false
                            showEditDialog = true
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = null
                            )
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(context.getString(R.string.delete)) },
                        onClick = {
                            showMenu = false
                            onDeleteApp(app.id)
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null
                            )
                        }
                    )
                }
            }
        }
    }

    // Unified Edit Dialog
    if (showEditDialog) {
        UnifiedEditDialog(
            app = app,
            onDismiss = { showEditDialog = false },
            onSaveApp = { updatedApp ->
                onEditApp(updatedApp)
                showEditDialog = false
            }
        )
    }
}