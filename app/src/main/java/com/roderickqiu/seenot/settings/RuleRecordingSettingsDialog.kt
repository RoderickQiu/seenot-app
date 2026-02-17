package com.roderickqiu.seenot.settings

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import com.roderickqiu.seenot.R
import com.roderickqiu.seenot.service.AIServiceUtils

private const val AI_PREFS = "seenot_ai"
private const val KEY_ENABLE_RULE_RECORDING = "enable_rule_recording"
private const val KEY_RULE_RECORD_SCREENSHOT_MODE = "rule_record_screenshot_mode"

/** Screenshot save mode: all, matched_only, none */
private fun loadScreenshotMode(context: Context): String {
    return AIServiceUtils.loadRuleRecordScreenshotMode(context)
}

private fun saveRuleRecordingSettings(context: Context, enable: Boolean, screenshotMode: String) {
    context.getSharedPreferences(AI_PREFS, Context.MODE_PRIVATE).edit()
        .putBoolean(KEY_ENABLE_RULE_RECORDING, enable)
        .putString(KEY_RULE_RECORD_SCREENSHOT_MODE, screenshotMode)
        .apply()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleRecordingSettingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current

    val initialEnabled = AIServiceUtils.loadEnableRuleRecording(context)
    val initialMode = loadScreenshotMode(context)

    var enableRecording by remember { mutableStateOf(initialEnabled) }
    var screenshotMode by remember { mutableStateOf(initialMode) }

    var screenshotDropdownExpanded by remember { mutableStateOf(false) }
    var screenshotDropdownSize by remember { mutableStateOf(Size.Zero) }

    val screenshotModeOptions = listOf(
        "all" to context.getString(R.string.rule_record_screenshot_all),
        "matched_only" to context.getString(R.string.rule_record_screenshot_matched_only),
        "none" to context.getString(R.string.rule_record_screenshot_none)
    )
    val selectedScreenshotLabel = screenshotModeOptions.find { it.first == screenshotMode }?.second
        ?: screenshotModeOptions.first().second

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = context.getString(R.string.rule_records)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = context.getString(R.string.enable_rule_recording))
                        Text(
                            text = context.getString(R.string.enable_rule_recording_desc),
                            style = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
                            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = enableRecording,
                        onCheckedChange = { enableRecording = it }
                    )
                }

                if (enableRecording) {
                    Text(
                        text = context.getString(R.string.rule_record_screenshot_save),
                        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                    )
                    Box {
                        OutlinedTextField(
                            value = selectedScreenshotLabel,
                            onValueChange = {},
                            readOnly = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .onGloballyPositioned { coordinates ->
                                    screenshotDropdownSize = coordinates.size.toSize()
                                }
                                .clickable { screenshotDropdownExpanded = true },
                            trailingIcon = {
                                IconButton(onClick = { screenshotDropdownExpanded = !screenshotDropdownExpanded }) {
                                    Icon(
                                        imageVector = if (screenshotDropdownExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                                        contentDescription = null
                                    )
                                }
                            }
                        )
                        DropdownMenu(
                            expanded = screenshotDropdownExpanded,
                            onDismissRequest = { screenshotDropdownExpanded = false },
                            modifier = Modifier.width(
                                with(LocalDensity.current) { screenshotDropdownSize.width.toDp() }
                            )
                        ) {
                            screenshotModeOptions.forEach { (value, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        screenshotMode = value
                                        screenshotDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    saveRuleRecordingSettings(context, enableRecording, screenshotMode)
                    onDismiss()
                }
            ) { Text(text = context.getString(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = context.getString(R.string.cancel)) }
        }
    )
}
