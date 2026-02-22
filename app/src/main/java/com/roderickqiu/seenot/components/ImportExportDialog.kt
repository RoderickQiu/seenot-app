package com.roderickqiu.seenot.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.roderickqiu.seenot.R

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ImportExportDialog(
    onDismiss: () -> Unit,
    onExport: () -> Unit,
    onExportLogs: () -> Unit,
    onExportInsights: () -> Unit,
    onImport: (String) -> Unit,
    onImportInsightsFromFile: () -> Unit
) {
    val context = LocalContext.current
    var showImportDialog by remember { mutableStateOf(false) }
    var showConfirmDialog by remember { mutableStateOf(false) }
    var pendingImportText by remember { mutableStateOf("") }

    if (showImportDialog) {
        ImportTextDialog(
            title = context.getString(R.string.import_rules),
            description = context.getString(R.string.paste_rules_json_here),
            placeholder = "{\n  \"name\": \"Example App\",\n  \"rules\": [...]\n}",
            onDismiss = { showImportDialog = false },
            onConfirm = { text ->
                pendingImportText = text
                showImportDialog = false
                showConfirmDialog = true
            }
        )
    }

    if (showConfirmDialog) {
        ImportConfirmDialog(
            title = context.getString(R.string.confirm_import_rules),
            message = context.getString(R.string.import_rules_warning),
            onDismiss = {
                showConfirmDialog = false
                pendingImportText = ""
            },
            onConfirm = {
                onImport(pendingImportText)
                showConfirmDialog = false
                pendingImportText = ""
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = context.getString(R.string.import_export_title),
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Rules Section
                SectionCard(
                    icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                    title = context.getString(R.string.rules),
                    description = context.getString(R.string.rules_export_import_desc)
                ) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { showImportDialog = true },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(context.getString(R.string.import_action))
                        }

                        OutlinedButton(onClick = onExport) {
                            Icon(Icons.Default.Upload, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(context.getString(R.string.export_action))
                        }
                    }
                }

                HorizontalDivider()

                // Activity Insights Section
                SectionCard(
                    icon = { Icon(Icons.Default.DateRange, contentDescription = null) },
                    title = context.getString(R.string.activity_insights),
                    description = context.getString(R.string.insights_export_import_desc)
                ) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                onDismiss()
                                onImportInsightsFromFile()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondary
                            )
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(context.getString(R.string.import_from_file))
                        }

                        OutlinedButton(onClick = onExportInsights) {
                            Icon(Icons.Default.Upload, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(context.getString(R.string.export_to_file))
                        }
                    }
                }

                HorizontalDivider()

                // Logs Section
                SectionCard(
                    icon = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null) },
                    title = context.getString(R.string.logs),
                    description = context.getString(R.string.logs_export_desc)
                ) {
                    OutlinedButton(
                        onClick = onExportLogs,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Upload, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(context.getString(R.string.export_logs))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(context.getString(R.string.close))
            }
        }
    )
}

@Composable
private fun SectionCard(
    icon: @Composable () -> Unit,
    title: String,
    description: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                icon()
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            content()
        }
    }
}

@Composable
private fun ImportTextDialog(
    title: String,
    description: String,
    placeholder: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium
                )

                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 6,
                    maxLines = 10,
                    placeholder = { Text(placeholder) }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (text.isNotBlank()) {
                        onConfirm(text)
                    }
                },
                enabled = text.isNotBlank()
            ) {
                Text(context.getString(R.string.confirm_import))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(context.getString(R.string.cancel))
            }
        }
    )
}

@Composable
private fun ImportConfirmDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(context.getString(R.string.confirm_import))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(context.getString(R.string.cancel))
            }
        }
    )
}
