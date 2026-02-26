package com.roderickqiu.seenot.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roderickqiu.seenot.R
import com.roderickqiu.seenot.data.AIStatsRepo
import com.roderickqiu.seenot.data.ContentLabel
import com.roderickqiu.seenot.data.DailyStats
import com.roderickqiu.seenot.data.LabelMergeSuggestion
import com.roderickqiu.seenot.data.LabelNormalizationRepo
import com.roderickqiu.seenot.data.PeriodStats
import com.roderickqiu.seenot.data.ScreenObservation
import java.math.BigInteger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DebugStatsDialog(
    onDismiss: () -> Unit,
    onClearStats: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val statsRepo = remember { AIStatsRepo(context) }
    val stats = remember { statsRepo.getDisplayStats() }
    val labelRepo = remember { LabelNormalizationRepo(context) }
    val labels = remember { labelRepo.loadLabels() }
    val observations = remember { labelRepo.loadObservations() }
    val mergeSuggestions = remember { labelRepo.loadMergeSuggestions() }
    
    // Build app -> labels mapping from observations
    val appLabelsMap = remember(observations, labels) {
        buildAppLabelsMap(observations, labels)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = context.getString(R.string.debug_stats_title),
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 600.dp)
                    .verticalScroll(scrollState)
            ) {
                // Today stats - DailyStats extends PeriodStats via delegation
                DailyStatsSection(
                    title = context.getString(R.string.stats_today),
                    stats = stats.today
                )

                Spacer(modifier = Modifier.height(16.dp))

                // This week stats
                PeriodStatsSection(
                    title = context.getString(R.string.stats_this_week),
                    stats = stats.thisWeek
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Overall stats
                PeriodStatsSection(
                    title = context.getString(R.string.stats_overall),
                    stats = stats.overall
                )

                Spacer(modifier = Modifier.height(16.dp))

                // LabelNorm labels section - grouped by app
                LabelsByAppSection(
                    activeLabels = labels.filter { it.mergedInto == null && it.labelId != LabelNormalizationRepo.UNKNOWN_LABEL_ID },
                    appLabelsMap = appLabelsMap
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Merge history section
                MergeHistorySection(mergeSuggestions = mergeSuggestions)

                Spacer(modifier = Modifier.height(16.dp))

                // Last updated
                if (stats.lastUpdated > 0) {
                    Text(
                        text = context.getString(
                            R.string.stats_last_updated,
                            formatTimestamp(stats.lastUpdated)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(context.getString(R.string.ok))
            }
        },
        dismissButton = {
            if (onClearStats != null) {
                TextButton(
                    onClick = onClearStats,
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(context.getString(R.string.clear_stats))
                }
            }
        }
    )
}

/**
 * Build a map of appName -> list of labels used by that app.
 * Resolved merged labels to their target labels.
 */
private fun buildAppLabelsMap(
    observations: List<ScreenObservation>,
    labels: List<ContentLabel>
): Map<String, List<ContentLabel>> {
    val labelById = labels.associateBy { it.labelId }
    
    // For each app, collect the set of resolved label IDs used
    val appLabelIds = observations
        .filter { it.labelId != null }
        .groupBy { it.appName }
        .mapValues { (_, obsList) ->
            obsList.map { obs ->
                resolveLabelId(labelById, obs.labelId)
            }.filter { it != LabelNormalizationRepo.UNKNOWN_LABEL_ID }
             .distinct()
        }
    
    // Convert label IDs to ContentLabel objects
    return appLabelIds.mapValues { (_, labelIds) ->
        labelIds.mapNotNull { labelById[it] }
            .filter { it.mergedInto == null } // Only show active labels
    }.toSortedMap()
}

/**
 * Resolve a label ID following merge chain.
 */
private fun resolveLabelId(labelById: Map<String, ContentLabel>, id: String?): String? {
    if (id == null) return null
    var cur = id
    val visited = mutableSetOf<String>()
    while (true) {
        val label = labelById[cur] ?: return cur
        val next = label.mergedInto ?: return cur
        if (!visited.add(next)) return cur // Cycle detected
        cur = next
    }
}

@Composable
private fun PeriodStatsSection(
    title: String,
    stats: PeriodStats
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(12.dp))

            PeriodStatsContent(stats = stats)
        }
    }
}

@Composable
private fun DailyStatsSection(
    title: String,
    stats: DailyStats
) {
    val context = LocalContext.current
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(12.dp))

            // First show period stats content
            PeriodStatsContent(stats = stats.periodStats)

            // Additional daily-specific stats
            androidx.compose.material3.HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            StatRow(
                label = context.getString(R.string.stats_action_executions),
                value = formatBigInt(stats.actionExecutions)
            )
        }
    }
}

@Composable
private fun PeriodStatsContent(stats: PeriodStats) {
    val context = LocalContext.current
    
    // Requests row
    StatRow(
        label = context.getString(R.string.stats_requests_success_failed),
        value = "${formatBigInt(stats.totalRequests)} / ${formatBigInt(stats.successfulRequests)} / ${formatBigInt(stats.failedRequests)}"
    )

    // Success rate
    StatRow(
        label = context.getString(R.string.stats_success_rate),
        value = context.getString(R.string.stats_percent_value, (stats.successRate * 100).toInt())
    )

    // Rule matches
    StatRow(
        label = context.getString(R.string.stats_rule_matches),
        value = formatBigInt(stats.ruleMatches)
    )

    androidx.compose.material3.HorizontalDivider(
        modifier = Modifier.padding(vertical = 8.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    )

    // Tokens
    StatRow(
        label = context.getString(R.string.stats_total_tokens),
        value = formatBigInt(stats.totalTokens)
    )

    StatRow(
        label = context.getString(R.string.stats_prompt_completion),
        value = "${formatBigInt(stats.promptTokens)} / ${formatBigInt(stats.completionTokens)}"
    )

    androidx.compose.material3.HorizontalDivider(
        modifier = Modifier.padding(vertical = 8.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    )

    // Latency
    StatRow(
        label = context.getString(R.string.stats_avg_latency),
        value = context.getString(R.string.stats_unit_ms, stats.avgLatencyMs)
    )

    if (stats.recordCount > BigInteger.ZERO) {
        StatRow(
            label = context.getString(R.string.stats_latency_range),
            value = context.getString(R.string.stats_latency_range_value, stats.minLatencyMs, stats.maxLatencyMs)
        )
    }
}

@Composable
private fun StatRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp
        )
    }
}

@Composable
private fun LabelsByAppSection(
    activeLabels: List<ContentLabel>,
    appLabelsMap: Map<String, List<ContentLabel>>
) {
    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    
    // Count apps and labels
    val appCount = appLabelsMap.size
    val totalLabelCount = activeLabels.size
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = context.getString(R.string.debug_activity_insights),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (expanded) context.getString(R.string.debug_collapse) else context.getString(R.string.label_collapse)
                    )
                }
            }
            
            // Show count on new line
            Text(
                text = context.getString(R.string.debug_apps_labels_count, appCount, totalLabelCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )

            if (expanded) {
                if (appLabelsMap.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    // Show labels grouped by app
                    appLabelsMap.forEach { (appName, appLabels) ->
                        if (appLabels.isNotEmpty()) {
                            AppLabelGroup(
                                appName = appName,
                                labels = appLabels
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                    
                    // Show labels not associated with any app
                    val unusedLabels = activeLabels.filter { label ->
                        appLabelsMap.values.none { appLabels -> appLabels.any { it.labelId == label.labelId } }
                    }
                    if (unusedLabels.isNotEmpty()) {
                        AppLabelGroup(
                            appName = context.getString(R.string.debug_unused_labels),
                            labels = unusedLabels
                        )
                    }
                } else if (activeLabels.isNotEmpty()) {
                    // No observations but have labels - show all labels flat
                    Spacer(modifier = Modifier.height(12.dp))
                    AppLabelGroup(
                        appName = context.getString(R.string.debug_all_labels),
                        labels = activeLabels
                    )
                } else {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = context.getString(R.string.debug_no_labels),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else if (activeLabels.isEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = context.getString(R.string.debug_no_labels),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AppLabelGroup(
    appName: String,
    labels: List<ContentLabel>,
    initialVisibleCount: Int = 3
) {
    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        // App name header with label count
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = appName,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
            )
            
            if (labels.size > initialVisibleCount) {
                Text(
                    text = context.getString(R.string.debug_app_label_count, labels.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }
        
        Spacer(modifier = Modifier.height(4.dp))
        
        // Labels for this app (sorted by name)
        val sortedLabels = labels.sortedBy { it.displayName }
        val visibleLabels = if (expanded) sortedLabels else sortedLabels.take(initialVisibleCount)
        
        visibleLabels.forEach { label ->
            LabelItem(label = label)
        }
        
        // Show "还有x条记录" if collapsed and there are more labels
        if (!expanded && sortedLabels.size > initialVisibleCount) {
            val remainingCount = sortedLabels.size - initialVisibleCount
            Text(
                text = context.getString(R.string.debug_expand_labels, remainingCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(start = 8.dp, top = 4.dp)
                    .clickable { expanded = true },
                fontWeight = FontWeight.Medium
            )
        }
        
        // Show "收起" button if expanded and there are more labels
        if (expanded && sortedLabels.size > initialVisibleCount) {
            Text(
                text = context.getString(R.string.debug_collapse),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier
                    .padding(start = 8.dp, top = 4.dp)
                    .clickable { expanded = false }
            )
        }
    }
}

@Composable
private fun LabelItem(label: ContentLabel) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label.displayName,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        
        if (label.localizedNames.isNotEmpty()) {
            Text(
                text = label.localizedNames.values.firstOrNull() ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}

@Composable
private fun MergeHistorySection(
    mergeSuggestions: List<LabelMergeSuggestion>,
    initialVisibleCount: Int = 5
) {
    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = context.getString(R.string.debug_merge_history),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = context.getString(R.string.debug_merge_records_count, mergeSuggestions.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    IconButton(onClick = { expanded = !expanded }) {
                        Icon(
                            imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = if (expanded) context.getString(R.string.debug_collapse) else context.getString(R.string.label_collapse)
                        )
                    }
                }
            }

            if (expanded && mergeSuggestions.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                
                // Show all merge suggestions
                mergeSuggestions
                    .sortedByDescending { it.suggestedAt }
                    .forEachIndexed { index, suggestion ->
                        MergeSuggestionItem(suggestion = suggestion)
                        if (index < mergeSuggestions.size - 1) {
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 4.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                            )
                        }
                    }
                
                // Show collapse button when expanded
                if (mergeSuggestions.size > initialVisibleCount) {
                    Text(
                        text = context.getString(R.string.debug_collapse),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .clickable { expanded = false }
                    )
                }
            } else if (mergeSuggestions.isNotEmpty()) {
                // Collapsed state - show only initialVisibleCount
                Spacer(modifier = Modifier.height(12.dp))
                
                mergeSuggestions
                    .sortedByDescending { it.suggestedAt }
                    .take(initialVisibleCount)
                    .forEachIndexed { index, suggestion ->
                        MergeSuggestionItem(suggestion = suggestion)
                        if (index < minOf(initialVisibleCount, mergeSuggestions.size) - 1) {
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 4.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                            )
                        }
                    }
                
                // Show "还有x条记录" if there are more
                if (mergeSuggestions.size > initialVisibleCount) {
                    val remainingCount = mergeSuggestions.size - initialVisibleCount
                    Text(
                        text = context.getString(R.string.debug_expand_all_records, remainingCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .clickable { expanded = true },
                        fontWeight = FontWeight.Medium
                    )
                }
            } else if (mergeSuggestions.isEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = context.getString(R.string.debug_no_merge_records),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun MergeSuggestionItem(suggestion: LabelMergeSuggestion) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp, horizontal = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = suggestion.mergeFrom,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            
            Text(
                text = "→",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
            
            Text(
                text = suggestion.mergeInto,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            suggestion.reason?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(end = 8.dp)
                )
            }
            
            Text(
                text = formatTimestamp(suggestion.suggestedAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }
    }
}

/**
 * Format BigInteger for display with thousand separators.
 */
private fun formatBigInt(value: BigInteger): String {
    return value.toString().reversed()
        .chunked(3)
        .joinToString(",")
        .reversed()
        .ifEmpty { "0" }
}

/**
 * Format timestamp to readable string.
 */
private fun formatTimestamp(timestamp: Long): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
