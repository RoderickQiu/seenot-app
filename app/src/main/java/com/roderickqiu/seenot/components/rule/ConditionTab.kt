package com.roderickqiu.seenot.components.rule

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roderickqiu.seenot.R
import com.roderickqiu.seenot.data.ConditionType

@Composable
fun ConditionTab(
    selectedConditionType: ConditionType,
    onConditionTypeSelected: (ConditionType) -> Unit,
    timeInterval: Int,
    onTimeIntervalClick: () -> Unit,
    conditionParameter: String,
    onConditionParameterClick: () -> Unit,
    onFinetuneClick: () -> Unit,
    context: android.content.Context
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
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
                }
                val isSelected = selectedConditionType == conditionType
                val onSelect: () -> Unit = { onConditionTypeSelected(conditionType) }
                
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onSelect)
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onSelect() }
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
                    onClick = onTimeIntervalClick
                ) {
                    Text("$timeInterval")
                }
            }
        }
        
        // Parameter input for ON_PAGE condition
        if (selectedConditionType == ConditionType.ON_PAGE) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onConditionParameterClick),
                    shape = RoundedCornerShape(4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Text(
                        text = conditionParameter.ifEmpty { context.getString(R.string.click_to_input_parameter) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        fontSize = 14.sp,
                        maxLines = 3,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
                
                TextButton(
                    onClick = onFinetuneClick,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = context.getString(R.string.finetune_description),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = context.getString(R.string.finetune_optimize_with_examples),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
    }
}

