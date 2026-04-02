package com.seenot.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.seenot.app.config.RuleRecordingPrefs
import com.seenot.app.data.repository.RuleRecordRepository
import kotlinx.coroutines.launch

/**
 * Settings dialog for rule recording
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleRecordingSettingsDialog(
    repository: RuleRecordRepository,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var enableRecording by remember {
        mutableStateOf(RuleRecordingPrefs.isEnabled(context))
    }
    var screenshotMode by remember {
        mutableStateOf(RuleRecordingPrefs.getScreenshotMode(context))
    }
    var showHomeTimeline by remember {
        mutableStateOf(RuleRecordingPrefs.isHomeTimelineEnabled(context))
    }

    var screenshotDropdownExpanded by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val screenshotModeOptions = listOf(
        RuleRecordingPrefs.ScreenshotMode.ALL to "保存所有截图",
        RuleRecordingPrefs.ScreenshotMode.MATCHED_ONLY to "仅保存匹配时截图",
        RuleRecordingPrefs.ScreenshotMode.NONE to "不保存截图"
    )
    val selectedScreenshotLabel = screenshotModeOptions.find { it.first == screenshotMode }?.second
        ?: screenshotModeOptions.first().second

    // Delete confirmation dialog
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("确认删除") },
            text = { Text("确定要删除所有规则记录吗？此操作无法撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            repository.clearAllRecords()
                        }
                        showDeleteConfirm = false
                    }
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("取消")
                }
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("规则记录") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Enable switch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("启用规则记录")
                        Text(
                            text = "记录每次 AI 屏幕分析的详情",
                            style = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = enableRecording,
                        onCheckedChange = { enableRecording = it }
                    )
                }

                if (enableRecording) {
                    // Screenshot mode dropdown
                    Text(
                        text = "截图保存模式",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Box {
                        OutlinedTextField(
                            value = selectedScreenshotLabel,
                            onValueChange = {},
                            readOnly = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { screenshotDropdownExpanded = true },
                            trailingIcon = {
                                IconButton(onClick = { screenshotDropdownExpanded = !screenshotDropdownExpanded }) {
                                    Icon(
                                        imageVector = if (screenshotDropdownExpanded)
                                            Icons.Filled.KeyboardArrowUp
                                        else
                                            Icons.Filled.KeyboardArrowDown,
                                        contentDescription = null
                                    )
                                }
                            }
                        )
                        DropdownMenu(
                            expanded = screenshotDropdownExpanded,
                            onDismissRequest = { screenshotDropdownExpanded = false },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            screenshotModeOptions.forEach { (mode, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        screenshotMode = mode
                                        screenshotDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    // Storage info
                    Text(
                        text = "截图将保存在应用内部存储中，不会保存到您的图库",
                        style = androidx.compose.ui.text.TextStyle(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                HorizontalDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("显示首页时间轴")
                        Text(
                            text = "关闭后首页不再展示“今日时间轴”模块",
                            style = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = showHomeTimeline,
                        onCheckedChange = { showHomeTimeline = it }
                    )
                }

                // Delete all records button
                HorizontalDivider()

                OutlinedButton(
                    onClick = { showDeleteConfirm = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("删除所有记录")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    RuleRecordingPrefs.setEnabled(context, enableRecording)
                    RuleRecordingPrefs.setScreenshotMode(context, screenshotMode)
                    RuleRecordingPrefs.setHomeTimelineEnabled(context, showHomeTimeline)
                    onDismiss()
                }
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
