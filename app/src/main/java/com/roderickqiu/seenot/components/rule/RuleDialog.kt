package com.roderickqiu.seenot.components.rule

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roderickqiu.seenot.R
import com.roderickqiu.seenot.data.ActionType
import com.roderickqiu.seenot.data.ConditionType
import com.roderickqiu.seenot.data.Rule
import com.roderickqiu.seenot.data.RuleAction
import com.roderickqiu.seenot.data.RuleCondition
import com.roderickqiu.seenot.data.TimeConstraint
import com.roderickqiu.seenot.utils.RuleFormatter

/**
 * Unified dialog for adding or editing a rule.
 * If rule is null, it's in add mode; otherwise it's in edit mode.
 */
@Composable
fun RuleDialog(
    rule: Rule? = null,
    onDismiss: () -> Unit,
    onSaveRule: (Rule) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val isEditMode = rule != null
    
    var selectedTab by remember { mutableStateOf(0) }
    var selectedConditionType by remember { 
        mutableStateOf(rule?.condition?.type ?: ConditionType.ON_ENTER) 
    }
    var selectedActionType by remember { 
        mutableStateOf(rule?.action?.type ?: ActionType.ASK) 
    }
    var timeInterval by remember { 
        mutableStateOf(rule?.condition?.timeInterval ?: 3) 
    }
    var conditionParameter by remember { 
        mutableStateOf(rule?.condition?.parameter ?: "") 
    }
    var actionParameter by remember { 
        mutableStateOf(rule?.action?.parameter ?: "") 
    }
    var showConditionParameterDialog by remember { mutableStateOf(false) }
    var showActionParameterDialog by remember { mutableStateOf(false) }
    var showTimeIntervalDialog by remember { mutableStateOf(false) }
    var enableTimeConstraint by remember { mutableStateOf(rule?.timeConstraint != null) }
    var timeConstraintType by remember { mutableStateOf(rule?.timeConstraint) }
    var continuousMinutes by remember { 
        mutableStateOf(
            (rule?.timeConstraint as? TimeConstraint.Continuous)?.minutes ?: 3.0
        ) 
    }
    var dailyTotalMinutes by remember { 
        mutableStateOf(
            (rule?.timeConstraint as? TimeConstraint.DailyTotal)?.minutes ?: 30.0
        ) 
    }
    var recentHours by remember { 
        mutableStateOf(
            (rule?.timeConstraint as? TimeConstraint.RecentTotal)?.hours ?: 24
        ) 
    }
    var recentMinutes by remember { 
        mutableStateOf(
            (rule?.timeConstraint as? TimeConstraint.RecentTotal)?.minutes ?: 60.0
        ) 
    }
    var showContinuousMinutesDialog by remember { mutableStateOf(false) }
    var showDailyTotalMinutesDialog by remember { mutableStateOf(false) }
    var showRecentHoursDialog by remember { mutableStateOf(false) }
    var showRecentMinutesDialog by remember { mutableStateOf(false) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { 
            Text(
                if (isEditMode) {
                    context.getString(R.string.edit_rule)
                } else {
                    context.getString(R.string.add_rule)
                }
            ) 
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                // Tab Row
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text(context.getString(R.string.tab_condition)) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text(context.getString(R.string.tab_action)) }
                    )
                    Tab(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        text = { Text(context.getString(R.string.tab_time_constraint)) }
                    )
                }
                
                // Tab Content
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    when (selectedTab) {
                        0 -> {
                            // Condition Tab
                            Text(
                                text = context.getString(R.string.condition_type),
                                fontSize = 16.sp,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                            )
                            
                            LazyColumn(
                                modifier = Modifier.height(180.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(ConditionType.values().toList()) { conditionType ->
                                    val conditionText = when (conditionType) {
                                        ConditionType.TIME_INTERVAL -> context.getString(R.string.condition_time_interval_label)
                                        ConditionType.ON_ENTER -> context.getString(R.string.condition_on_enter_label)
                                        ConditionType.ON_PAGE -> context.getString(R.string.condition_on_page_label)
                                        ConditionType.ON_CONTENT -> context.getString(R.string.condition_on_content_label)
                                    }
                                    
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { selectedConditionType = conditionType }
                                            .padding(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            checked = selectedConditionType == conditionType,
                                            onCheckedChange = { selectedConditionType = conditionType }
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(text = conditionText)
                                    }
                                }
                            }
                            
                            // Time interval input for TIME_INTERVAL condition
                            if (selectedConditionType == ConditionType.TIME_INTERVAL) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(context.getString(R.string.time_interval_minutes))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    OutlinedButton(
                                        onClick = { showTimeIntervalDialog = true }
                                    ) {
                                        Text("$timeInterval")
                                    }
                                }
                            }
                            
                            // Parameter input for ON_PAGE and ON_CONTENT conditions
                            if (selectedConditionType == ConditionType.ON_PAGE || selectedConditionType == ConditionType.ON_CONTENT) {
                                OutlinedButton(
                                    onClick = { showConditionParameterDialog = true },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(conditionParameter.ifEmpty { context.getString(R.string.click_to_input_parameter) })
                                }
                            }
                        }
                        1 -> {
                            // Action Tab
                            Text(
                                text = context.getString(R.string.action_type),
                                fontSize = 16.sp,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                            )
                            
                            LazyColumn(
                                modifier = Modifier.height(180.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(ActionType.values().toList()) { actionType ->
                                    val actionText = when (actionType) {
                                        ActionType.REMIND -> context.getString(R.string.action_remind_label)
                                        ActionType.AUTO_CLICK -> context.getString(R.string.action_auto_click_label)
                                        ActionType.AUTO_SCROLL_UP -> context.getString(R.string.action_auto_scroll_up_label)
                                        ActionType.AUTO_SCROLL_DOWN -> context.getString(R.string.action_auto_scroll_down_label)
                                        ActionType.AUTO_BACK -> context.getString(R.string.action_auto_back_label)
                                        ActionType.ASK -> context.getString(R.string.action_ask_label)
                                    }
                                    
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { selectedActionType = actionType }
                                            .padding(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            checked = selectedActionType == actionType,
                                            onCheckedChange = { selectedActionType = actionType }
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(text = actionText)
                                    }
                                }
                            }
                            
                            // Action Parameter input for REMIND and AUTO_CLICK actions
                            if (selectedActionType == ActionType.REMIND || selectedActionType == ActionType.AUTO_CLICK) {
                                OutlinedButton(
                                    onClick = { showActionParameterDialog = true },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        if (selectedActionType == ActionType.REMIND) {
                                            actionParameter.ifEmpty { context.getString(R.string.click_to_input_remind_message) }
                                        } else {
                                            actionParameter.ifEmpty { context.getString(R.string.click_to_input_parameter) }
                                        }
                                    )
                                }
                            }
                        }
                        2 -> {
                            // Time Constraint Tab
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = enableTimeConstraint,
                                    onCheckedChange = { enableTimeConstraint = it }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = context.getString(R.string.enable_time_constraint),
                                    fontSize = 16.sp,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                                )
                            }
                            
                            if (enableTimeConstraint) {
                                Text(
                                    text = context.getString(R.string.time_constraint_type),
                                    fontSize = 14.sp,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                                )
                                
                                LazyColumn(
                                    modifier = Modifier.height(180.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    item {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { 
                                                    timeConstraintType = TimeConstraint.Continuous(continuousMinutes)
                                                    showContinuousMinutesDialog = true
                                                }
                                                .padding(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Checkbox(
                                                checked = timeConstraintType is TimeConstraint.Continuous,
                                                onCheckedChange = { 
                                                    if (it) {
                                                        timeConstraintType = TimeConstraint.Continuous(continuousMinutes)
                                                        showContinuousMinutesDialog = true
                                                    }
                                                }
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Column {
                                                Text(text = context.getString(R.string.time_constraint_continuous))
                                                Text(
                                                    text = context.getString(R.string.time_constraint_continuous_desc, RuleFormatter.formatMinutes(continuousMinutes)),
                                                    fontSize = 12.sp,
                                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                                )
                                            }
                                        }
                                    }
                                    item {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { 
                                                    timeConstraintType = TimeConstraint.DailyTotal(dailyTotalMinutes)
                                                    showDailyTotalMinutesDialog = true
                                                }
                                                .padding(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Checkbox(
                                                checked = timeConstraintType is TimeConstraint.DailyTotal,
                                                onCheckedChange = { 
                                                    if (it) {
                                                        timeConstraintType = TimeConstraint.DailyTotal(dailyTotalMinutes)
                                                        showDailyTotalMinutesDialog = true
                                                    }
                                                }
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Column {
                                                Text(text = context.getString(R.string.time_constraint_daily_total))
                                                Text(
                                                    text = context.getString(R.string.time_constraint_daily_total_desc, RuleFormatter.formatMinutes(dailyTotalMinutes)),
                                                    fontSize = 12.sp,
                                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                                )
                                            }
                                        }
                                    }
                                    item {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { 
                                                    timeConstraintType = TimeConstraint.RecentTotal(recentHours, recentMinutes)
                                                    showRecentHoursDialog = true
                                                }
                                                .padding(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Checkbox(
                                                checked = timeConstraintType is TimeConstraint.RecentTotal,
                                                onCheckedChange = { 
                                                    if (it) {
                                                        timeConstraintType = TimeConstraint.RecentTotal(recentHours, recentMinutes)
                                                        showRecentHoursDialog = true
                                                    }
                                                }
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Column {
                                                Text(text = context.getString(R.string.time_constraint_recent_total))
                                                Text(
                                                    text = context.getString(R.string.time_constraint_recent_total_desc, recentHours, RuleFormatter.formatMinutes(recentMinutes)),
                                                    fontSize = 12.sp,
                                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val condition = when (selectedConditionType) {
                        ConditionType.TIME_INTERVAL -> RuleCondition(
                            type = selectedConditionType,
                            timeInterval = timeInterval
                        )
                        ConditionType.ON_PAGE, ConditionType.ON_CONTENT -> RuleCondition(
                            type = selectedConditionType,
                            parameter = conditionParameter
                        )
                        else -> RuleCondition(type = selectedConditionType)
                    }
                    
                    val action = when (selectedActionType) {
                        ActionType.REMIND, ActionType.AUTO_CLICK -> RuleAction(
                            type = selectedActionType,
                            parameter = actionParameter.takeIf { it.isNotEmpty() }
                        )
                        else -> RuleAction(type = selectedActionType)
                    }
                    val timeConstraint = if (enableTimeConstraint) timeConstraintType else null
                    
                    val savedRule = if (isEditMode && rule != null) {
                        rule.copy(condition = condition, action = action, timeConstraint = timeConstraint)
                    } else {
                        Rule(condition = condition, action = action, timeConstraint = timeConstraint)
                    }
                    onSaveRule(savedRule)
                }
            ) {
                Text(
                    if (isEditMode) {
                        context.getString(R.string.save)
                    } else {
                        context.getString(R.string.add_rule)
                    }
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(context.getString(R.string.cancel))
            }
        }
    )
    
    // Condition Parameter Input Dialog
    if (showConditionParameterDialog) {
        var inputText by remember { mutableStateOf(conditionParameter) }
        AlertDialog(
            onDismissRequest = { showConditionParameterDialog = false },
            title = { Text(context.getString(R.string.input_parameter)) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = context.getString(R.string.parameter_input_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(context.getString(R.string.please_input_parameter)) }
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        conditionParameter = inputText
                        showConditionParameterDialog = false
                    }
                ) {
                    Text(context.getString(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showConditionParameterDialog = false }) {
                    Text(context.getString(R.string.cancel))
                }
            }
        )
    }
    
    // Action Parameter Input Dialog
    if (showActionParameterDialog) {
        var inputText by remember { mutableStateOf(actionParameter) }
        AlertDialog(
            onDismissRequest = { showActionParameterDialog = false },
            title = { 
                Text(
                    if (selectedActionType == ActionType.REMIND) {
                        context.getString(R.string.input_remind_message)
                    } else {
                        context.getString(R.string.input_parameter)
                    }
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = if (selectedActionType == ActionType.REMIND) {
                            context.getString(R.string.remind_message_input_hint)
                        } else {
                            context.getString(R.string.parameter_input_hint)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { 
                            Text(
                                if (selectedActionType == ActionType.REMIND) {
                                    context.getString(R.string.please_input_remind_message)
                                } else {
                                    context.getString(R.string.please_input_parameter)
                                }
                            )
                        }
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        actionParameter = inputText
                        showActionParameterDialog = false
                    }
                ) {
                    Text(context.getString(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showActionParameterDialog = false }) {
                    Text(context.getString(R.string.cancel))
                }
            }
        )
    }
    
    // Time Interval Input Dialog
    if (showTimeIntervalDialog) {
        var inputText by remember { mutableStateOf(timeInterval.toString()) }
        AlertDialog(
            onDismissRequest = { showTimeIntervalDialog = false },
            title = { Text(context.getString(R.string.input_time_interval)) },
            text = {
                TextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(context.getString(R.string.please_input_minutes)) }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        timeInterval = inputText.toIntOrNull() ?: 3
                        showTimeIntervalDialog = false
                    }
                ) {
                    Text(context.getString(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showTimeIntervalDialog = false }) {
                    Text(context.getString(R.string.cancel))
                }
            }
        )
    }
    
    // Continuous Minutes Input Dialog
    if (showContinuousMinutesDialog) {
        var inputText by remember { mutableStateOf(continuousMinutes.toString()) }
        AlertDialog(
            onDismissRequest = { showContinuousMinutesDialog = false },
            title = { Text(context.getString(R.string.input_continuous_minutes)) },
            text = {
                TextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(context.getString(R.string.please_input_minutes)) }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        continuousMinutes = inputText.toDoubleOrNull() ?: 3.0
                        timeConstraintType = TimeConstraint.Continuous(continuousMinutes)
                        showContinuousMinutesDialog = false
                    }
                ) {
                    Text(context.getString(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showContinuousMinutesDialog = false }) {
                    Text(context.getString(R.string.cancel))
                }
            }
        )
    }
    
    // Daily Total Minutes Input Dialog
    if (showDailyTotalMinutesDialog) {
        var inputText by remember { mutableStateOf(dailyTotalMinutes.toString()) }
        AlertDialog(
            onDismissRequest = { showDailyTotalMinutesDialog = false },
            title = { Text(context.getString(R.string.input_daily_total_minutes)) },
            text = {
                TextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(context.getString(R.string.please_input_minutes)) }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        dailyTotalMinutes = inputText.toDoubleOrNull() ?: 30.0
                        timeConstraintType = TimeConstraint.DailyTotal(dailyTotalMinutes)
                        showDailyTotalMinutesDialog = false
                    }
                ) {
                    Text(context.getString(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDailyTotalMinutesDialog = false }) {
                    Text(context.getString(R.string.cancel))
                }
            }
        )
    }
    
    // Recent Hours Input Dialog
    if (showRecentHoursDialog) {
        var inputText by remember { mutableStateOf(recentHours.toString()) }
        AlertDialog(
            onDismissRequest = { showRecentHoursDialog = false },
            title = { Text(context.getString(R.string.input_recent_hours)) },
            text = {
                TextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(context.getString(R.string.please_input_hours)) }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        recentHours = inputText.toIntOrNull() ?: 24
                        timeConstraintType = TimeConstraint.RecentTotal(recentHours, recentMinutes)
                        showRecentHoursDialog = false
                        showRecentMinutesDialog = true
                    }
                ) {
                    Text(context.getString(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRecentHoursDialog = false }) {
                    Text(context.getString(R.string.cancel))
                }
            }
        )
    }
    
    // Recent Minutes Input Dialog
    if (showRecentMinutesDialog) {
        var inputText by remember { mutableStateOf(recentMinutes.toString()) }
        AlertDialog(
            onDismissRequest = { showRecentMinutesDialog = false },
            title = { Text(context.getString(R.string.input_recent_minutes)) },
            text = {
                TextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(context.getString(R.string.please_input_minutes)) }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        recentMinutes = inputText.toDoubleOrNull() ?: 60.0
                        timeConstraintType = TimeConstraint.RecentTotal(recentHours, recentMinutes)
                        showRecentMinutesDialog = false
                    }
                ) {
                    Text(context.getString(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRecentMinutesDialog = false }) {
                    Text(context.getString(R.string.cancel))
                }
            }
        )
    }
}

