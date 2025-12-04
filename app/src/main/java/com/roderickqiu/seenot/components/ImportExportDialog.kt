package com.roderickqiu.seenot.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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

@Composable
fun ImportExportDialog(
    onDismiss: () -> Unit,
    onExport: () -> Unit,
    onImport: (String) -> Unit
) {
    val context = LocalContext.current
    var showImportDialog by remember { mutableStateOf(false) }
    var showConfirmDialog by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }
    var pendingImportText by remember { mutableStateOf("") }

    if (showImportDialog) {
        ImportTextDialog(
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
                text = context.getString(R.string.import_export_rules),
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = context.getString(R.string.import_rules_description),
                    style = MaterialTheme.typography.bodyMedium
                )

                Button(
                    onClick = {
                        showImportDialog = true
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(context.getString(R.string.import_rules))
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = context.getString(R.string.export_rules_description),
                    style = MaterialTheme.typography.bodyMedium
                )

                OutlinedButton(
                    onClick = {
                        onExport()
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(context.getString(R.string.export_rules))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(context.getString(R.string.cancel))
            }
        }
    )
}

@Composable
private fun ImportTextDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = context.getString(R.string.import_rules),
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = context.getString(R.string.paste_json_here),
                    style = MaterialTheme.typography.bodyMedium
                )

                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 6,
                    maxLines = 10,
                    placeholder = { Text("{\n  \"name\": \"示例应用\",\n  \"rules\": [...]\n}") }
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
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = context.getString(R.string.confirm_import),
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Text(
                text = context.getString(R.string.import_warning),
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
