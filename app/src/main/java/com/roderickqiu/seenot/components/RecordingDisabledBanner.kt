package com.roderickqiu.seenot.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.roderickqiu.seenot.R
import com.roderickqiu.seenot.service.AIServiceUtils

@Composable
fun RecordingDisabledBanner(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var isRecordingEnabled by remember { mutableStateOf(false) }

    // Check if recording is enabled
    isRecordingEnabled = AIServiceUtils.loadEnableRuleRecording(context)

    // Only show banner if recording is disabled
    if (!isRecordingEnabled) {
        val colorScheme = MaterialTheme.colorScheme
        Surface(
            modifier = modifier
                .fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = colorScheme.primaryContainer
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Text(
                    text = context.getString(R.string.rule_recording_disabled),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    color = colorScheme.onPrimaryContainer
                )
                Text(
                    text = context.getString(R.string.rule_recording_disabled_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}