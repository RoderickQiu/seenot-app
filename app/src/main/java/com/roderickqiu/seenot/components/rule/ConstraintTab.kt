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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roderickqiu.seenot.R
import com.roderickqiu.seenot.data.TimeConstraint
import com.roderickqiu.seenot.utils.RuleFormatter

@Composable
fun ConstraintTab(
    enableTimeConstraint: Boolean,
    onEnableTimeConstraintChanged: (Boolean) -> Unit,
    timeConstraintType: TimeConstraint?,
    onTimeConstraintTypeChanged: (TimeConstraint?) -> Unit,
    continuousMinutes: Double,
    onContinuousMinutesClick: () -> Unit,
    dailyTotalMinutes: Double,
    onDailyTotalMinutesClick: () -> Unit,
    recentHours: Int,
    recentMinutes: Double,
    onRecentHoursClick: () -> Unit,
    onRecentMinutesClick: () -> Unit,
    context: android.content.Context
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = enableTimeConstraint,
                onCheckedChange = onEnableTimeConstraintChanged
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
                                onTimeConstraintTypeChanged(TimeConstraint.Continuous(continuousMinutes))
                                onContinuousMinutesClick()
                            }
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = timeConstraintType is TimeConstraint.Continuous,
                            onCheckedChange = { 
                                if (it) {
                                    onTimeConstraintTypeChanged(TimeConstraint.Continuous(continuousMinutes))
                                    onContinuousMinutesClick()
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
                                onTimeConstraintTypeChanged(TimeConstraint.DailyTotal(dailyTotalMinutes))
                                onDailyTotalMinutesClick()
                            }
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = timeConstraintType is TimeConstraint.DailyTotal,
                            onCheckedChange = { 
                                if (it) {
                                    onTimeConstraintTypeChanged(TimeConstraint.DailyTotal(dailyTotalMinutes))
                                    onDailyTotalMinutesClick()
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
                                onTimeConstraintTypeChanged(TimeConstraint.RecentTotal(recentHours, recentMinutes))
                                onRecentHoursClick()
                            }
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = timeConstraintType is TimeConstraint.RecentTotal,
                            onCheckedChange = { 
                                if (it) {
                                    onTimeConstraintTypeChanged(TimeConstraint.RecentTotal(recentHours, recentMinutes))
                                    onRecentHoursClick()
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
            
            Text(
                text = context.getString(R.string.time_constraint_decay_note),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

