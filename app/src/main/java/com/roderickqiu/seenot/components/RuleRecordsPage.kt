package com.roderickqiu.seenot.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.Image
import com.roderickqiu.seenot.components.ToastOverlay
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
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
import com.roderickqiu.seenot.R
import com.roderickqiu.seenot.data.AppDataStore
import com.roderickqiu.seenot.data.RuleRecord
import com.roderickqiu.seenot.data.RuleRecordRepo
import com.roderickqiu.seenot.utils.RecordExporter
import com.roderickqiu.seenot.utils.RuleFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleRecordsPage(
    modifier: Modifier = Modifier,
    ruleRecordRepo: RuleRecordRepo = RuleRecordRepo(LocalContext.current)
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // State
    var records by remember { mutableStateOf<List<RuleRecord>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var selectedDate by remember { mutableStateOf<String?>(null) }
    var selectedHour by remember { mutableStateOf<Int?>(null) }
    var showImageDialog by remember { mutableStateOf<RuleRecord?>(null) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf<RuleRecord?>(null) }
    var showDeleteAllConfirmDialog by remember { mutableStateOf(false) }
    var availableDates by remember { mutableStateOf<List<String>>(emptyList()) }
    var availableHours by remember { mutableStateOf<List<Int>>(emptyList()) }
    var isExporting by remember { mutableStateOf(false) }
    var exportProgress by remember { mutableStateOf("") }
    var filterMarkedOnly by remember { mutableStateOf(false) }
    var filterMatchStatus by remember { mutableStateOf<Boolean?>(null) } // null = all, true = matched, false = not matched
    
    val appDataStore = remember { AppDataStore(context) }

    val recordExporter = remember { RecordExporter(context) }

    // Load records function
    suspend fun loadRecords() {
        withContext(Dispatchers.IO) {
            var loadedRecords = if (selectedDate != null && selectedHour != null) {
                ruleRecordRepo.getRecordsForDateAndHour(selectedDate!!, selectedHour!!)
            } else if (selectedDate != null) {
                ruleRecordRepo.getRecordsForDate(selectedDate!!)
            } else {
                ruleRecordRepo.loadRecords()
            }

            // Apply filters
            if (filterMarkedOnly) {
                loadedRecords = loadedRecords.filter { it.isMarked }
            }
            if (filterMatchStatus != null) {
                loadedRecords = loadedRecords.filter { it.isConditionMatched == filterMatchStatus }
            }

            records = loadedRecords
            availableDates = ruleRecordRepo.getRecordsGroupedByDate().keys.sortedDescending()
            isLoading = false
        }
    }

    // Load initial data
    LaunchedEffect(Unit) {
        loadRecords()
    }

    // Update available hours when date changes
    LaunchedEffect(selectedDate) {
        if (selectedDate != null) {
            val dateRecords = ruleRecordRepo.getRecordsForDate(selectedDate!!)
            availableHours = dateRecords
                .map { record ->
                    val calendar = Calendar.getInstance().apply { time = record.date }
                    calendar.get(Calendar.HOUR_OF_DAY)
                }
                .distinct()
                .sortedDescending()
        } else {
            availableHours = emptyList()
        }
        loadRecords()
    }

    // Update records when hour changes
    LaunchedEffect(selectedHour) {
        loadRecords()
    }

    // Format timestamp
    fun formatTimestamp(timestamp: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    // Load bitmap from file
    fun loadBitmapFromFile(filePath: String): Bitmap? {
        return try {
            BitmapFactory.decodeFile(filePath)
        } catch (e: Exception) {
            null
        }
    }

    // Export records
    fun exportRecords(exportMarkedOnly: Boolean = false) {
        coroutineScope.launch {
            try {
                isExporting = true
                exportProgress = "Preparing export..."

                val recordsToExport = if (exportMarkedOnly) {
                    // Get all marked records, not just the filtered ones
                    ruleRecordRepo.getMarkedRecords()
                } else {
                    records
                }

                val exportUri = recordExporter.exportRecordsToZip(recordsToExport) { progress ->
                    exportProgress = progress
                }

                if (exportUri != null) {
                    recordExporter.shareExportedFile(exportUri) { error ->
                        ToastOverlay.show(context, error, 5000L)
                    }
                } else {
                    ToastOverlay.show(context, "Export failed", 5000L)
                }
            } catch (e: Exception) {
                ToastOverlay.show(context, "Export failed: ${e.message}", 5000L)
            } finally {
                isExporting = false
                exportProgress = ""
            }
        }
    }

    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Date and Hour filters with action buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Date selector
            var showDateMenu by remember { mutableStateOf(false) }
            OutlinedButton(
                onClick = { showDateMenu = true },
                modifier = Modifier.weight(1f)
            ) {
                Text(selectedDate ?: context.getString(R.string.all_dates))
                Icon(
                    if (selectedDate != null) Icons.Default.Close else Icons.Default.DateRange,
                    contentDescription = null,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }

            DropdownMenu(
                expanded = showDateMenu,
                onDismissRequest = { showDateMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text(context.getString(R.string.all_dates)) },
                    onClick = {
                        selectedDate = null
                        selectedHour = null
                        showDateMenu = false
                    }
                )
                availableDates.forEach { date ->
                    DropdownMenuItem(
                        text = { Text(date) },
                        onClick = {
                            selectedDate = date
                            selectedHour = null
                            showDateMenu = false
                        }
                    )
                }
            }

            // Hour selector (only show if date is selected)
            if (selectedDate != null) {
                var showHourMenu by remember { mutableStateOf(false) }
                OutlinedButton(
                    onClick = { showHourMenu = true },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(selectedHour?.toString() ?: context.getString(R.string.all_hours))
                    Icon(
                        if (selectedHour != null) Icons.Default.Close else Icons.Default.DateRange,
                        contentDescription = null,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }

                DropdownMenu(
                    expanded = showHourMenu,
                    onDismissRequest = { showHourMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(context.getString(R.string.all_hours)) },
                        onClick = {
                            selectedHour = null
                            showHourMenu = false
                        }
                    )
                    availableHours.forEach { hour ->
                        DropdownMenuItem(
                            text = { Text(String.format("%02d:00", hour)) },
                            onClick = {
                                selectedHour = hour
                                showHourMenu = false
                            }
                        )
                    }
                }
            }
            
            // Share button
            IconButton(onClick = { showExportDialog = true }) {
                Icon(Icons.Default.Share, contentDescription = context.getString(R.string.export_records))
            }
            
            // Delete all button
            IconButton(onClick = {
                showDeleteAllConfirmDialog = true
            }) {
                Icon(Icons.Default.Delete, contentDescription = context.getString(R.string.clear_all_records))
            }
        }

        // Filter buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Custom FilterChip colors for better contrast
            val filterChipColors = FilterChipDefaults.filterChipColors(
                // Unselected state - use surface container for better contrast
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                labelColor = MaterialTheme.colorScheme.onSurface,
                iconColor = MaterialTheme.colorScheme.onSurface,
                // Selected state - use primary colors for clear indication
                selectedContainerColor = MaterialTheme.colorScheme.primary,
                selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary,
                selectedTrailingIconColor = MaterialTheme.colorScheme.onPrimary
            )

            // Marked only filter
            FilterChip(
                selected = filterMarkedOnly,
                onClick = {
                    filterMarkedOnly = !filterMarkedOnly
                    coroutineScope.launch { loadRecords() }
                },
                label = { Text(context.getString(R.string.marked_only)) },
                leadingIcon = if (filterMarkedOnly) {
                    { Icon(Icons.Default.Star, contentDescription = null) }
                } else null,
                colors = filterChipColors
            )

            // Match status filter
            var showMatchFilterMenu by remember { mutableStateOf(false) }
            FilterChip(
                selected = filterMatchStatus != null,
                onClick = { showMatchFilterMenu = true },
                label = {
                    Text(
                        when (filterMatchStatus) {
                            true -> context.getString(R.string.matched_records)
                            false -> context.getString(R.string.not_matched_records)
                            null -> context.getString(R.string.all_results)
                        }
                    )
                },
                trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
                colors = filterChipColors
            )

            DropdownMenu(
                expanded = showMatchFilterMenu,
                onDismissRequest = { showMatchFilterMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text(context.getString(R.string.all_results)) },
                    onClick = {
                        filterMatchStatus = null
                        showMatchFilterMenu = false
                        coroutineScope.launch { loadRecords() }
                    }
                )
                DropdownMenuItem(
                    text = { Text(context.getString(R.string.matched_records)) },
                    onClick = {
                        filterMatchStatus = true
                        showMatchFilterMenu = false
                        coroutineScope.launch { loadRecords() }
                    }
                )
                DropdownMenuItem(
                    text = { Text(context.getString(R.string.not_matched_records)) },
                    onClick = {
                        filterMatchStatus = false
                        showMatchFilterMenu = false
                        coroutineScope.launch { loadRecords() }
                    }
                )
            }
        }

        // Records count
        Text(
            text = context.getString(R.string.records_count, records.size),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )

        // Records list
        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (records.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = context.getString(R.string.no_records_found),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(records) { record ->
                    RuleRecordItem(
                        record = record,
                        onClick = { showImageDialog = record },
                        onDeleteClick = {
                            showDeleteConfirmDialog = record
                        },
                        onMarkClick = { isMarked ->
                            coroutineScope.launch {
                                ruleRecordRepo.markRecord(record.id, isMarked)
                                loadRecords() // Refresh to show updated state
                            }
                        }
                    )
                }
            }
        }

        // Export dialog
        if (showExportDialog) {
            var exportMarkedOnly by remember { mutableStateOf(false) }

            AlertDialog(
                onDismissRequest = { if (!isExporting) showExportDialog = false },
                title = { Text(context.getString(R.string.export_records)) },
                text = {
                    if (isExporting) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                            Text(exportProgress, textAlign = TextAlign.Center)
                        }
                    } else {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(context.getString(R.string.export_records_desc))

                            // Export options
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Checkbox(
                                    checked = exportMarkedOnly,
                                    onCheckedChange = { exportMarkedOnly = it }
                                )
                                Text(
                                    text = context.getString(R.string.export_marked_only),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(start = 8.dp)
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            if (!isExporting) {
                                exportRecords(exportMarkedOnly)
                            }
                        },
                        enabled = !isExporting
                    ) {
                        Text(if (isExporting) "Exporting..." else context.getString(R.string.export_records))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { if (!isExporting) showExportDialog = false },
                        enabled = !isExporting
                    ) {
                        Text(context.getString(R.string.cancel))
                    }
                }
            )
        }

        // Delete confirmation dialog for single record
        showDeleteConfirmDialog?.let { record ->
            AlertDialog(
                onDismissRequest = { showDeleteConfirmDialog = null },
                title = { Text(context.getString(R.string.delete_record)) },
                text = { Text(context.getString(R.string.delete_record_confirm)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            coroutineScope.launch {
                                withContext(Dispatchers.IO) {
                                    val deleted = ruleRecordRepo.deleteRecord(record.id)
                                    if (deleted) {
                                        loadRecords()
                                    }
                                }
                            }
                            showDeleteConfirmDialog = null
                        }
                    ) {
                        Text(context.getString(R.string.delete))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirmDialog = null }) {
                        Text(context.getString(R.string.cancel))
                    }
                }
            )
        }

        // Delete all confirmation dialog
        if (showDeleteAllConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showDeleteAllConfirmDialog = false },
                title = { Text(context.getString(R.string.clear_all_records)) },
                text = { Text(context.getString(R.string.clear_all_records_confirm)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            coroutineScope.launch {
                                withContext(Dispatchers.IO) {
                                    val cleared = ruleRecordRepo.clearAllRecords()
                                    if (cleared) {
                                        loadRecords()
                                    }
                                }
                            }
                            showDeleteAllConfirmDialog = false
                        }
                    ) {
                        Text(context.getString(R.string.delete))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteAllConfirmDialog = false }) {
                        Text(context.getString(R.string.cancel))
                    }
                }
            )
        }

        // Image dialog
        showImageDialog?.let { currentRecord ->
            // Find current record index
            val currentIndex = records.indexOfFirst { it.id == currentRecord.id }
            val hasPrevious = currentIndex > 0
            val hasNext = currentIndex >= 0 && currentIndex < records.size - 1
            
            Dialog(onDismissRequest = { showImageDialog = null }) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(16.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Header with navigation buttons
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Previous button
                            IconButton(
                                onClick = {
                                    if (hasPrevious) {
                                        showImageDialog = records[currentIndex - 1]
                                    }
                                },
                                enabled = hasPrevious
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                    contentDescription = context.getString(R.string.previous_record)
                                )
                            }
                            
                            // Title
                            Text(
                                text = "${currentRecord.appName} - ${formatTimestamp(currentRecord.timestamp)}",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f),
                                textAlign = TextAlign.Center
                            )
                            
                            // Next button
                            IconButton(
                                onClick = {
                                    if (hasNext) {
                                        showImageDialog = records[currentIndex + 1]
                                    }
                                },
                                enabled = hasNext
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = context.getString(R.string.next_record)
                                )
                            }
                            
                            // Close button
                            IconButton(onClick = { showImageDialog = null }) {
                                Icon(Icons.Default.Close, contentDescription = "Close")
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Image
                        currentRecord.imagePath?.let { path ->
                            val imageFile = File(path)
                            if (imageFile.exists()) {
                                loadBitmapFromFile(path)?.let { bitmap ->
                                    Image(
                                        bitmap = bitmap.asImageBitmap(),
                                        contentDescription = "Screenshot",
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp)),
                                        contentScale = ContentScale.Fit
                                    )
                                } ?: Text("${context.getString(R.string.invalid_json_format)}: $path")
                            } else {
                                Text("${context.getString(R.string.no_image_available)}: $path")
                            }
                        } ?: Text(context.getString(R.string.no_image_available))

                        Spacer(modifier = Modifier.height(16.dp))

                        // Record details with full rule information
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text("${context.getString(R.string.app)}: ${currentRecord.appName}", style = MaterialTheme.typography.bodyMedium)
                            
                            // Get full rule information including timeConstraint
                            val monitoringApps = appDataStore.loadMonitoringApps()
                            val fullRule = monitoringApps
                                .flatMap { it.rules }
                                .find { it.id == currentRecord.ruleId }
                            
                            if (fullRule != null) {
                                Text(
                                    text = "${context.getString(R.string.rule)}: ${RuleFormatter.formatRule(context, fullRule)}",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            } else {
                                Text("${context.getString(R.string.condition)}: ${currentRecord.condition.type}${if (currentRecord.condition.parameter != null) " - ${currentRecord.condition.parameter}" else ""}", style = MaterialTheme.typography.bodyMedium)
                                Text("${context.getString(R.string.action)}: ${currentRecord.action.type}", style = MaterialTheme.typography.bodyMedium)
                            }
                            
                            Text("${context.getString(R.string.result)}: ${if (currentRecord.isConditionMatched) context.getString(R.string.matched) else context.getString(R.string.not_matched)}", style = MaterialTheme.typography.bodyMedium)
                            currentRecord.elapsedTimeMs?.let {
                                Text("${context.getString(R.string.processing_time)}: ${it}ms", style = MaterialTheme.typography.bodyMedium)
                            }
                            currentRecord.aiResult?.let { aiResult ->
                                // Try to parse reason from JSON, if failed show original text
                                val displayText = try {
                                    val reasonPattern = Regex("\"reason\"\\s*:\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE)
                                    val match = reasonPattern.find(aiResult)
                                    match?.groupValues?.get(1) ?: aiResult
                                } catch (e: Exception) {
                                    aiResult
                                }
                                Text("${context.getString(R.string.ai_response)}: $displayText", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RuleRecordItem(
    record: RuleRecord,
    onClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onMarkClick: (Boolean) -> Unit,
    context: Context = LocalContext.current
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Clickable content area
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onClick)
            ) {
                Text(
                    text = record.appName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${record.condition.type} → ${record.action.type}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(record.date),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Result indicator
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(
                        color = if (record.isConditionMatched)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.error,
                        shape = RoundedCornerShape(6.dp)
                    )
            )

            Spacer(modifier = Modifier.width(8.dp))

            // Mark button
            IconButton(onClick = { onMarkClick(!record.isMarked) }) {
                Icon(
                    if (record.isMarked) Icons.Default.Star else Icons.Outlined.Star,
                    contentDescription = if (record.isMarked) context.getString(R.string.unmark_record) else context.getString(R.string.mark_record),
                    tint = if (record.isMarked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Delete button - this should automatically stop event propagation
            IconButton(onClick = onDeleteClick) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = context.getString(R.string.delete_record),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}