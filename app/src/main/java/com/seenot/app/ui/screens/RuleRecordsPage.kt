package com.seenot.app.ui.screens

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

@Composable
private fun rememberRecordStatus(record: RuleRecord): RecordStatusPresentation {
    return when (record.constraintType) {
        ConstraintType.TIME_CAP -> {
            if (record.isConditionMatched) {
                RecordStatusPresentation(
                    label = "计时状态",
                    text = "正在计时",
                    accentColor = MaterialTheme.colorScheme.tertiary,
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f)
                )
            } else {
                RecordStatusPresentation(
                    label = "计时状态",
                    text = "当前不计时",
                    accentColor = MaterialTheme.colorScheme.outline,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            }
        }

        else -> {
            if (record.isConditionMatched) {
                RecordStatusPresentation(
                    label = "匹配状态",
                    text = "正常 (未违反规则)",
                    accentColor = MaterialTheme.colorScheme.primary,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            } else {
                RecordStatusPresentation(
                    label = "匹配状态",
                    text = "违规 (已违反规则)",
                    accentColor = MaterialTheme.colorScheme.error,
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                )
            }
        }
    }
}

private fun RuleRecord.needsAttention(): Boolean {
    return when (constraintType) {
        ConstraintType.TIME_CAP -> isConditionMatched
        else -> !isConditionMatched
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
    var generatedHintScopeLabel by remember { mutableStateOf("只对这条意图生效") }
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
                title = { Text("规则记录") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { showDatePicker = true }) {
                        Icon(Icons.Filled.DateRange, contentDescription = "选择日期")
                    }
                    IconButton(
                        onClick = {
                            recordsToExport = null
                            showExportDialog = true
                        },
                        enabled = records.isNotEmpty()
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = "导出记录")
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
                            text = "暂无记录",
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
                    exportProgress = "正在准备导出..."
                    try {
                        val exportUri = recordExporter.exportRecordsToZip(list) { progress ->
                            exportProgress = progress
                        }

                        if (exportUri != null) {
                            recordExporter.shareExportedFile(exportUri) { error ->
                                Toast.makeText(context, error, Toast.LENGTH_LONG).show()
                            }
                        } else {
                            Toast.makeText(context, "导出失败", Toast.LENGTH_LONG).show()
                        }
                    } catch (e: Exception) {
                        Toast.makeText(context, "导出失败: ${e.message}", Toast.LENGTH_LONG).show()
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
                generatedHintScopeLabel = "只对这条意图生效"
                isGeneratingHint = false
                hintGenerationAttempted = false
            },
            title = { Text("生成补充规则") },
            text = {
                Column {
                    Text(
                        text = "您标记了 \"${dialogRecord.constraintContent?.take(30)}\" 为误判。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "系统会结合这条记录的截图、当前意图和应用特点，先生成一条草稿，再判断它更适合放在整个 app 通用，还是只对这条意图生效。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = hintDialogText,
                        onValueChange = { hintDialogText = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("可选：例如 我只用QQ聊天，不看QQ空间") },
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
                                text = "正在生成补充规则草稿…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else if (hintGenerationAttempted) {
                        Text(
                            text = "建议放在：$generatedHintScopeLabel",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = generatedHintDraft,
                            onValueChange = { generatedHintDraft = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("可直接修改生成结果，或自己写一条更准确的补充规则") },
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
                        generatedHintScopeLabel = "只对这条意图生效"
                        hintGenerationAttempted = false
                    }
                    ,
                    enabled = if (!hintGenerationAttempted) !isGeneratingHint else generatedHintDraft.isNotBlank() && !isGeneratingHint
                ) {
                    Text(if (hintGenerationAttempted) "保存" else "生成")
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
                            Text("重新生成")
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
                            generatedHintScopeLabel = "只对这条意图生效"
                            hintGenerationAttempted = false
                        }
                    ) {
                        Text("取消")
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
    val dateFormat = SimpleDateFormat("yyyy年MM月dd日 EEEE", Locale.CHINA)
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
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "前一天")
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (isToday) "今天" else dateFormat.format(currentDate.time),
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
                contentDescription = "后一天",
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
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val filters = listOf(
            RecordFilter.ALL to "全部",
            RecordFilter.MARKED to "已标记",
            RecordFilter.MATCHED to "需关注",
            RecordFilter.NOT_MATCHED to "普通"
        )

        items(filters) { (filter, label) ->
            val isSelected = selectedFilter == filter

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
                            contentDescription = "干预动作",
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
                        text = record.appName,
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
                        text = "动作: ${record.actionType} (${record.actionReason ?: "unknown"})",
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
                            text = "置信度: ${(conf * 100).toInt()}%",
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
                    contentDescription = if (record.isMarked) "取消标记" else "标记",
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
    val scope = rememberCoroutineScope()

    var showDeleteConfirm by remember { mutableStateOf(false) }

    val timeFormat = SimpleDateFormat("yyyy年MM月dd日 HH:mm:ss", Locale.getDefault())

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
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("记录详情")
                            if (records.size > 1) {
                                Text(
                                    text = "${currentIndex + 1} / ${records.size}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "关闭")
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
                                contentDescription = "上一条",
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
                                contentDescription = "下一条",
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
                                contentDescription = "标记"
                            )
                        }
                        IconButton(onClick = { showDeleteConfirm = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "删除")
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
                                        text = if (record.actionType != null) "⚡ 干预动作" else "基本信息",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                DetailRow("应用", record.appName)
                                record.packageName?.let { DetailRow("包名", it) }
                                DetailRow("时间", timeFormat.format(Date(record.timestamp)))

                                if (record.actionType != null) {
                                    // Action record
                                    DetailRow("动作类型", record.actionType)
                                    record.actionReason?.let { DetailRow("触发原因", it) }
                                } else {
                                    // Judgement record
                                    DetailRow(
                                        status.label,
                                        status.text
                                    )
                                }

                                record.constraintType?.let { type ->
                                    DetailRow("约束类型", type.name)
                                }
                                record.constraintContent?.let { content ->
                                    DetailRow("约束内容", content)
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
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text(
                                                text = "AI 分析结果",
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.Bold
                                            )
                                            record.confidence?.let { conf ->
                                                Text(
                                                    text = "(置信度: ${(conf * 100).toInt()}%)",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
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
                                                    "标记误判",
                                                    style = MaterialTheme.typography.labelSmall
                                                )
                                            }
                                        }
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
                                        text = "截图",
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
                                                contentDescription = "截图",
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(8.dp)),
                                                contentScale = ContentScale.FillWidth
                                            )
                                        }
                                    } else {
                                        Text(
                                            text = "截图文件不存在",
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
                            DetailRow("处理耗时", "${time}ms")
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
            title = { Text("确认删除") },
            text = { Text("确定要删除这条记录吗？此操作无法撤销。") },
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
                text = "已选择 $selectedCount 项",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = onExport) {
                    Icon(
                        Icons.Filled.Share,
                        contentDescription = "导出",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "删除",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                IconButton(onClick = onCancel) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "取消",
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
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
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
    var selectedApp by remember { mutableStateOf<String?>(null) }
    var appDropdownExpanded by remember { mutableStateOf(false) }

    val uniqueApps = remember(records) {
        records.map { it.appName }.distinct().sorted()
    }

    val filteredRecords = remember(records, selectedApp, exportMarkedOnly) {
        records.filter { record ->
            val appMatch = selectedApp == null || record.appName == selectedApp
            val markedMatch = !exportMarkedOnly || record.isMarked
            appMatch && markedMatch
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导出记录") },
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
                            "将选中的 ${records.size} 条记录导出为 ZIP 文件，可包含截图。"
                        } else {
                            "将符合条件的 ${filteredRecords.size} 条记录导出为 ZIP 文件，可包含截图。"
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )

                    if (!exportFromSelection) {
                        ExposedDropdownMenuBox(
                            expanded = appDropdownExpanded,
                            onExpandedChange = { appDropdownExpanded = it }
                        ) {
                            OutlinedTextField(
                                value = selectedApp ?: "全部应用",
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("选择应用") },
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
                                    text = { Text("全部应用") },
                                    onClick = {
                                        selectedApp = null
                                        appDropdownExpanded = false
                                    }
                                )
                                uniqueApps.forEach { appName ->
                                    val appCount = records.count { it.appName == appName }
                                    DropdownMenuItem(
                                        text = { Text("$appName ($appCount)") },
                                        onClick = {
                                            selectedApp = appName
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
                                text = "仅导出已标记的记录 (${filteredRecords.count { it.isMarked }} 条)",
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
                    Text("导出")
                }
            }
        },
        dismissButton = {
            if (!isExporting) {
                TextButton(onClick = onDismiss) {
                    Text("取消")
                }
            }
        }
    )
}
