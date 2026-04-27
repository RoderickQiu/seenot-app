package com.seenot.app.ui.screens

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.activity.compose.BackHandler
import androidx.compose.ui.graphics.Color
import com.seenot.app.R
import com.seenot.app.data.model.AppHintScopeType
import com.seenot.app.data.model.ConstraintType
import com.seenot.app.data.model.RuleRecord
import com.seenot.app.data.repository.RuleRecordRepository
import com.seenot.app.domain.SessionManager
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Filter options for rule records
 */
enum class RecordFilter {
    ALL,
    MARKED,
    MATCHED,
    NOT_MATCHED
}

private data class RecordStatusPresentation(
    val label: String,
    val text: String,
    val accentColor: Color,
    val containerColor: Color
)

private data class RecordAppOption(
    val key: String,
    val label: String,
    val count: Int
)

@Composable
private fun rememberRecordStatus(record: RuleRecord): RecordStatusPresentation {
    return when (record.constraintType) {
        ConstraintType.TIME_CAP -> {
            if (record.isConditionMatched) {
                RecordStatusPresentation(
                    label = stringResource(R.string.record_timing_status),
                    text = stringResource(R.string.record_timing_active),
                    accentColor = MaterialTheme.colorScheme.tertiary,
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f)
                )
            } else {
                RecordStatusPresentation(
                    label = stringResource(R.string.record_timing_status),
                    text = stringResource(R.string.record_timing_inactive),
                    accentColor = MaterialTheme.colorScheme.outline,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            }
        }

        ConstraintType.NO_MONITOR -> RecordStatusPresentation(
            label = stringResource(R.string.constraint_type_no_monitor),
            text = stringResource(R.string.hud_status_no_monitor),
            accentColor = MaterialTheme.colorScheme.outline,
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )

        ConstraintType.DENY -> {
            if (record.isConditionMatched) {
                RecordStatusPresentation(
                    label = stringResource(R.string.record_match_status),
                    text = stringResource(R.string.record_match_normal),
                    accentColor = MaterialTheme.colorScheme.primary,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            } else {
                RecordStatusPresentation(
                    label = stringResource(R.string.record_match_status),
                    text = stringResource(R.string.record_match_violated),
                    accentColor = MaterialTheme.colorScheme.error,
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                )
            }
        }

        null -> RecordStatusPresentation(
            label = stringResource(R.string.record_match_status),
            text = stringResource(R.string.record_status_unknown),
            accentColor = MaterialTheme.colorScheme.outline,
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    }
}

private fun RuleRecord.needsAttention(): Boolean {
    return when (constraintType) {
        ConstraintType.TIME_CAP -> isConditionMatched
        ConstraintType.NO_MONITOR -> false
        ConstraintType.DENY -> !isConditionMatched
        null -> false
    }
}

/**
 * Rule Records Page - displays list of recorded rule evaluations
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun RuleRecordsPage(
    repository: RuleRecordRepository,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var records by remember { mutableStateOf<List<RuleRecord>>(emptyList()) }
    var filteredRecords by remember { mutableStateOf<List<RuleRecord>>(emptyList()) }
    var selectedFilter by remember { mutableStateOf(RecordFilter.ALL) }
    var selectedRecord by remember { mutableStateOf<RuleRecord?>(null) }
    var selectedRecordIndex by remember { mutableStateOf(-1) }

    // Multi-select state
    var isMultiSelectMode by remember { mutableStateOf(false) }
    var selectedRecords by remember { mutableStateOf(setOf<String>()) }  // Set of record IDs

    // Hint dialog state
    var showHintDialog by remember { mutableStateOf(false) }
    var hintDialogRecord by remember { mutableStateOf<RuleRecord?>(null) }
    var hintDialogText by remember { mutableStateOf("") }
    var generatedHintDraft by remember { mutableStateOf("") }
    var generatedHintScopeType by remember { mutableStateOf(AppHintScopeType.INTENT_SPECIFIC) }
    var generatedHintScopeLabel by remember { mutableStateOf("") }  // Set to stringResource(R.string.record_intent_scope_label) when dialog opens
    var isGeneratingHint by remember { mutableStateOf(false) }
    var hintGenerationAttempted by remember { mutableStateOf(false) }
    val sessionManager = remember { SessionManager.getInstance(context) }

    // Handle back press
    BackHandler(enabled = true) {
        if (isMultiSelectMode) {
            // Exit multi-select mode first
            isMultiSelectMode = false
            selectedRecords = emptySet()
        } else {
            onBack()
        }
    }

    // Date navigation
    var currentDate by remember { mutableStateOf(Calendar.getInstance()) }
    var showDatePicker by remember { mutableStateOf(false) }

    // Export state: when non-null, export dialog exports this list (from multi-select)
    var recordsToExport by remember { mutableStateOf<List<RuleRecord>?>(null) }
    var showExportDialog by remember { mutableStateOf(false) }
    var isExporting by remember { mutableStateOf(false) }
    var exportProgress by remember { mutableStateOf("") }
    val recordExporter = remember { RecordExporter(context) }

    fun applyFilter(allRecords: List<RuleRecord>, filter: RecordFilter): List<RuleRecord> {
        return when (filter) {
            RecordFilter.ALL -> allRecords
            RecordFilter.MARKED -> allRecords.filter { it.isMarked }
            RecordFilter.MATCHED -> allRecords.filter { it.needsAttention() }
            RecordFilter.NOT_MATCHED -> allRecords.filter { !it.needsAttention() }
        }
    }

    // Load records
    LaunchedEffect(currentDate, selectedFilter) {
        try {
            scope.launch {
                val year = currentDate.get(Calendar.YEAR)
                val month = currentDate.get(Calendar.MONTH)
                val day = currentDate.get(Calendar.DAY_OF_MONTH)
                records = repository.getRecordsForDate(year, month, day)
                filteredRecords = applyFilter(records, selectedFilter)
            }
        } catch (e: Exception) {
            android.util.Log.e("RuleRecordsPage", "Error loading records", e)
        }
    }

    // Main content
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.record_page_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.record_back))
                    }
                },
                actions = {
                    IconButton(onClick = { showDatePicker = true }) {
                        Icon(Icons.Filled.DateRange, contentDescription = stringResource(R.string.record_select_date))
                    }
                    IconButton(
                        onClick = {
                            recordsToExport = null
                            showExportDialog = true
                        },
                        enabled = records.isNotEmpty()
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.record_export))
                    }
                }
            )
        },
        bottomBar = {
            if (isMultiSelectMode && selectedRecords.isNotEmpty()) {
                MultiSelectActionBar(
                    selectedCount = selectedRecords.size,
                    onDelete = {
                        scope.launch {
                            selectedRecords.forEach { id ->
                                repository.deleteRecord(id)
                            }
                            selectedRecords = emptySet()
                            isMultiSelectMode = false
                            val year = currentDate.get(Calendar.YEAR)
                            val month = currentDate.get(Calendar.MONTH)
                            val day = currentDate.get(Calendar.DAY_OF_MONTH)
                            records = repository.getRecordsForDate(year, month, day)
                            filteredRecords = applyFilter(records, selectedFilter)
                        }
                    },
                    onExport = {
                        recordsToExport = filteredRecords.filter { it.id in selectedRecords }
                        showExportDialog = true
                        isMultiSelectMode = false
                        selectedRecords = emptySet()
                    },
                    onCancel = {
                        isMultiSelectMode = false
                        selectedRecords = emptySet()
                    }
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Date navigation
            DateNavigationBar(
                currentDate = currentDate,
                onPreviousDay = {
                    currentDate = (currentDate.clone() as Calendar).apply {
                        add(Calendar.DAY_OF_MONTH, -1)
                    }
                },
                onNextDay = {
                    currentDate = (currentDate.clone() as Calendar).apply {
                        add(Calendar.DAY_OF_MONTH, 1)
                    }
                }
            )

            // Filter chips
            val allCount = records.size
            val markedCount = records.count { it.isMarked }
            val matchedCount = records.count { it.needsAttention() }
            val notMatchedCount = records.count { !it.needsAttention() }
            FilterChipsRow(
                selectedFilter = selectedFilter,
                onFilterSelected = { selectedFilter = it },
                counts = mapOf(
                    RecordFilter.ALL to allCount,
                    RecordFilter.MARKED to markedCount,
                    RecordFilter.MATCHED to matchedCount,
                    RecordFilter.NOT_MATCHED to notMatchedCount
                )
            )

            // Records list
            if (filteredRecords.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Filled.History,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Text(
                            text = stringResource(R.string.record_no_records),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    itemsIndexed(filteredRecords, key = { _, record -> record.id }) { index, record ->
                        RecordItem(
                            record = record,
                            isSelected = selectedRecords.contains(record.id),
                            isMultiSelectMode = isMultiSelectMode,
                            onClick = {
                                if (isMultiSelectMode) {
                                    selectedRecords = if (selectedRecords.contains(record.id)) {
                                        selectedRecords - record.id
                                    } else {
                                        selectedRecords + record.id
                                    }
                                } else {
                                    selectedRecord = record
                                    selectedRecordIndex = index
                                }
                            },
                            onLongClick = {
                                isMultiSelectMode = true
                                selectedRecords = setOf(record.id)
                            },
                            onToggleMark = {
                                if (!record.isMarked) {
                                    hintDialogRecord = record
                                    hintDialogText = ""
                                    showHintDialog = true
                                } else {
                                    scope.launch {
                                        repository.markRecord(record.id, false)
                                        val year = currentDate.get(Calendar.YEAR)
                                        val month = currentDate.get(Calendar.MONTH)
                                        val day = currentDate.get(Calendar.DAY_OF_MONTH)
                                        records = repository.getRecordsForDate(year, month, day)
                                        filteredRecords = applyFilter(records, selectedFilter)
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    // Record detail dialog
    selectedRecord?.let { record ->
        RecordDetailDialog(
            record = record,
            repository = repository,
            records = filteredRecords,
            currentIndex = selectedRecordIndex,
            onNavigateToRecord = { index ->
                selectedRecord = filteredRecords.getOrNull(index)
                selectedRecordIndex = index
            },
            onDismiss = {
                selectedRecord = null
                // Refresh list after delete
                scope.launch {
                    val year = currentDate.get(Calendar.YEAR)
                    val month = currentDate.get(Calendar.MONTH)
                    val day = currentDate.get(Calendar.DAY_OF_MONTH)
                    records = repository.getRecordsForDate(year, month, day)
                    filteredRecords = applyFilter(records, selectedFilter)
                }
            },
            onShowHintDialog = { rec ->
                hintDialogRecord = rec
                hintDialogText = ""
                showHintDialog = true
            }
        )
    }

    // Date picker dialog
    if (showDatePicker) {
        RuleRecordsDatePickerDialog(
            onDismiss = { showDatePicker = false },
            onDateSelected = { calendar ->
                currentDate = calendar
                showDatePicker = false
            },
            initialDate = currentDate
        )
    }

    // Export dialog
    if (showExportDialog) {
        val recordsForDialog = recordsToExport ?: filteredRecords
        val exportPreparingStr = stringResource(R.string.record_export_preparing)
        val exportFailStr = stringResource(R.string.record_export_fail)
        ExportRecordsDialog(
            records = recordsForDialog,
            exportFromSelection = recordsToExport != null,
            isExporting = isExporting,
            exportProgress = exportProgress,
            onDismiss = {
                if (!isExporting) {
                    recordsToExport = null
                    showExportDialog = false
                }
            },
            onExport = { list ->
                scope.launch {
                    isExporting = true
                    exportProgress = exportPreparingStr
                    try {
                        val exportUri = recordExporter.exportRecordsToZip(list) { progress ->
                            exportProgress = progress
                        }

                        if (exportUri != null) {
                            recordExporter.shareExportedFile(exportUri) { error ->
                                Toast.makeText(context, error, Toast.LENGTH_LONG).show()
                            }
                        } else {
                            Toast.makeText(context, exportFailStr, Toast.LENGTH_LONG).show()
                        }
                    } catch (e: Exception) {
                        Toast.makeText(context, exportFailStr + ": ${e.message}", Toast.LENGTH_LONG).show()
                    }
                    isExporting = false
                    exportProgress = ""
                    recordsToExport = null
                    showExportDialog = false
                }
            }
        )
    }

    // Hint input dialog for misclassification feedback
    if (showHintDialog && hintDialogRecord != null) {
        val dialogRecord = hintDialogRecord!!
        AlertDialog(
            onDismissRequest = {
                if (isGeneratingHint) return@AlertDialog
                showHintDialog = false
                hintDialogRecord = null
                hintDialogText = ""
                generatedHintDraft = ""
                generatedHintScopeType = AppHintScopeType.INTENT_SPECIFIC
                generatedHintScopeLabel = ""  // Will be set when dialog opens
                isGeneratingHint = false
                hintGenerationAttempted = false
            },
            title = { Text(stringResource(R.string.record_generate_title)) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.record_marked_false_positive, dialogRecord.constraintContent?.take(30) ?: ""),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.record_generate_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = hintDialogText,
                        onValueChange = { hintDialogText = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(stringResource(R.string.record_hint_placeholder_optional)) },
                        minLines = 2,
                        maxLines = 4
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    if (isGeneratingHint) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text(
                                text = stringResource(R.string.record_generating_draft),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else if (hintGenerationAttempted) {
                        Text(
                            text = stringResource(R.string.record_scope_suggestion, generatedHintScopeLabel),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = generatedHintDraft,
                            onValueChange = { generatedHintDraft = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text(stringResource(R.string.record_hint_edit_placeholder)) },
                            minLines = 3,
                            maxLines = 6
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (!hintGenerationAttempted) {
                            isGeneratingHint = true
                            sessionManager.previewFalsePositiveRule(
                                record = dialogRecord,
                                userNote = hintDialogText.takeIf { it.isNotBlank() }
                            ) { result ->
                                isGeneratingHint = false
                                hintGenerationAttempted = true
                                if (result.generatedRule != null) {
                                    generatedHintDraft = result.generatedRule
                                }
                                generatedHintScopeType = result.generatedScopeType
                                generatedHintScopeLabel = result.generatedScopeLabel
                                Toast.makeText(context, result.userMessage, Toast.LENGTH_SHORT).show()
                            }
                            return@Button
                        }

                        sessionManager.markRecordAsWrong(
                            record = dialogRecord,
                            userNote = hintDialogText.takeIf { it.isNotBlank() },
                            confirmedRule = generatedHintDraft.takeIf { it.isNotBlank() },
                            confirmedScopeType = generatedHintScopeType,
                            source = "record_detail"
                        ) { result ->
                            Toast.makeText(context, result.userMessage, Toast.LENGTH_SHORT).show()
                            scope.launch {
                                val year = currentDate.get(Calendar.YEAR)
                                val month = currentDate.get(Calendar.MONTH)
                                val day = currentDate.get(Calendar.DAY_OF_MONTH)
                                records = repository.getRecordsForDate(year, month, day)
                                filteredRecords = applyFilter(records, selectedFilter)
                            }
                        }
                        showHintDialog = false
                        hintDialogRecord = null
                        hintDialogText = ""
                        generatedHintDraft = ""
                        generatedHintScopeType = AppHintScopeType.INTENT_SPECIFIC
                        generatedHintScopeLabel = ""  // Reset when dismissed
                        hintGenerationAttempted = false
                    }
                    ,
                    enabled = if (!hintGenerationAttempted) !isGeneratingHint else generatedHintDraft.isNotBlank() && !isGeneratingHint
                ) {
                    Text(if (hintGenerationAttempted) stringResource(R.string.record_save) else stringResource(R.string.record_generate))
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (hintGenerationAttempted) {
                        TextButton(
                            onClick = {
                                isGeneratingHint = true
                                sessionManager.previewFalsePositiveRule(
                                    record = dialogRecord,
                                    userNote = hintDialogText.takeIf { it.isNotBlank() }
                                ) { result ->
                                    isGeneratingHint = false
                                    hintGenerationAttempted = true
                                    if (result.generatedRule != null) {
                                        generatedHintDraft = result.generatedRule
                                    }
                                    generatedHintScopeType = result.generatedScopeType
                                    generatedHintScopeLabel = result.generatedScopeLabel
                                    Toast.makeText(context, result.userMessage, Toast.LENGTH_SHORT).show()
                                }
                            },
                            enabled = !isGeneratingHint
                        ) {
                            Text(stringResource(R.string.record_regenerate))
                        }
                    }
                    TextButton(
                        onClick = {
                            if (isGeneratingHint) return@TextButton
                            showHintDialog = false
                            hintDialogRecord = null
                            hintDialogText = ""
                            generatedHintDraft = ""
                            generatedHintScopeType = AppHintScopeType.INTENT_SPECIFIC
                            generatedHintScopeLabel = ""  // Reset when dismissed
                            hintGenerationAttempted = false
                        }
                    ) {
                        Text(stringResource(R.string.record_cancel))
                    }
                }
            }
        )
    }
}

@Composable
private fun DateNavigationBar(
    currentDate: Calendar,
    onPreviousDay: () -> Unit,
    onNextDay: () -> Unit
) {
    val dateFormat = SimpleDateFormat(stringResource(R.string.record_date_format), Locale.CHINA)
    val isToday = remember(currentDate) {
        val today = Calendar.getInstance()
        currentDate.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
                currentDate.get(Calendar.MONTH) == today.get(Calendar.MONTH) &&
                currentDate.get(Calendar.DAY_OF_MONTH) == today.get(Calendar.DAY_OF_MONTH)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onPreviousDay) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = stringResource(R.string.timeline_prev_day))
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (isToday) stringResource(R.string.record_today) else dateFormat.format(currentDate.time),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
        }

        IconButton(
            onClick = onNextDay,
            enabled = !isToday
        ) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = stringResource(R.string.timeline_next_day),
                tint = if (isToday)
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                else
                    MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun FilterChipsRow(
    selectedFilter: RecordFilter,
    onFilterSelected: (RecordFilter) -> Unit,
    counts: Map<RecordFilter, Int>
) {
    val filters = remember {
        listOf(
            RecordFilter.ALL to "",  // Will be resolved at usage
            RecordFilter.MARKED to "",
            RecordFilter.MATCHED to "",
            RecordFilter.NOT_MATCHED to ""
        )
    }
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(filters) { (filter, _) ->
            val isSelected = selectedFilter == filter
            val label = when (filter) {
                RecordFilter.ALL -> stringResource(R.string.record_filter_all)
                RecordFilter.MARKED -> stringResource(R.string.record_filter_marked)
                RecordFilter.MATCHED -> stringResource(R.string.record_filter_need_attention)
                RecordFilter.NOT_MATCHED -> stringResource(R.string.record_filter_normal)
            }

            FilterChip(
                selected = isSelected,
                onClick = { onFilterSelected(filter) },
                label = {
                    Text("$label (${counts[filter] ?: 0})")
                }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecordItem(
    record: RuleRecord,
    isSelected: Boolean = false,
    isMultiSelectMode: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    onToggleMark: () -> Unit
) {
    val context = LocalContext.current
    val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    val status = rememberRecordStatus(record)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isMultiSelectMode) {
                    // In multi-select mode: regular click
                    Modifier.clickable(onClick = onClick)
                } else {
                    // Not in multi-select mode: detect long-click to enter multi-select
                    Modifier.combinedClickable(
                        onClick = onClick,
                        onLongClick = onLongClick
                    )
                }
            ),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isSelected -> MaterialTheme.colorScheme.primaryContainer
                else -> status.containerColor
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Checkbox for multi-select mode
            if (isMultiSelectMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onClick() }
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Action or judgement indicator
                    if (record.actionType != null) {
                        // Action record - show lightning icon
                        Icon(
                            imageVector = Icons.Filled.FlashOn,
                            contentDescription = stringResource(R.string.record_action_label),
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                    } else {
                        // Judgement record - show status dot
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(status.accentColor)
                        )
                    }
                    Text(
                        text = remember(record.appName, record.packageName, context) {
                            resolveRecordAppLabel(context, record)
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Show action type or constraint info
                if (record.actionType != null) {
                    Text(
                        text = stringResource(R.string.record_action, record.actionType ?: "", record.actionReason ?: "unknown"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    // Constraint info
                    record.constraintType?.let { type ->
                        Text(
                            text = "${type.name}: ${record.constraintContent ?: ""} | ${status.text}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = timeFormat.format(Date(record.timestamp)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    record.confidence?.let { conf ->
                        Text(
                            text = stringResource(R.string.record_confidence, (conf * 100).toInt()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Mark button
            IconButton(onClick = onToggleMark) {
                Icon(
                    imageVector = if (record.isMarked) Icons.Outlined.Star else Icons.Outlined.StarOutline,
                    contentDescription = if (record.isMarked) stringResource(R.string.record_unmark) else stringResource(R.string.record_mark),
                    tint = if (record.isMarked)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecordDetailDialog(
    record: RuleRecord,
    repository: RuleRecordRepository,
    records: List<RuleRecord>,
    currentIndex: Int,
    onNavigateToRecord: (Int) -> Unit,
    onDismiss: () -> Unit,
    onShowHintDialog: (RuleRecord) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var showDeleteConfirm by remember { mutableStateOf(false) }

    val timeFormat = SimpleDateFormat(stringResource(R.string.record_time_format), Locale.getDefault())

    val hasPrevious = currentIndex > 0
    val hasNext = currentIndex < records.size - 1
    val status = rememberRecordStatus(record)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {},
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            if (dragAmount > 50 && hasPrevious) {
                                onNavigateToRecord(currentIndex - 1)
                            } else if (dragAmount < -50 && hasNext) {
                                onNavigateToRecord(currentIndex + 1)
                            }
                        }
                    )
                }
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // Top bar
                TopAppBar(
                    title = {
                        Text(
                            text = "${currentIndex + 1}/${records.size}",
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.record_close))
                        }
                    },
                    actions = {
                        // Previous button
                        IconButton(
                            onClick = { onNavigateToRecord(currentIndex - 1) },
                            enabled = hasPrevious
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                contentDescription = stringResource(R.string.record_prev),
                                tint = if (hasPrevious)
                                    MaterialTheme.colorScheme.onSurface
                                else
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                            )
                        }
                        // Next button
                        IconButton(
                            onClick = { onNavigateToRecord(currentIndex + 1) },
                            enabled = hasNext
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = stringResource(R.string.record_next),
                                tint = if (hasNext)
                                    MaterialTheme.colorScheme.onSurface
                                else
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                            )
                        }
                        IconButton(onClick = {
                            scope.launch {
                                repository.markRecord(record.id, !record.isMarked)
                            }
                        }) {
                            Icon(
                                imageVector = if (record.isMarked)
                                    Icons.Outlined.Star
                                else
                                    Icons.Outlined.StarOutline,
                                contentDescription = stringResource(R.string.record_mark)
                            )
                        }
                        IconButton(onClick = { showDeleteConfirm = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.record_delete_action))
                        }
                    }
                )

                // Content
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Basic info
                    item {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (record.actionType != null) {
                                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                                } else {
                                    status.containerColor
                                }
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (record.actionType != null) stringResource(R.string.record_intervention_action) else stringResource(R.string.record_basic_info),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                DetailRow(
                                    stringResource(R.string.record_app),
                                    resolveRecordAppLabel(context, record)
                                )
                                record.packageName?.let { DetailRow(stringResource(R.string.record_package), it) }
                                DetailRow(stringResource(R.string.record_time), timeFormat.format(Date(record.timestamp)))

                                if (record.actionType != null) {
                                    // Action record
                                    DetailRow(stringResource(R.string.record_action_type), record.actionType)
                                    record.actionReason?.let { DetailRow(stringResource(R.string.record_trigger_reason), it) }
                                } else {
                                    // Judgement record
                                    DetailRow(
                                        status.label,
                                        status.text
                                    )
                                }

                                record.constraintType?.let { type ->
                                    DetailRow(stringResource(R.string.record_constraint_type), type.name)
                                }
                                record.constraintContent?.let { content ->
                                    DetailRow(stringResource(R.string.record_constraint_content), content)
                                }
                            }
                        }
                    }

                    // AI result
                    record.aiResult?.let { result ->
                        item {
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                )
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = stringResource(R.string.record_ai_result),
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (!record.isMarked) {
                                            TextButton(
                                                onClick = {
                                                    onShowHintDialog(record)
                                                }
                                            ) {
                                                Icon(
                                                    Icons.Filled.Warning,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp),
                                                    tint = MaterialTheme.colorScheme.error
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    stringResource(R.string.record_mark_false_positive),
                                                    style = MaterialTheme.typography.labelSmall
                                                )
                                            }
                                        }
                                    }
                                    record.confidence?.let { conf ->
                                        Text(
                                            text = stringResource(R.string.record_ai_confidence, (conf * 100).toInt()),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Text(
                                        text = result,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }
                    }

                    // Screenshot
                    record.imagePath?.let { imagePath ->
                        item {
                            Card(
                                modifier = Modifier.padding(bottom = 16.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                )
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.record_screenshot),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )

                                    val file = File(imagePath)
                                    if (file.exists()) {
                                        val bitmap = remember(imagePath) {
                                            BitmapFactory.decodeFile(imagePath)
                                        }
                                        bitmap?.let {
                                            Image(
                                                bitmap = it.asImageBitmap(),
                                                contentDescription = stringResource(R.string.record_screenshot),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(8.dp)),
                                                contentScale = ContentScale.FillWidth
                                            )
                                        }
                                    } else {
                                        Text(
                                            text = stringResource(R.string.record_screenshot_not_exist),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Processing time
                    record.elapsedTimeMs?.let { time ->
                        item {
                            DetailRow(stringResource(R.string.record_processing_time), "${time}ms")
                        }
                    }
                }
            }
        }
    }

    // Delete confirmation
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.record_confirm_delete)) },
            text = { Text(stringResource(R.string.record_confirm_delete_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            repository.deleteRecord(record.id)
                            showDeleteConfirm = false
                            onDismiss()
                        }
                    }
                ) {
                    Text(stringResource(R.string.record_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.record_cancel))
                }
            }
        )
    }
}

@Composable
private fun MultiSelectActionBar(
    selectedCount: Int,
    onDelete: () -> Unit,
    onExport: () -> Unit,
    onCancel: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .navigationBarsPadding(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.record_items_selected, selectedCount),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = onExport) {
                    Icon(
                        Icons.Filled.Share,
                        contentDescription = stringResource(R.string.record_export_action),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.record_delete_action),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                IconButton(onClick = onCancel) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.record_cancel_action),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RuleRecordsDatePickerDialog(
    onDismiss: () -> Unit,
    onDateSelected: (Calendar) -> Unit,
    initialDate: Calendar
) {
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = initialDate.timeInMillis
    )

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        val calendar = Calendar.getInstance().apply {
                            timeInMillis = millis
                        }
                        onDateSelected(calendar)
                    }
                }
            ) {
                Text(stringResource(R.string.record_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.record_cancel))
            }
        }
    ) {
        DatePicker(state = datePickerState)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExportRecordsDialog(
    records: List<com.seenot.app.data.model.RuleRecord>,
    exportFromSelection: Boolean = false,
    isExporting: Boolean,
    exportProgress: String,
    onDismiss: () -> Unit,
    onExport: (List<com.seenot.app.data.model.RuleRecord>) -> Unit
) {
    var exportMarkedOnly by remember { mutableStateOf(false) }
    var selectedAppKey by remember { mutableStateOf<String?>(null) }
    var appDropdownExpanded by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val uniqueApps = remember(records) {
        records
            .groupBy { recordAppKey(it) }
            .map { (key, appRecords) ->
                RecordAppOption(
                    key = key,
                    label = resolveRecordAppLabel(context, appRecords.first()),
                    count = appRecords.size
                )
            }
            .sortedBy { it.label }
    }

    val filteredRecords = remember(records, selectedAppKey, exportMarkedOnly) {
        records.filter { record ->
            val appMatch = selectedAppKey == null || recordAppKey(record) == selectedAppKey
            val markedMatch = !exportMarkedOnly || record.isMarked
            appMatch && markedMatch
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.record_export_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (isExporting) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                    Text(
                        text = exportProgress,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Text(
                        text = if (exportFromSelection) {
                            stringResource(R.string.record_export_selected, records.size)
                        } else {
                            stringResource(R.string.record_export_filtered, filteredRecords.size)
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )

                    if (!exportFromSelection) {
                        ExposedDropdownMenuBox(
                            expanded = appDropdownExpanded,
                            onExpandedChange = { appDropdownExpanded = it }
                        ) {
                            OutlinedTextField(
                                value = uniqueApps.firstOrNull { it.key == selectedAppKey }?.label
                                    ?: stringResource(R.string.record_all_apps),
                                onValueChange = {},
                                readOnly = true,
                                label = { Text(stringResource(R.string.record_select_app)) },
                                trailingIcon = {
                                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = appDropdownExpanded)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor()
                            )
                            ExposedDropdownMenu(
                                expanded = appDropdownExpanded,
                                onDismissRequest = { appDropdownExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.record_all_apps)) },
                                    onClick = {
                                        selectedAppKey = null
                                        appDropdownExpanded = false
                                    }
                                )
                                uniqueApps.forEach { appOption ->
                                    DropdownMenuItem(
                                        text = { Text("${appOption.label} (${appOption.count})") },
                                        onClick = {
                                            selectedAppKey = appOption.key
                                            appDropdownExpanded = false
                                        }
                                    )
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = exportMarkedOnly,
                                onCheckedChange = { exportMarkedOnly = it },
                                enabled = filteredRecords.any { it.isMarked }
                            )
                            Text(
                                text = stringResource(R.string.record_export_only_marked, filteredRecords.count { it.isMarked }),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (!isExporting) {
                Button(
                    onClick = { onExport(filteredRecords) },
                    enabled = filteredRecords.isNotEmpty()
                ) {
                    Text(stringResource(R.string.record_export_action))
                }
            }
        },
        dismissButton = {
            if (!isExporting) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.record_cancel))
                }
            }
        }
    )
}

private fun recordAppKey(record: RuleRecord): String {
    return record.packageName?.takeIf { it.isNotBlank() } ?: "label:${record.appName}"
}

private fun resolveRecordAppLabel(context: Context, record: RuleRecord): String {
    val packageName = record.packageName?.takeIf { it.isNotBlank() }
    if (packageName == null) {
        return record.appName
    }
    return try {
        val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
        context.packageManager.getApplicationLabel(appInfo).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        record.appName.ifBlank { packageName }
    } catch (_: Throwable) {
        record.appName.ifBlank { packageName }
    }
}
