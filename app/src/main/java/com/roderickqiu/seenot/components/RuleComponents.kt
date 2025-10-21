package com.roderickqiu.seenot.components

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roderickqiu.seenot.R
import com.roderickqiu.seenot.data.ActionType
import com.roderickqiu.seenot.data.ConditionType
import com.roderickqiu.seenot.data.Rule
import com.roderickqiu.seenot.data.RuleAction
import com.roderickqiu.seenot.data.RuleCondition

@Composable
fun RuleItem(
    rule: Rule,
    isEnabled: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    onEditRule: (Rule) -> Unit,
    onDeleteRule: () -> Unit,
    onDuplicateRule: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var showMenu by remember { mutableStateOf(false) }
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggleEnabled(!isEnabled) }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Checkbox
        Checkbox(
            checked = isEnabled,
            onCheckedChange = onToggleEnabled,
            modifier = Modifier.size(24.dp)
        )
        
        Spacer(modifier = Modifier.width(12.dp))
        
        // Rule description
        Column(
            modifier = Modifier.weight(1f)
        ) {
            val conditionText = when (rule.condition.type) {
                ConditionType.TIME_INTERVAL -> {
                    val interval = rule.condition.timeInterval ?: 0
                    context.getString(R.string.condition_time_interval, interval)
                }
                ConditionType.ON_ENTER -> context.getString(R.string.condition_on_enter)
                ConditionType.ON_PAGE -> {
                    val parameter = rule.condition.parameter ?: ""
                    context.getString(R.string.condition_on_page, parameter)
                }
                ConditionType.ON_CONTENT -> {
                    val parameter = rule.condition.parameter ?: ""
                    context.getString(R.string.condition_on_content, parameter)
                }
            }
            
            val actionText = when (rule.action.type) {
                ActionType.REMIND -> context.getString(R.string.action_remind)
                ActionType.AUTO_CLICK -> {
                    val parameter = rule.action.parameter ?: ""
                    context.getString(R.string.action_auto_click, parameter)
                }
                ActionType.AUTO_SCROLL_UP -> context.getString(R.string.action_auto_scroll_up)
                ActionType.AUTO_SCROLL_DOWN -> context.getString(R.string.action_auto_scroll_down)
                ActionType.AUTO_BACK -> context.getString(R.string.action_auto_back)
                ActionType.ASK -> context.getString(R.string.action_ask)
            }
            
            Text(
                text = conditionText,
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = actionText,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        
        // More options menu
        Box {
            IconButton(
                onClick = { showMenu = true },
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = context.getString(R.string.more_options),
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
            
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text(context.getString(R.string.edit_rule)) },
                    onClick = {
                        showMenu = false
                        onEditRule(rule)
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
                DropdownMenuItem(
                    text = { Text(context.getString(R.string.duplicate_rule)) },
                    onClick = {
                        showMenu = false
                        onDuplicateRule()
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
                DropdownMenuItem(
                    text = { Text(context.getString(R.string.delete_rule)) },
                    onClick = {
                        showMenu = false
                        onDeleteRule()
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
            }
        }
    }
}

@Composable
fun AddRuleDialog(
    onDismiss: () -> Unit,
    onAddRule: (Rule) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var selectedConditionType by remember { mutableStateOf(ConditionType.ON_ENTER) }
    var selectedActionType by remember { mutableStateOf(ActionType.ASK) }
    var timeInterval by remember { mutableStateOf(3) }
    var parameter by remember { mutableStateOf("") }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(context.getString(R.string.add_rule)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Condition Type Selection
                Text(
                    text = "条件类型",
                    fontSize = 16.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                )
                
                LazyColumn(
                    modifier = Modifier.height(120.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(ConditionType.values().toList()) { conditionType ->
                        val conditionText = when (conditionType) {
                            ConditionType.TIME_INTERVAL -> "每X分钟"
                            ConditionType.ON_ENTER -> "每次进入时"
                            ConditionType.ON_PAGE -> "如果在特定页面"
                            ConditionType.ON_CONTENT -> "如果内容关于特定主题"
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
                        Text("间隔时间（分钟）:")
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = { /* TODO: Implement number picker */ }
                        ) {
                            Text("$timeInterval")
                        }
                    }
                }
                
                // Parameter input for ON_PAGE and ON_CONTENT conditions
                if (selectedConditionType == ConditionType.ON_PAGE || selectedConditionType == ConditionType.ON_CONTENT) {
                    OutlinedButton(
                        onClick = { /* TODO: Implement parameter input */ },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(parameter.ifEmpty { "点击输入参数" })
                    }
                }
                
                // Action Type Selection
                Text(
                    text = "动作类型",
                    fontSize = 16.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                )
                
                LazyColumn(
                    modifier = Modifier.height(120.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(ActionType.values().toList()) { actionType ->
                        val actionText = when (actionType) {
                            ActionType.REMIND -> "进行提醒"
                            ActionType.AUTO_CLICK -> "自动点击"
                            ActionType.AUTO_SCROLL_UP -> "自动上滑"
                            ActionType.AUTO_SCROLL_DOWN -> "自动下滑"
                            ActionType.AUTO_BACK -> "自动回退"
                            ActionType.ASK -> "询问"
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
                            parameter = parameter
                        )
                        else -> RuleCondition(type = selectedConditionType)
                    }
                    
                    val action = RuleAction(type = selectedActionType)
                    val newRule = Rule(condition = condition, action = action)
                    onAddRule(newRule)
                }
            ) {
                Text(context.getString(R.string.add_rule))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(context.getString(R.string.cancel))
            }
        }
    )
}
