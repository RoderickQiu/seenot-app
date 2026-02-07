package com.roderickqiu.seenot.components.rule

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roderickqiu.seenot.R
import com.roderickqiu.seenot.data.ActionType
import com.roderickqiu.seenot.data.ConditionType
import com.roderickqiu.seenot.data.Rule

@Composable
fun RuleItem(
    rule: Rule,
    isEnabled: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    onEditRule: (Rule) -> Unit,
    onDeleteRule: () -> Unit,
    onDuplicateRule: () -> Unit,
    /** When non-null, show a line like "将于 14:30 自动恢复" under the rule */
    reopenAtText: String? = null
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
            val conditionType = rule.condition.type
            val conditionText = when (conditionType) {
                ConditionType.TIME_INTERVAL -> {
                    val interval = rule.condition.timeInterval ?: 0
                    context.getString(R.string.condition_time_interval, interval)
                }
                ConditionType.ON_ENTER -> context.getString(R.string.condition_on_enter)
                ConditionType.ON_PAGE -> {
                    val parameter = rule.condition.parameter ?: ""
                    context.getString(R.string.condition_on_page, parameter)
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
            if (reopenAtText != null) {
                Text(
                    text = reopenAtText,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
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

