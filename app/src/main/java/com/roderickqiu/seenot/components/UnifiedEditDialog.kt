package com.roderickqiu.seenot.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roderickqiu.seenot.components.rule.RuleItem
import com.roderickqiu.seenot.components.rule.RuleDialog
import com.roderickqiu.seenot.R
import com.roderickqiu.seenot.data.MonitoringApp
import com.roderickqiu.seenot.data.Rule
import com.roderickqiu.seenot.data.RuleCondition
import com.roderickqiu.seenot.data.RuleAction
import com.roderickqiu.seenot.data.ConditionType
import com.roderickqiu.seenot.data.ActionType

@Composable
fun UnifiedEditDialog(
    app: MonitoringApp,
    onDismiss: () -> Unit,
    onSaveApp: (MonitoringApp) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var isEnabled by remember { mutableStateOf(app.isEnabled) }
    var askOnEnter by remember { mutableStateOf(app.askOnEnter) }
    val rules = remember { mutableStateListOf<Rule>(*app.rules.toTypedArray()) }
    var showAddRuleDialog by remember { mutableStateOf(false) }
    var editingRuleIndex by remember { mutableStateOf<Int?>(null) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { 
            Text(
                text = context.getString(R.string.edit_dialog_title, app.name),
                fontSize = 18.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(400.dp)
            ) {
                // App Settings Section
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Enable/Disable Switch
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = context.getString(R.string.enabled_status_label),
                            fontSize = 16.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                        )
                        Switch(
                            checked = isEnabled,
                            onCheckedChange = { isEnabled = it }
                        )
                    }

                    // Ask on Enter Switch
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = context.getString(R.string.ask_on_enter_label),
                                fontSize = 16.sp,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                            )
                            Text(
                                text = context.getString(R.string.ask_on_enter_desc),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
                        Switch(
                            checked = askOnEnter,
                            onCheckedChange = { askOnEnter = it }
                        )
                    }
                    
                    // App Info
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = context.getString(R.string.app_name_label_colon),
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                        Text(
                            text = app.name,
                            fontSize = 14.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                        )
                    }
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = context.getString(R.string.rule_count),
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                        Text(
                            text = "${rules.size}",
                            fontSize = 14.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // Divider
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // Rules Section
                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = context.getString(R.string.rule_management),
                        fontSize = 16.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        items(rules.size) { index ->
                            val rule = rules[index]
                            RuleItem(
                                rule = rule,
                                isEnabled = true,
                                onToggleEnabled = { _ ->
                                    // For now, we don't support disabling individual rules
                                },
                                onEditRule = { 
                                    editingRuleIndex = index
                                },
                                onDeleteRule = {
                                    rules.removeAt(index)
                                },
                                onDuplicateRule = {
                                    val newRule = rule.copy(id = java.util.UUID.randomUUID().toString())
                                    rules.add(index + 1, newRule)
                                }
                            )
                            if (index < rules.size - 1) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                )
                            }
                        }
                    }
                    
                    // Add Rule Button
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showAddRuleDialog = true }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = context.getString(R.string.add_rule_button),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            fontSize = 16.sp
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                    onClick = {
                        val updatedApp = app.copy(
                            isEnabled = isEnabled,
                            askOnEnter = askOnEnter,
                            rules = rules.toList()
                        )
                        onSaveApp(updatedApp)
                    }
            ) {
                Text(context.getString(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(context.getString(R.string.cancel))
            }
        }
    )
    
    // Add Rule Dialog
    if (showAddRuleDialog) {
        RuleDialog(
            rule = null,
            onDismiss = { showAddRuleDialog = false },
            onSaveRule = { newRule ->
                rules.add(newRule)
                showAddRuleDialog = false
            }
        )
    }
    
    // Edit Rule Dialog
    editingRuleIndex?.let { index ->
        RuleDialog(
            rule = rules[index],
            onDismiss = { editingRuleIndex = null },
            onSaveRule = { updatedRule ->
                rules[index] = updatedRule
                editingRuleIndex = null
            }
        )
    }
}
