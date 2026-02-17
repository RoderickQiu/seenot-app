package com.roderickqiu.seenot.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.roderickqiu.seenot.data.ContentLabel
import com.roderickqiu.seenot.data.LabelMergeSuggestion
import com.roderickqiu.seenot.data.LabelNormalizationRepo
import com.roderickqiu.seenot.data.ScreenObservation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun LabelNormPage(
    modifier: Modifier = Modifier,
    repo: LabelNormalizationRepo = LabelNormalizationRepo(LocalContext.current)
) {
    var observations by remember { mutableStateOf<List<ScreenObservation>>(emptyList()) }
    var labels by remember { mutableStateOf<List<ContentLabel>>(emptyList()) }
    var merges by remember { mutableStateOf<List<LabelMergeSuggestion>>(emptyList()) }
    var selectedTab by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            observations = repo.loadObservations()
            labels = repo.loadLabels()
            merges = repo.loadMergeSuggestions()
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("Observations (${observations.size})") }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("Labels (${labels.size})") }
            )
            Tab(
                selected = selectedTab == 2,
                onClick = { selectedTab = 2 },
                text = { Text("Merges (${merges.size})") }
            )
        }
        when (selectedTab) {
            0 -> ObservationsTab(observations = observations)
            1 -> LabelsTab(labels = labels)
            2 -> MergesTab(merges = merges)
        }
    }
}

@Composable
private fun ObservationsTab(observations: List<ScreenObservation>) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp)
    ) {
        observations.forEach { o ->
            Text("id: ${o.observationId}")
            Text("  ts: ${o.timestamp} app: ${o.appName} pkg: ${o.packageName}")
            Text("  rawReason: ${o.rawReason}")
            Text("  labelId: ${o.labelId} normalizedAt: ${o.normalizedAt}")
            Text("  confidence: ${o.confidence} recordIds: ${o.recordIds.size}")
            Text("")
        }
    }
}

@Composable
private fun LabelsTab(labels: List<ContentLabel>) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp)
    ) {
        labels.forEach { l ->
            Text("id: ${l.labelId} displayName: ${l.displayName}")
            Text("  description: ${l.description}")
            Text("  createdAt: ${l.createdAt} createdInBatch: ${l.createdInBatch} mergedInto: ${l.mergedInto}")
            Text("")
        }
    }
}

@Composable
private fun MergesTab(merges: List<LabelMergeSuggestion>) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp)
    ) {
        merges.forEach { m ->
            Text("mergeFrom: ${m.mergeFrom} -> mergeInto: ${m.mergeInto}")
            Text("  reason: ${m.reason} confidence: ${m.confidence}")
            Text("")
        }
    }
}
