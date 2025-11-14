package com.roderickqiu.seenot.components.rule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.roderickqiu.seenot.R
import com.roderickqiu.seenot.data.ActionType
import com.roderickqiu.seenot.data.ConditionType
import com.roderickqiu.seenot.data.Rule
import com.roderickqiu.seenot.data.RuleAction
import com.roderickqiu.seenot.data.RuleCondition
import com.roderickqiu.seenot.data.TimeConstraint

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
                when (selectedTab) {
                    0 -> {
                        ConditionTab(
                            selectedConditionType = selectedConditionType,
                            onConditionTypeSelected = { selectedConditionType = it },
                            timeInterval = timeInterval,
                            onTimeIntervalClick = { showTimeIntervalDialog = true },
                            conditionParameter = conditionParameter,
                            onConditionParameterClick = { showConditionParameterDialog = true },
                            context = context
                        )
                    }
                    1 -> {
                        ActionTab(
                            selectedActionType = selectedActionType,
                            onActionTypeSelected = { selectedActionType = it },
                            actionParameter = actionParameter,
                            onActionParameterClick = { showActionParameterDialog = true },
                            context = context
                        )
                    }
                    2 -> {
                        ConstraintTab(
                            enableTimeConstraint = enableTimeConstraint,
                            onEnableTimeConstraintChanged = { enableTimeConstraint = it },
                            timeConstraintType = timeConstraintType,
                            onTimeConstraintTypeChanged = { timeConstraintType = it },
                            continuousMinutes = continuousMinutes,
                            onContinuousMinutesClick = { showContinuousMinutesDialog = true },
                            dailyTotalMinutes = dailyTotalMinutes,
                            onDailyTotalMinutesClick = { showDailyTotalMinutesDialog = true },
                            recentHours = recentHours,
                            recentMinutes = recentMinutes,
                            onRecentHoursClick = { showRecentHoursDialog = true },
                            onRecentMinutesClick = { showRecentMinutesDialog = true },
                            context = context
                        )
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
                    
                    val savedRule = if (isEditMode) {
                        rule!!.copy(condition = condition, action = action, timeConstraint = timeConstraint)
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

