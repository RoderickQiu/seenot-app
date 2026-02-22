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
import androidx.compose.runtime.LaunchedEffect
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
import com.roderickqiu.seenot.data.AppDataStore
import com.roderickqiu.seenot.data.MonitoringApp
import com.roderickqiu.seenot.data.Rule
import com.roderickqiu.seenot.data.RuleCondition
import com.roderickqiu.seenot.data.RuleAction
import com.roderickqiu.seenot.data.ConditionType
import com.roderickqiu.seenot.data.ActionType
import com.roderickqiu.seenot.utils.GenericUtils
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun UnifiedEditDialog(
    app: MonitoringApp,
    onDismiss: () -> Unit,
    onSaveApp: (MonitoringApp) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val appDataStore = remember { AppDataStore(context) }
    var isEnabled by remember { mutableStateOf(app.isEnabled) }
    val rules = remember { mutableStateListOf<Rule>(*app.rules.toTypedArray()) }
    var showAddRuleDialog by remember { mutableStateOf(false) }
    var editingRuleIndex by remember { mutableStateOf<Int?>(null) }
    /** When non-null, show "disable for how long?" dialog for this rule */
    var showDisableDurationForRule by remember { mutableStateOf<Rule?>(null) }
    /** When true, show same dialog for disabling the whole app */
    var showDisableDurationForApp by remember { mutableStateOf(false) }
    /** ruleId -> reopenAtMillis (from AppDataStore); Long.MAX_VALUE = indefinite */
    var ruleReopenAtMap by remember { mutableStateOf(appDataStore.getAllRuleReopenAt()) }
    /** App-level reopen-at (when whole app is paused); null = not set */
    var appReopenAt by remember { mutableStateOf<Long?>(appDataStore.getAppReopenAt(app.id)) }

    LaunchedEffect(Unit) {
        ruleReopenAtMap = appDataStore.getAllRuleReopenAt()
        appReopenAt = appDataStore.getAppReopenAt(app.id)
        val now = System.currentTimeMillis()
        appReopenAt?.let { until ->
            if (until != Long.MAX_VALUE && now >= until) {
                appDataStore.clearAppReopenAt(app.id)
                appReopenAt = null
                isEnabled = true
            }
        }
    }

    fun isRuleEnabled(rule: Rule): Boolean {
        val reopenAt = ruleReopenAtMap[rule.id] ?: return true
        if (reopenAt == Long.MAX_VALUE) return false
        return System.currentTimeMillis() >= reopenAt
    }

    fun onRuleToggleEnabled(rule: Rule, newEnabled: Boolean) {
        if (newEnabled) {
            appDataStore.clearRuleReopenAt(rule.id)
            ruleReopenAtMap = ruleReopenAtMap - rule.id
        } else {
            showDisableDurationForRule = rule
        }
    }

    fun applyDisableDuration(rule: Rule, untilMillis: Long) {
        appDataStore.setRuleReopenAt(rule.id, untilMillis)
        ruleReopenAtMap = ruleReopenAtMap + (rule.id to untilMillis)
        showDisableDurationForRule = null
    }

    fun formatReopenAtText(rule: Rule): String? {
        val until = ruleReopenAtMap[rule.id] ?: return null
        if (System.currentTimeMillis() >= until && until != Long.MAX_VALUE) return null
        if (until == Long.MAX_VALUE) return context.getString(R.string.disable_rule_reopen_indefinite)
        val locale = com.roderickqiu.seenot.utils.LanguageManager.getLocale(
            com.roderickqiu.seenot.utils.LanguageManager.getSavedLanguage(context)
        )
        val dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale)
        val timeStr = dateFormat.format(Date(until))
        return context.getString(R.string.disable_rule_reopen_at, timeStr)
    }

    fun applyDisableDurationForApp(untilMillis: Long) {
        appDataStore.setAppReopenAt(app.id, untilMillis)
        appReopenAt = untilMillis
        isEnabled = false
        showDisableDurationForApp = false
    }

    fun formatAppReopenAtText(): String? {
        val until = appReopenAt ?: return null
        if (System.currentTimeMillis() >= until && until != Long.MAX_VALUE) return null
        if (until == Long.MAX_VALUE) return context.getString(R.string.disable_rule_reopen_indefinite)
        val locale = com.roderickqiu.seenot.utils.LanguageManager.getLocale(
            com.roderickqiu.seenot.utils.LanguageManager.getSavedLanguage(context)
        )
        val dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale)
        val timeStr = dateFormat.format(Date(until))
        return context.getString(R.string.disable_rule_reopen_at, timeStr)
    }
    
    // Find package name from app name
    val packageName = remember {
        GenericUtils.getInstalledApps(context)
            .find { it.appName == app.name }
            ?.packageName
    }
    
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
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
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
                                onCheckedChange = { newEnabled ->
                                    if (newEnabled) {
                                        appDataStore.clearAppReopenAt(app.id)
                                        appReopenAt = null
                                        isEnabled = true
                                    } else {
                                        showDisableDurationForApp = true
                                    }
                                }
                            )
                        }
                        formatAppReopenAtText()?.let { reopenText ->
                            Text(
                                text = reopenText,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
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
                                isEnabled = isRuleEnabled(rule),
                                onToggleEnabled = { newEnabled -> onRuleToggleEnabled(rule, newEnabled) },
                                onEditRule = { 
                                    editingRuleIndex = index
                                },
                                onDeleteRule = {
                                    rules.removeAt(index)
                                },
                                onDuplicateRule = {
                                    val newRule = rule.copy(id = java.util.UUID.randomUUID().toString())
                                    rules.add(index + 1, newRule)
                                },
                                reopenAtText = formatReopenAtText(rule)
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
            },
            appName = app.name,
            packageName = packageName
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
            },
            appName = app.name,
            packageName = packageName
        )
    }

    // "Disable for how long?" dialog (reused for both rule and whole-app disable)
    val showDurationDialog = showDisableDurationForRule != null || showDisableDurationForApp
    if (showDurationDialog) {
        val ruleToDisable = showDisableDurationForRule
        AlertDialog(
            onDismissRequest = {
                showDisableDurationForRule = null
                showDisableDurationForApp = false
            },
            confirmButton = { },
            dismissButton = {
                TextButton(onClick = {
                    showDisableDurationForRule = null
                    showDisableDurationForApp = false
                }) {
                    Text(context.getString(R.string.disable_rule_duration_cancel))
                }
            },
            title = { Text(context.getString(R.string.disable_rule_duration_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = context.getString(R.string.disable_rule_duration_message),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    TextButton(
                        onClick = {
                            val until = System.currentTimeMillis() + 30 * 60 * 1000L
                            if (ruleToDisable != null) applyDisableDuration(ruleToDisable, until)
                            else applyDisableDurationForApp(until)
                        }
                    ) {
                        Text(context.getString(R.string.disable_rule_duration_30min))
                    }
                    TextButton(
                        onClick = {
                            val until = System.currentTimeMillis() + 60 * 60 * 1000L
                            if (ruleToDisable != null) applyDisableDuration(ruleToDisable, until)
                            else applyDisableDurationForApp(until)
                        }
                    ) {
                        Text(context.getString(R.string.disable_rule_duration_1h))
                    }
                    TextButton(
                        onClick = {
                            val until = System.currentTimeMillis() + 2 * 60 * 60 * 1000L
                            if (ruleToDisable != null) applyDisableDuration(ruleToDisable, until)
                            else applyDisableDurationForApp(until)
                        }
                    ) {
                        Text(context.getString(R.string.disable_rule_duration_2h))
                    }
                    TextButton(
                        onClick = {
                            val cal = Calendar.getInstance()
                            cal.add(Calendar.DAY_OF_MONTH, 1)
                            cal.set(Calendar.HOUR_OF_DAY, 0)
                            cal.set(Calendar.MINUTE, 0)
                            cal.set(Calendar.SECOND, 0)
                            cal.set(Calendar.MILLISECOND, 0)
                            val until = cal.timeInMillis
                            if (ruleToDisable != null) applyDisableDuration(ruleToDisable, until)
                            else applyDisableDurationForApp(until)
                        }
                    ) {
                        Text(context.getString(R.string.disable_rule_duration_rest_of_today))
                    }
                    TextButton(
                        onClick = {
                            if (ruleToDisable != null) applyDisableDuration(ruleToDisable, Long.MAX_VALUE)
                            else applyDisableDurationForApp(Long.MAX_VALUE)
                        }
                    ) {
                        Text(context.getString(R.string.disable_rule_duration_indefinite))
                    }
                }
            }
        )
    }
}
