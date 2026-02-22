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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roderickqiu.seenot.R
import com.roderickqiu.seenot.data.ActionType

@Composable
fun ActionTab(
    selectedActionType: ActionType,
    onActionTypeSelected: (ActionType) -> Unit,
    actionParameter: String,
    onActionParameterClick: () -> Unit,
    context: android.content.Context
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
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
                        .clickable { onActionTypeSelected(actionType) }
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = selectedActionType == actionType,
                        onCheckedChange = { onActionTypeSelected(actionType) }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = actionText)
                }
            }
        }
        // Combine logic to show warning/hint for AUTO_BACK or ASK action
        if (selectedActionType == ActionType.AUTO_BACK || selectedActionType == ActionType.ASK) {
            val message = when (selectedActionType) {
                ActionType.AUTO_BACK -> context.getString(R.string.auto_back_home_warning)
                ActionType.ASK -> context.getString(R.string.ask_action_hint)
                else -> ""
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }

        // Action Parameter input for REMIND and AUTO_CLICK actions
        if (selectedActionType == ActionType.REMIND || selectedActionType == ActionType.AUTO_CLICK) {
            OutlinedButton(
                onClick = onActionParameterClick,
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
}

