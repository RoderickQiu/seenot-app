package com.roderickqiu.seenot.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roderickqiu.seenot.R
import com.roderickqiu.seenot.data.ActionExecution
import com.roderickqiu.seenot.data.ActionType
import com.roderickqiu.seenot.data.AppSession
import com.roderickqiu.seenot.data.AppUsage
import com.roderickqiu.seenot.data.SessionItem
import com.roderickqiu.seenot.data.LabelNormalizationRepo
import com.roderickqiu.seenot.data.TimelineEvent
import com.roderickqiu.seenot.data.UsageSummary
import com.roderickqiu.seenot.data.UsageSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// ---------------------------------------------------------------------------
// Color palette - stable across tabs, keyed by app name
// ---------------------------------------------------------------------------

private val APP_COLORS = listOf(
    Color(0xFF5C6BC0), // indigo
    Color(0xFF26A69A), // teal
    Color(0xFFEF5350), // red
    Color(0xFFFF7043), // deep orange
    Color(0xFF42A5F5), // blue
    Color(0xFF66BB6A), // green
    Color(0xFFAB47BC), // purple
    Color(0xFFFFCA28), // amber
)

private fun buildAppColorMap(vararg summaries: UsageSummary?): Map<String, Color> {
    val allApps = summaries
        .filterNotNull()
        .flatMap { it.apps.map { a -> a.appName } }
        .distinct()
        .sorted()
    return allApps.mapIndexed { i, name -> name to APP_COLORS[i % APP_COLORS.size] }.toMap()
}

private fun appColor(appName: String, colorMap: Map<String, Color>): Color =
    colorMap[appName] ?: APP_COLORS[appName.hashCode().and(0x7FFFFFFF) % APP_COLORS.size]

// ---------------------------------------------------------------------------
// Root page
// ---------------------------------------------------------------------------

enum class SummaryRange { TODAY, YESTERDAY, THIS_WEEK, THIS_MONTH }

@Composable
fun LabelNormPage(
    modifier: Modifier = Modifier,
    repo: LabelNormalizationRepo = LabelNormalizationRepo(LocalContext.current),
    refreshKey: Int = 0
) {
    val context = LocalContext.current

    // Summary tab data - loaded based on selected range
    var summaryData by remember { mutableStateOf<UsageSummary?>(null) }
    var selectedSummaryRange by remember { mutableStateOf(SummaryRange.TODAY) }

    // Timeline data - 7 days of mixed timeline events (sessions + action executions)
    var timelineEvents by remember { mutableStateOf<List<List<TimelineEvent>>>(emptyList()) }
    var selectedTimelineDay by remember { mutableStateOf(6) } // 6 = today (last in the 7-day list, indices 0..6)

    // Main tab: 0 = Summary, 1 = Timeline
    var selectedMainTab by remember { mutableStateOf(0) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(refreshKey, selectedSummaryRange, selectedMainTab) {
        isLoading = true
        withContext(Dispatchers.IO) {
            val langCode = com.roderickqiu.seenot.utils.LanguageManager.getEffectiveLanguage(context)

            if (langCode != "en") {
                val targetLanguage = Locale.forLanguageTag(langCode).getDisplayLanguage(Locale.ENGLISH)
                repo.ensureLocalizedNames(
                    languageCode = langCode,
                    targetLanguage = targetLanguage,
                    context = context,
                    note = "Very brief label name, ideally 2-5 characters."
                )
            }

            // Load data based on current tab
            if (selectedMainTab == 0) {
                // Summary tab - load selected range
                val (startMs, endMs) = when (selectedSummaryRange) {
                    SummaryRange.TODAY -> dayRange(0)
                    SummaryRange.YESTERDAY -> dayRange(-1)
                    SummaryRange.THIS_WEEK -> weekRange()
                    SummaryRange.THIS_MONTH -> monthRange()
                }
                summaryData = repo.computeSummary(startMs, endMs, langCode)
            } else {
                // Timeline tab - load last 7 days mixed timeline (sessions + action executions)
                val dayRanges = lastNDaysRanges(7)
                val eventsList = dayRanges.map { (range, _) ->
                    repo.buildMixedTimeline(range.first, range.second, langCode)
                }
                timelineEvents = eventsList
                // Ensure selected day index is valid
                if (selectedTimelineDay >= eventsList.size) {
                    selectedTimelineDay = eventsList.size - 1
                }
            }
            isLoading = false
        }
    }

    // Build color map from all available data (including timeline events)
    val appColorMap: Map<String, Color> = remember(summaryData, timelineEvents) {
        val allSummaries = listOfNotNull(summaryData)
        val appNamesFromTimeline = timelineEvents.flatten().map { it.session.appName }.distinct()
        // Combine app names from summary and timeline
        val allAppNames = (allSummaries.flatMap { it.apps.map { a -> a.appName } } + appNamesFromTimeline).distinct().sorted()
        allAppNames.mapIndexed { i, name -> name to APP_COLORS[i % APP_COLORS.size] }.toMap()
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Main tabs: Summary | Timeline - fixed width 50% each
        TabRow(selectedTabIndex = selectedMainTab) {
            Tab(
                selected = selectedMainTab == 0,
                onClick = { selectedMainTab = 0 },
                text = { Text(stringResource(R.string.label_summary)) }
            )
            Tab(
                selected = selectedMainTab == 1,
                onClick = { selectedMainTab = 1 },
                text = { Text(stringResource(R.string.label_timeline)) }
            )
        }

        when (selectedMainTab) {
            0 -> {
                // Summary tab: range picker + summary content
                SummaryWithRangePicker(
                    selectedRange = selectedSummaryRange,
                    onRangeChange = { selectedSummaryRange = it },
                    summary = summaryData,
                    appColorMap = appColorMap,
                    isLoading = isLoading
                )
            }
            1 -> {
                // Timeline tab: day picker + timeline content
                val dayRanges = lastNDaysRanges(7)
                TimelineWithDayPicker(
                    days = dayRanges.mapIndexed { index, (range, dayIndex) ->
                        DayInfo(
                            label = getDayLabel(dayIndex, range.first),
                            range = range,
                            hasData = timelineEvents.getOrNull(index)?.isNotEmpty() ?: false
                        )
                    },
                    selectedDayIndex = selectedTimelineDay,
                    onDaySelected = { selectedTimelineDay = it },
                    events = timelineEvents.getOrNull(selectedTimelineDay) ?: emptyList(),
                    appColorMap = appColorMap,
                    isLoading = isLoading
                )
            }
        }
    }
}

data class DayInfo(
    val label: String,
    val range: Pair<Long, Long>,
    val hasData: Boolean
)

// ---------------------------------------------------------------------------
// Loading / empty helpers
// ---------------------------------------------------------------------------

@Composable
private fun LoadingBox() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.label_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun EmptyBox(message: String? = null, hint: String? = null) {
    val displayMessage = message ?: stringResource(R.string.label_no_data)
    val displayHint = hint ?: stringResource(R.string.label_no_data_hint)
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Text(text = displayMessage, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Text(text = displayHint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

// ---------------------------------------------------------------------------
// Summary Components
// ---------------------------------------------------------------------------

private const val MIN_CATEGORY_DURATION_MS = 60_000L

@Composable
private fun TotalDurationHeader(summary: UsageSummary) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = stringResource(R.string.label_total_duration),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = formatDuration(summary.totalDurationMs),
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.label_apps_records, summary.apps.size, summary.totalObservations),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun AppUsageCard(app: AppUsage, color: Color) {
    val allCategories = app.categories
    if (allCategories.isEmpty()) return

    val majorCategories = allCategories.filter { it.durationMs >= MIN_CATEGORY_DURATION_MS }
    val minorCategories = allCategories.filter { it.durationMs < MIN_CATEGORY_DURATION_MS }
    val hasMinor = minorCategories.isNotEmpty()

    var expanded by remember { mutableStateOf(false) }
    val visibleCategories = when {
        expanded -> allCategories
        majorCategories.isNotEmpty() -> majorCategories
        else -> allCategories
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(10.dp))
                Text(
                    text = app.appName,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = formatDuration(app.totalDurationMs),
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = color
                )
            }
            Spacer(Modifier.height(10.dp))
            visibleCategories.forEach { cat ->
                CategoryProgressRow(label = cat.displayName, percentage = cat.percentage, durationMs = cat.durationMs, color = color)
            }
            if (hasMinor) {
                androidx.compose.material3.TextButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.align(Alignment.End),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                ) {
                    Text(
                        text = if (expanded) stringResource(R.string.label_collapse) else stringResource(R.string.label_more_items, minorCategories.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = color.copy(alpha = 0.75f)
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryProgressRow(label: String, percentage: Float, durationMs: Long, color: Color) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
            Text(
                text = "${percentage.toInt()}%  ${formatDuration(durationMs)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { (percentage / 100f).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
            color = color,
            trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
        )
        Spacer(Modifier.height(8.dp))
    }
}

// ---------------------------------------------------------------------------
// Summary with Range Picker
// ---------------------------------------------------------------------------

@Composable
private fun SummaryWithRangePicker(
    selectedRange: SummaryRange,
    onRangeChange: (SummaryRange) -> Unit,
    summary: UsageSummary?,
    appColorMap: Map<String, Color>,
    isLoading: Boolean
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Range picker chips - horizontally scrollable
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = selectedRange == SummaryRange.TODAY,
                onClick = { onRangeChange(SummaryRange.TODAY) },
                label = {
                    Text(
                        text = stringResource(R.string.label_tab_today),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
            FilterChip(
                selected = selectedRange == SummaryRange.YESTERDAY,
                onClick = { onRangeChange(SummaryRange.YESTERDAY) },
                label = {
                    Text(
                        text = stringResource(R.string.label_tab_yesterday),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
            FilterChip(
                selected = selectedRange == SummaryRange.THIS_WEEK,
                onClick = { onRangeChange(SummaryRange.THIS_WEEK) },
                label = {
                    Text(
                        text = stringResource(R.string.label_this_week),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
            FilterChip(
                selected = selectedRange == SummaryRange.THIS_MONTH,
                onClick = { onRangeChange(SummaryRange.THIS_MONTH) },
                label = {
                    Text(
                        text = stringResource(R.string.label_this_month),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }

        when {
            isLoading -> LoadingBox()
            summary == null || summary.totalObservations == 0 -> EmptyBox()
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item { TotalDurationHeader(summary = summary) }
                item { Spacer(Modifier.height(4.dp)) }
                items(summary.apps, key = { it.appName }) { app ->
                    AppUsageCard(app = app, color = appColor(app.appName, appColorMap))
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Timeline with Day Picker (Last 7 days)
// ---------------------------------------------------------------------------

@Composable
private fun TimelineWithDayPicker(
    days: List<DayInfo>,
    selectedDayIndex: Int,
    onDaySelected: (Int) -> Unit,
    events: List<TimelineEvent>,
    appColorMap: Map<String, Color>,
    isLoading: Boolean
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // 7-day picker - horizontally scrollable
        ScrollableTabRow(
            selectedTabIndex = selectedDayIndex,
            edgePadding = 16.dp,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary
        ) {
            days.forEachIndexed { index, day ->
                Tab(
                    selected = selectedDayIndex == index,
                    onClick = { onDaySelected(index) },
                    text = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = day.label,
                                style = MaterialTheme.typography.labelSmall
                            )
                            if (day.hasData) {
                                Box(
                                    modifier = Modifier
                                        .padding(top = 2.dp)
                                        .size(4.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary)
                                )
                            }
                        }
                    }
                )
            }
        }

        Spacer(modifier = androidx.compose.ui.Modifier.height(16.dp))

        when {
            isLoading -> LoadingBox()
            events.isEmpty() -> EmptyBox(hint = stringResource(R.string.label_no_day_data))
            else -> TimelineList(events = events, appColorMap = appColorMap)
        }
    }
}

// ---------------------------------------------------------------------------
// App session grouping - consecutive segments of the same app
// ---------------------------------------------------------------------------

private fun groupIntoSessions(segments: List<UsageSegment>): List<AppSession> {
    if (segments.isEmpty()) return emptyList()
    val sessions = mutableListOf<AppSession>()
    var group = mutableListOf(segments[0])
    for (i in 1 until segments.size) {
        if (segments[i].appName == group.last().appName) {
            group.add(segments[i])
        } else {
            sessions.add(group.toSession())
            group = mutableListOf(segments[i])
        }
    }
    sessions.add(group.toSession())
    return sessions
}

private fun List<UsageSegment>.toSession() = AppSession(
    appName = first().appName,
    startMs = first().startMs,
    totalDurationMs = sumOf { it.durationMs },
    items = map { SessionItem.SegmentItem(it) }
)

// ---------------------------------------------------------------------------
// Timeline list - displays app sessions with embedded actions
// ---------------------------------------------------------------------------

@Composable
private fun TimelineList(events: List<TimelineEvent>, appColorMap: Map<String, Color>) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp)
    ) {
        itemsIndexed(events, key = { _, event -> "session_${event.session.startMs}" }) { index, event ->
            val isLast = index == events.lastIndex
            AppSessionItem(
                session = event.session,
                color = appColor(event.session.appName, appColorMap),
                isLast = isLast
            )
        }
    }
}

@Composable
private fun AppSessionItem(session: AppSession, color: Color, isLast: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    val hasMultiple = session.hasMultipleSegments
    val hasActions = session.hasActions
    val shouldExpand = hasMultiple || hasActions

    // IntrinsicSize.Min lets the connector line use weight(1f) to
    // fill exactly the content height - no hardcoded estimates needed.
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        verticalAlignment = Alignment.Top
    ) {
        // Time
        Text(
            text = formatTime(session.startMs),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.width(40.dp).padding(top = 4.dp)
        )
        Spacer(Modifier.width(10.dp))

        // Connector dot + line (app session uses app color)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(14.dp).fillMaxHeight()
        ) {
            Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(color))
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .weight(1f)
                        .background(color.copy(alpha = 0.18f))
                )
            }
        }
        Spacer(Modifier.width(12.dp))

        // Content
        Column(modifier = Modifier.weight(1f).padding(bottom = if (isLast) 8.dp else 16.dp)) {
            // Header row
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = session.appName,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.weight(1f)
                )

                // Duration badge
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(color.copy(alpha = 0.15f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = formatDuration(session.totalDurationMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = color
                    )
                }

                Spacer(Modifier.width(6.dp))

                // Action count tag (if has actions)
                if (hasActions) {
                    ActionCountTag(count = session.actions.size)
                    Spacer(Modifier.width(4.dp))
                }

                // Expand button (if has multiple segments or actions)
                if (shouldExpand) {
                    IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(28.dp)) {
                        Icon(
                            imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // Collapsed state summary
            if (!expanded) {
                // Single segment: show label
                if (!hasMultiple) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = session.segments.first().displayName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                } else {
                    // Multiple segments: show summary
                    Spacer(Modifier.height(2.dp))
                    val topLabel = session.segments.first().displayName
                    val summaryText = if (hasActions) {
                        stringResource(R.string.label_session_summary_with_actions, topLabel, session.segments.size, session.actions.size)
                    } else {
                        stringResource(R.string.label_session_summary, topLabel, session.segments.size)
                    }
                    Text(
                        text = summaryText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }
            }

            // Expanded state: show interleaved segments and actions
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 6.dp)) {
                    // Iterate through interleaved items (segments and actions mixed by timestamp)
                    session.items.forEach { item ->
                        when (item) {
                            is SessionItem.SegmentItem -> {
                                SegmentSubItem(segment = item.segment, color = color)
                            }
                            is SessionItem.ActionItem -> {
                                ActionSubItem(action = item.action)
                            }
                        }
                    }
                }
            }
        }
    }
}

private val subItemMarkerSize = 8.dp
private val subItemMarkerGap = 8.dp

@Composable
private fun SegmentSubItem(segment: UsageSegment, color: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(subItemMarkerSize)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.5f))
        )
        Spacer(Modifier.width(subItemMarkerGap))
        Text(
            text = segment.displayName,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            fontSize = 12.sp
        )
        Text(
            text = formatDuration(segment.durationMs),
            style = MaterialTheme.typography.labelSmall,
            color = color.copy(alpha = 0.8f),
            fontSize = 11.sp
        )
    }
}

@Composable
private fun ActionCountTag(count: Int) {
    val actionColor = Color(0xFFFF9800) // Orange for actions
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(actionColor.copy(alpha = 0.15f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = stringResource(R.string.action_count_tag, count),
            style = MaterialTheme.typography.labelSmall,
            color = actionColor,
            fontWeight = FontWeight.Medium,
            fontSize = 10.sp
        )
    }
}

@Composable
private fun ActionSubItem(action: ActionExecution) {
    val actionColor = getActionColor(action.actionType)
    val actionLabel = getActionLabel(action.actionType)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Diamond marker - same size/gap as SegmentSubItem for left alignment
        Box(
            modifier = Modifier
                .size(subItemMarkerSize)
                .clip(androidx.compose.foundation.shape.GenericShape { size, _ ->
                    moveTo(size.width / 2, 0f)
                    lineTo(size.width, size.height / 2)
                    lineTo(size.width / 2, size.height)
                    lineTo(0f, size.height / 2)
                    close()
                })
                .background(actionColor)
        )
        Spacer(Modifier.width(subItemMarkerGap))

        Column(modifier = Modifier.weight(1f)) {
            // Action type badge + time
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(3.dp))
                        .background(actionColor.copy(alpha = 0.15f))
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = actionLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = actionColor,
                        fontSize = 10.sp
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    text = formatTime(action.timestamp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp
                )
            }

            // Condition description (truncated to 20 chars)
            action.conditionDescription?.let { condition ->
                if (condition.isNotBlank()) {
                    val truncatedCondition = if (condition.length > 20) {
                        condition.take(20) + "..."
                    } else {
                        condition
                    }
                    Spacer(Modifier.height(1.dp))
                    Text(
                        text = truncatedCondition,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        fontSize = 11.sp
                    )
                }
            }

            // Action parameter (for REMIND and AUTO_CLICK, truncated)
            action.actionParameter?.let { param ->
                if (param.isNotBlank()) {
                    val displayText = when (action.actionType) {
                        ActionType.REMIND -> {
                            val truncated = if (param.length > 20) param.take(20) + "..." else param
                            stringResource(R.string.action_remind_content_short, truncated)
                        }
                        ActionType.AUTO_CLICK -> stringResource(R.string.action_click_coordinate, param)
                        else -> param
                    }
                    Spacer(Modifier.height(1.dp))
                    Text(
                        text = displayText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        fontSize = 11.sp
                    )
                }
            }

            // Error message if failed
            if (!action.isSuccess && action.errorMessage != null) {
                Spacer(Modifier.height(1.dp))
                Text(
                    text = action.errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 11.sp
                )
            }
        }
    }
}

private fun getActionColor(actionType: ActionType): Color {
    return when (actionType) {
        ActionType.REMIND -> Color(0xFFFF9800)          // Orange
        ActionType.AUTO_BACK -> Color(0xFFE91E63)       // Pink
        ActionType.AUTO_CLICK -> Color(0xFF9C27B0)      // Purple
        ActionType.AUTO_SCROLL_UP -> Color(0xFF00BCD4)  // Cyan
        ActionType.AUTO_SCROLL_DOWN -> Color(0xFF03A9F4) // Light Blue
        ActionType.ASK -> Color(0xFF4CAF50)              // Green
    }
}

@Composable
private fun getActionLabel(actionType: ActionType): String {
    return when (actionType) {
        ActionType.REMIND -> stringResource(R.string.action_remind_label_short)
        ActionType.AUTO_BACK -> stringResource(R.string.action_auto_back_label_short)
        ActionType.AUTO_CLICK -> stringResource(R.string.action_auto_click_label_short)
        ActionType.AUTO_SCROLL_UP -> stringResource(R.string.action_auto_scroll_up_label_short)
        ActionType.AUTO_SCROLL_DOWN -> stringResource(R.string.action_auto_scroll_down_label_short)
        ActionType.ASK -> stringResource(R.string.action_ask_label_short)
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

private fun dayRange(offsetDays: Int): Pair<Long, Long> {
    val cal = Calendar.getInstance()
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    cal.add(Calendar.DAY_OF_YEAR, offsetDays)
    val start = cal.timeInMillis
    return Pair(start, start + 24 * 60 * 60 * 1000L - 1)
}

private fun weekRange(): Pair<Long, Long> {
    val cal = Calendar.getInstance()
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    // Go to Monday of this week (Calendar.MONDAY = 2)
    val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
    val daysSinceMonday = (dayOfWeek + 5) % 7 // Convert: Sun=0, Mon=1... to Mon=0, Tue=1...
    cal.add(Calendar.DAY_OF_YEAR, -daysSinceMonday)
    val start = cal.timeInMillis
    val end = System.currentTimeMillis() // Up to now, not end of Sunday
    return Pair(start, end)
}

private fun monthRange(): Pair<Long, Long> {
    val cal = Calendar.getInstance()
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    cal.set(Calendar.DAY_OF_MONTH, 1)
    val start = cal.timeInMillis
    val end = System.currentTimeMillis() // Up to now
    return Pair(start, end)
}

/**
 * Get the last N days ranges.
 * Returns pairs of (timestamp range, label generator index).
 * Label is generated in composable context where we can access stringResource.
 */
private fun lastNDaysRanges(n: Int): List<Pair<Pair<Long, Long>, Int>> {
    val result = mutableListOf<Pair<Pair<Long, Long>, Int>>()
    val cal = Calendar.getInstance()

    for (i in (n - 1) downTo 0) { // Oldest first
        cal.timeInMillis = System.currentTimeMillis()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.add(Calendar.DAY_OF_YEAR, -i)

        val start = cal.timeInMillis
        val end = if (i == 0) System.currentTimeMillis() else start + 24 * 60 * 60 * 1000L - 1
        // 0 = today, 1 = yesterday, 2+ = other days
        result.add(Pair(start, end) to i)
    }
    return result
}

@Composable
private fun getDayLabel(dayIndex: Int, startMs: Long): String {
    return when (dayIndex) {
        0 -> stringResource(R.string.label_tab_today)
        1 -> stringResource(R.string.label_tab_yesterday)
        else -> {
            val context = LocalContext.current
            val langCode = com.roderickqiu.seenot.utils.LanguageManager.getEffectiveLanguage(context)
            val locale = Locale.forLanguageTag(langCode)
            val sdf = SimpleDateFormat("MM-dd", locale)
            sdf.format(Date(startMs))
        }
    }
}

fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}

private fun formatTime(ms: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
