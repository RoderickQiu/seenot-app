package com.seenot.app.ui.screens

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.seenot.app.data.model.ConstraintType
import com.seenot.app.data.model.RuleRecord
import com.seenot.app.data.repository.RuleRecordRepository
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun HomeTimelineSection(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val repository = remember { RuleRecordRepository(context) }

    // 0 = today, -1 = yesterday. Positive values are not allowed.
    var dayOffset by remember { mutableIntStateOf(0) }
    val dayRange = remember(dayOffset) { getDayRange(dayOffset) }

    val recordsFlow = remember(dayRange.first, dayRange.second) {
        repository.getRecordsInRangeFlow(dayRange.first, dayRange.second)
    }
    val records by recordsFlow.collectAsState(initial = emptyList())
    val events = remember(records) { buildTimelineEvents(records) }

    Column(
        modifier = modifier.fillMaxWidth()
    ) {
        TimelineHeader(
            dayOffset = dayOffset,
            onPrevDay = { dayOffset -= 1 },
            onNextDay = { dayOffset += 1 }
        )

        Spacer(modifier = Modifier.height(12.dp))

        if (events.isEmpty()) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Text(
                    text = if (dayOffset == 0) {
                        "今天还没有记录。进入受控 App 后，系统会在这里展示违规与计时事件。"
                    } else {
                        "这一天还没有记录。"
                    },
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return
        }

        val visible = events.asReversed().take(80)
        val stacked = remember(visible) { stackConsecutiveSameDisplayEvents(visible) }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            stacked.forEach { stack ->
                TimelineEventStackRow(
                    stack = stack,
                    appLabel = remember(stack.events) {
                        resolveStackAppLabel(context, stack)
                    }
                )
            }
        }
    }
}

@Composable
private fun TimelineHeader(
    dayOffset: Int,
    onPrevDay: () -> Unit,
    onNextDay: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = dayTitle(dayOffset),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )

        IconButton(
            onClick = onPrevDay
        ) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = "前一天"
            )
        }

        IconButton(
            onClick = onNextDay,
            enabled = dayOffset < 0
        ) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "后一天"
            )
        }
    }
}

private fun dayTitle(dayOffset: Int): String {
    if (dayOffset == 0) return "今日时间轴"
    val cal = Calendar.getInstance()
    cal.add(Calendar.DAY_OF_MONTH, dayOffset)
    val sdf = SimpleDateFormat("M月d日时间轴", Locale.getDefault())
    return sdf.format(cal.time)
}

private fun getDayRange(dayOffset: Int): Pair<Long, Long> {
    val cal = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_MONTH, dayOffset)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val start = cal.timeInMillis
    cal.add(Calendar.DAY_OF_MONTH, 1)
    val end = cal.timeInMillis - 1
    return start to end
}

private fun resolveAppLabel(context: Context, packageName: String?, fallback: String?): String {
    val pm = context.packageManager
    val pkg = packageName?.takeIf { it.isNotBlank() } ?: return fallback ?: "未知应用"
    return try {
        val ai = pm.getApplicationInfo(pkg, 0)
        pm.getApplicationLabel(ai).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        fallback ?: pkg
    } catch (_: Throwable) {
        fallback ?: pkg
    }
}

private sealed class TimelineEvent {
    abstract val key: String
    abstract val timestamp: Long
    abstract val packageName: String?
    abstract val appName: String?
    abstract val title: String
    abstract val subtitle: String?

    data class ViolationStarted(
        override val key: String,
        override val timestamp: Long,
        override val packageName: String?,
        override val appName: String?,
        override val title: String,
        override val subtitle: String?
    ) : TimelineEvent()

    data class TimeCapRange(
        override val key: String,
        override val timestamp: Long,
        override val packageName: String?,
        override val appName: String?,
        override val title: String,
        override val subtitle: String?
    ) : TimelineEvent()

    data class ActionTaken(
        override val key: String,
        override val timestamp: Long,
        override val packageName: String?,
        override val appName: String?,
        override val title: String,
        override val subtitle: String?
    ) : TimelineEvent()
}

private data class TimelineEventStack(
    val events: List<TimelineEvent>
) {
    val latestEvent: TimelineEvent = events.maxByOrNull { it.timestamp } ?: events.first()
    val packageName: String? get() = latestEvent.packageName
    val appName: String? get() = latestEvent.appName
}

private fun resolveStackAppLabel(context: Context, stack: TimelineEventStack): String {
    val appKeys = stack.events.map { (it.packageName ?: "") to (it.appName ?: "") }.distinct()
    if (appKeys.size > 1) return "多应用"
    val event = stack.latestEvent
    return resolveAppLabel(context, event.packageName, event.appName)
}

private data class StateKey(val type: ConstraintType, val key: String)
private data class Interval(
    val startTs: Long,
    val endTs: Long
)
private data class ActionKey(
    val packageName: String?,
    val constraintKey: String
)

private fun buildTimelineEvents(records: List<RuleRecord>): List<TimelineEvent> {
    if (records.isEmpty()) return emptyList()

    val sorted = records.sortedBy { it.timestamp }
    val out = ArrayList<TimelineEvent>()

    out.addAll(buildActionEvents(sorted))
    out.addAll(buildDenyTransitionEvents(sorted))
    out.addAll(buildTimeCapRangeEvents(sorted))

    return out.sortedBy { it.timestamp }
}

private fun buildActionEvents(records: List<RuleRecord>): List<TimelineEvent> {
    return records.mapNotNull { r ->
        if (r.actionType.isNullOrBlank()) return@mapNotNull null

        val ts = r.actionTimestamp ?: r.timestamp
        TimelineEvent.ActionTaken(
            key = "${r.id}:action",
            timestamp = ts,
            packageName = r.packageName,
            appName = r.appName,
            title = localizeActionTitle(r.actionType, r.actionReason),
            subtitle = r.constraintContent?.takeIf { it.isNotBlank() }
        )
    }
}

private fun buildDenyTransitionEvents(records: List<RuleRecord>): List<TimelineEvent> {
    val denyRecords = records.filter { it.actionType == null && it.constraintType == ConstraintType.DENY }
    val lastState = mutableMapOf<StateKey, Boolean>()
    val violationActionTimes = buildViolationActionTimes(records)
    val out = ArrayList<TimelineEvent>()

    for (r in denyRecords) {
        val stateKey = StateKey(
            type = ConstraintType.DENY,
            key = (r.constraintId?.toString()?.takeIf { it.isNotBlank() })
                ?: (r.constraintContent?.takeIf { it.isNotBlank() })
                ?: "unknown"
        )
        val prev = lastState[stateKey]
        val curr = r.isConditionMatched
        if (prev == null) {
            lastState[stateKey] = curr
            continue
        }

        if (prev && !curr) {
            if (!hasNearbyViolationAction(violationActionTimes, r, stateKey.key)) {
                out.add(
                    TimelineEvent.ViolationStarted(
                        key = "${r.id}:deny-start",
                        timestamp = r.timestamp,
                        packageName = r.packageName,
                        appName = r.appName,
                        title = "违规开始",
                        subtitle = r.constraintContent?.takeIf { it.isNotBlank() } ?: r.aiResult?.take(40)
                    )
                )
            }
        }
        lastState[stateKey] = curr
    }

    return out
}

private fun buildViolationActionTimes(records: List<RuleRecord>): Map<ActionKey, List<Long>> {
    val out = mutableMapOf<ActionKey, MutableList<Long>>()
    for (r in records) {
        if (r.actionType.isNullOrBlank() || r.actionReason != "violation") continue
        if (r.constraintType != ConstraintType.DENY) continue
        val constraintKey = (r.constraintId?.toString()?.takeIf { it.isNotBlank() })
            ?: (r.constraintContent?.takeIf { it.isNotBlank() })
            ?: "unknown"
        val key = ActionKey(r.packageName, constraintKey)
        val ts = r.actionTimestamp ?: r.timestamp
        out.getOrPut(key) { mutableListOf() }.add(ts)
    }
    return out.mapValues { it.value.sorted() }
}

private fun hasNearbyViolationAction(
    actionTimes: Map<ActionKey, List<Long>>,
    record: RuleRecord,
    constraintKey: String
): Boolean {
    val key = ActionKey(record.packageName, constraintKey)
    val times = actionTimes[key] ?: return false
    val ts = record.timestamp
    val windowMs = 5_000L
    return times.any { kotlin.math.abs(it - ts) <= windowMs }
}

private fun buildTimeCapRangeEvents(records: List<RuleRecord>): List<TimelineEvent> {
    val timeRecords = records.filter { it.actionType == null && it.constraintType == ConstraintType.TIME_CAP }
    if (timeRecords.isEmpty()) return emptyList()

    val recordsByConstraint = timeRecords.groupBy { record ->
        (record.constraintId?.toString()?.takeIf { it.isNotBlank() })
            ?: (record.constraintContent?.takeIf { it.isNotBlank() })
            ?: "unknown"
    }

    val out = ArrayList<TimelineEvent>()
    for ((constraintKey, list) in recordsByConstraint) {
        val sorted = list.sortedBy { it.timestamp }
        val rawIntervals = buildRawTimeIntervals(sorted)
        val mergedIntervals = mergeNearbyIntervals(rawIntervals, maxGapMs = 90_000L)
        val subtitle = sorted.firstOrNull()?.constraintContent?.takeIf { it.isNotBlank() }
        val sample = sorted.lastOrNull()

        for ((idx, interval) in mergedIntervals.withIndex()) {
            val title = "${formatTime(interval.startTs)} - ${formatTime(interval.endTs)} 在计时"
            out.add(
                TimelineEvent.TimeCapRange(
                    key = "${sample?.id ?: constraintKey}:time-range:$idx",
                    timestamp = interval.endTs,
                    packageName = sample?.packageName,
                    appName = sample?.appName,
                    title = title,
                    subtitle = subtitle
                )
            )
        }
    }

    return out
}

private fun buildRawTimeIntervals(records: List<RuleRecord>): List<Interval> {
    if (records.isEmpty()) return emptyList()

    var prevState: Boolean? = null
    var currentStart: Long? = null
    val out = ArrayList<Interval>()

    for (record in records) {
        val ts = record.timestamp
        val currentState = record.isConditionMatched // true = timing, false = not timing

        if (currentState && prevState != true) {
            currentStart = ts
        }
        if (!currentState && prevState == true) {
            val start = currentStart
            if (start != null && ts >= start) {
                out.add(Interval(startTs = start, endTs = ts))
            }
            currentStart = null
        }

        prevState = currentState
    }

    val lastTs = records.last().timestamp
    if (prevState == true && currentStart != null) {
        out.add(Interval(startTs = currentStart, endTs = lastTs))
    }

    return out
}

private fun mergeNearbyIntervals(intervals: List<Interval>, maxGapMs: Long): List<Interval> {
    if (intervals.isEmpty()) return emptyList()
    val sorted = intervals.sortedBy { it.startTs }
    val merged = ArrayList<Interval>()
    var current = sorted.first()

    for (next in sorted.drop(1)) {
        val gap = next.startTs - current.endTs
        if (gap <= maxGapMs) {
            current = Interval(
                startTs = current.startTs,
                endTs = maxOf(current.endTs, next.endTs)
            )
        } else {
            merged.add(current)
            current = next
        }
    }
    merged.add(current)
    return merged
}

private fun localizeActionTitle(actionType: String?, actionReason: String?): String {
    val type = actionType.orEmpty().uppercase(Locale.ROOT)
    return when (type) {
        "TOAST" -> when (actionReason) {
            "timeout" -> "时间到提醒"
            "gentle_confirmed_return" -> "用户确认后返回"
            else -> "违规提醒"
        }
        "AUTO_BACK" -> when (actionReason) {
            "timeout" -> "时间到，自动返回"
            "gentle_confirmed_return" -> "你选择了回到正事"
            else -> "检测到违规，自动返回"
        }
        "GO_HOME" -> when (actionReason) {
            "timeout" -> "时间到，返回主屏幕"
            "gentle_confirmed_return" -> "你选择了结束当前内容"
            else -> "严重违规，返回主屏幕"
        }
        "HUD_HIGHLIGHT" -> "高亮警告"
        "VIBRATE" -> "震动提醒"
        else -> when (actionReason) {
            "timeout" -> "时间到动作"
            "gentle_confirmed_return" -> "用户确认动作"
            "violation" -> "违规动作"
            else -> "系统动作"
        }
    }
}

@Composable
private fun TimelineEventStackRow(
    stack: TimelineEventStack,
    appLabel: String,
    modifier: Modifier = Modifier
) {
    if (stack.events.size == 1) {
        TimelineEventRow(
            event = stack.events.first(),
            appLabel = appLabel,
            modifier = modifier
        )
        return
    }

    val latestEvent = stack.latestEvent
    val (icon, color) = remember(latestEvent) { iconAndColor(latestEvent) }
    val primaryLines = remember(stack.events) {
        stack.events
            .asReversed()
            .map { it.subtitle?.takeIf { subtitle -> subtitle.isNotBlank() } ?: it.title }
            .distinct()
    }
    val hiddenCount = (primaryLines.size - 3).coerceAtLeast(0)
    val shownLines = primaryLines.take(3)
    val metaText = "${formatTime(latestEvent.timestamp)} · $appLabel · 共${stack.events.size}条"

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            RowTopLine(
                icon = icon,
                iconColor = color,
                content = {
                    shownLines.forEach { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (hiddenCount > 0) {
                        Text(
                            text = "另外 $hiddenCount 条",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = metaText,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            )
        }
    }
}

@Composable
private fun RowTopLine(
    icon: ImageVector,
    iconColor: Color,
    content: @Composable () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconColor,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.size(12.dp))
        Column(
            modifier = Modifier.weight(1f)
        ) {
            content()
        }
    }
}

@Composable
private fun TimelineEventRow(
    event: TimelineEvent,
    appLabel: String,
    modifier: Modifier = Modifier
) {
    val (icon, color) = remember(event) { iconAndColor(event) }
    val primaryText = event.subtitle?.takeIf { it.isNotBlank() } ?: event.title
    val metaText = "${formatTime(event.timestamp)} · $appLabel · ${event.title}"

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            RowTopLine(
                icon = icon,
                iconColor = color,
                content = {
                    Text(
                        text = primaryText,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = metaText,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            )
        }
    }
}

private fun stackConsecutiveSameDisplayEvents(events: List<TimelineEvent>): List<TimelineEventStack> {
    if (events.isEmpty()) return emptyList()

    val stacks = ArrayList<TimelineEventStack>()
    var current = mutableListOf(events.first())

    for (event in events.drop(1)) {
        val last = current.last()
        val sameApp = last.packageName == event.packageName && last.appName == event.appName
        val sameTitle = last.title == event.title
        val sameSubtitle = last.subtitle.orEmpty() == event.subtitle.orEmpty()
        if (sameApp && sameTitle && sameSubtitle) {
            current.add(event)
        } else {
            stacks.add(TimelineEventStack(events = current))
            current = mutableListOf(event)
        }
    }
    stacks.add(TimelineEventStack(events = current))
    return stacks
}

private fun iconAndColor(event: TimelineEvent): Pair<ImageVector, Color> {
    return when (event) {
        is TimelineEvent.ViolationStarted -> Icons.Default.Warning to Color(0xFFD32F2F)
        is TimelineEvent.TimeCapRange -> Icons.Default.Timer to Color(0xFF1976D2)
        is TimelineEvent.ActionTaken -> Icons.Default.Bolt to Color(0xFF6A1B9A)
    }
}

private fun formatTime(ts: Long): String {
    val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    return sdf.format(Date(ts))
}
