package com.roderickqiu.seenot.components.rule

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.roderickqiu.seenot.R
import com.roderickqiu.seenot.service.AIServiceUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

enum class MatchResult {
    PENDING,
    TESTING,
    MATCHED,
    NOT_MATCHED,
    ERROR
}

data class ExampleImage(
    val id: String = UUID.randomUUID().toString(),
    val uri: Uri,
    val bitmap: Bitmap,
    val isPositive: Boolean,
    var matchResult: MatchResult = MatchResult.PENDING,
    var confidence: Double = 0.0
)

@Composable
fun FinetuneDialog(
    initialDescription: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    appName: String? = null,
    packageName: String? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    var currentDescription by remember { mutableStateOf(initialDescription) }
    val positiveExamples = remember { mutableStateListOf<ExampleImage>() }
    val negativeExamples = remember { mutableStateListOf<ExampleImage>() }
    val logs = remember { mutableStateListOf<String>() }
    
    var isOptimizing by remember { mutableStateOf(false) }
    var hasStarted by remember { mutableStateOf(false) }
    var currentIteration by remember { mutableStateOf(0) }
    val maxIterations = 10
    var optimizationJob by remember { mutableStateOf<Job?>(null) }
    var isComplete by remember { mutableStateOf(false) }
    var bestDescription by remember { mutableStateOf(initialDescription) }
    var bestScore by remember { mutableStateOf(0) }
    var showingOriginal by remember { mutableStateOf(false) }
    
    // Whether editing is allowed (only before starting optimization)
    val canEdit = !hasStarted && !isOptimizing
    
    // Check if description has changed
    val descriptionChanged = isComplete && initialDescription != currentDescription
    
    // Image picker for positive examples
    val positiveImagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        uris.forEach { uri ->
            loadBitmapFromUri(context, uri)?.let { bitmap ->
                positiveExamples.add(ExampleImage(uri = uri, bitmap = bitmap, isPositive = true))
            }
        }
    }
    
    // Image picker for negative examples
    val negativeImagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        uris.forEach { uri ->
            loadBitmapFromUri(context, uri)?.let { bitmap ->
                negativeExamples.add(ExampleImage(uri = uri, bitmap = bitmap, isPositive = false))
            }
        }
    }
    
    // Cleanup bitmaps when dialog is dismissed
    DisposableEffect(Unit) {
        onDispose {
            optimizationJob?.cancel()
            positiveExamples.forEach { it.bitmap.recycle() }
            negativeExamples.forEach { it.bitmap.recycle() }
        }
    }
    
    fun resetForReconfigure() {
        hasStarted = false
        isComplete = false
        logs.clear()
        currentIteration = 0
        // Reset all example results to pending
        positiveExamples.forEachIndexed { idx, example ->
            positiveExamples[idx] = example.copy(matchResult = MatchResult.PENDING, confidence = 0.0)
        }
        negativeExamples.forEachIndexed { idx, example ->
            negativeExamples[idx] = example.copy(matchResult = MatchResult.PENDING, confidence = 0.0)
        }
    }
    
    fun startOptimization() {
        if (positiveExamples.isEmpty() && negativeExamples.isEmpty()) {
            logs.add(context.getString(R.string.finetune_no_examples_added))
            return
        }
        
        hasStarted = true
        isOptimizing = true
        isComplete = false
        currentIteration = 0
        logs.clear()
        logs.add(context.getString(R.string.finetune_starting))
        
        // Reset all example results
        positiveExamples.forEachIndexed { idx, example ->
            positiveExamples[idx] = example.copy(matchResult = MatchResult.PENDING, confidence = 0.0)
        }
        negativeExamples.forEachIndexed { idx, example ->
            negativeExamples[idx] = example.copy(matchResult = MatchResult.PENDING, confidence = 0.0)
        }
        
        optimizationJob = coroutineScope.launch(Dispatchers.IO) {
            try {
                var workingDescription = currentDescription
                bestDescription = workingDescription
                bestScore = 0
                
                for (iteration in 1..maxIterations) {
                    if (!isActive) break
                    
                    withContext(Dispatchers.Main) {
                        currentIteration = iteration
                        logs.add(context.getString(R.string.finetune_iteration_start, iteration, maxIterations))
                    }
                    
                    // Test all examples with current description
                    val failedPositives = mutableListOf<ExampleImage>()
                    val failedNegatives = mutableListOf<ExampleImage>()
                    var passedCount = 0
                    val totalCount = positiveExamples.size + negativeExamples.size
                    
                    // Test positive examples (should match)
                    for (i in positiveExamples.indices) {
                        if (!isActive) break
                        val example = positiveExamples[i]
                        
                        withContext(Dispatchers.Main) {
                            positiveExamples[i] = example.copy(matchResult = MatchResult.TESTING)
                        }
                        
                        val (confidence, error) = AIServiceUtils.evaluateDescriptionOnImage(
                            context, example.bitmap, workingDescription
                        )
                        
                        withContext(Dispatchers.Main) {
                            val isMatch = confidence >= AIServiceUtils.CONFIDENCE_THRESHOLD
                            val newResult = if (error != null) MatchResult.ERROR 
                                           else if (isMatch) MatchResult.MATCHED 
                                           else MatchResult.NOT_MATCHED
                            positiveExamples[i] = example.copy(
                                matchResult = newResult,
                                confidence = confidence
                            )
                            if (isMatch) passedCount++ else failedPositives.add(positiveExamples[i])
                        }
                    }
                    
                    // Test negative examples (should NOT match)
                    for (i in negativeExamples.indices) {
                        if (!isActive) break
                        val example = negativeExamples[i]
                        
                        withContext(Dispatchers.Main) {
                            negativeExamples[i] = example.copy(matchResult = MatchResult.TESTING)
                        }
                        
                        val (confidence, error) = AIServiceUtils.evaluateDescriptionOnImage(
                            context, example.bitmap, workingDescription
                        )
                        
                        withContext(Dispatchers.Main) {
                            val isMatch = confidence >= AIServiceUtils.CONFIDENCE_THRESHOLD
                            // For negative examples, NOT matching is success
                            val newResult = if (error != null) MatchResult.ERROR 
                                           else if (!isMatch) MatchResult.MATCHED 
                                           else MatchResult.NOT_MATCHED
                            negativeExamples[i] = example.copy(
                                matchResult = newResult,
                                confidence = confidence
                            )
                            if (!isMatch) passedCount++ else failedNegatives.add(negativeExamples[i])
                        }
                    }
                    
                    // Update best score
                    if (passedCount > bestScore) {
                        bestScore = passedCount
                        bestDescription = workingDescription
                    }
                    
                    withContext(Dispatchers.Main) {
                        logs.add(context.getString(R.string.finetune_iteration_result, passedCount, totalCount))
                    }
                    
                    // Check if all examples pass
                    if (failedPositives.isEmpty() && failedNegatives.isEmpty()) {
                        withContext(Dispatchers.Main) {
                            logs.add(context.getString(R.string.finetune_all_passed))
                            currentDescription = workingDescription
                            isComplete = true
                            isOptimizing = false
                        }
                        return@launch
                    }
                    
                    // If not all passed and not last iteration, generate improved description
                    if (iteration < maxIterations && isActive) {
                        withContext(Dispatchers.Main) {
                            logs.add(context.getString(R.string.finetune_generating_improvement))
                        }
                        
                        val improvedDescription = AIServiceUtils.generateImprovedDescription(
                            context = context,
                            currentDescription = workingDescription,
                            failedPositives = failedPositives.map { Pair(it.bitmap, it.confidence) },
                            failedNegatives = failedNegatives.map { Pair(it.bitmap, it.confidence) },
                            appName = appName,
                            packageName = packageName
                        )
                        
                        if (improvedDescription != null && improvedDescription != workingDescription) {
                            workingDescription = improvedDescription
                            withContext(Dispatchers.Main) {
                                currentDescription = workingDescription
                                logs.add(context.getString(R.string.finetune_description_updated))
                                logs.add("→ $workingDescription")
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                logs.add(context.getString(R.string.finetune_no_improvement))
                            }
                        }
                    }
                }
                
                // Finished all iterations
                withContext(Dispatchers.Main) {
                    currentDescription = bestDescription
                    logs.add(context.getString(R.string.finetune_max_iterations_reached, bestScore, positiveExamples.size + negativeExamples.size))
                    isComplete = true
                    isOptimizing = false
                }
                
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    logs.add(context.getString(R.string.finetune_error, e.message ?: "Unknown error"))
                    isComplete = true
                    isOptimizing = false
                }
            }
        }
    }
    
    fun stopOptimization() {
        optimizationJob?.cancel()
        isOptimizing = false
        isComplete = true
        logs.add(context.getString(R.string.finetune_stopped))
        // Clear testing state for any examples still in testing
        positiveExamples.forEachIndexed { idx, example ->
            if (example.matchResult == MatchResult.TESTING) {
                positiveExamples[idx] = example.copy(matchResult = MatchResult.PENDING)
            }
        }
        negativeExamples.forEachIndexed { idx, example ->
            if (example.matchResult == MatchResult.TESTING) {
                negativeExamples[idx] = example.copy(matchResult = MatchResult.PENDING)
            }
        }
    }
    
    Dialog(
        onDismissRequest = { if (!isOptimizing) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = !isOptimizing,
            dismissOnClickOutside = !isOptimizing,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .wrapContentHeight()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = context.getString(R.string.finetune_dialog_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(
                        onClick = { if (!isOptimizing) onDismiss() },
                        enabled = !isOptimizing,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.Close, 
                            contentDescription = context.getString(R.string.cancel),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Current Description Section
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (descriptionChanged && showingOriginal) {
                                    context.getString(R.string.finetune_original_description)
                                } else {
                                    context.getString(R.string.finetune_current_description)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            
                            // Toggle button when description changed
                            if (descriptionChanged) {
                                TextButton(
                                    onClick = { showingOriginal = !showingOriginal }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.SwapHoriz,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = if (showingOriginal) {
                                            context.getString(R.string.finetune_view_optimized)
                                        } else {
                                            context.getString(R.string.finetune_view_original)
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedTextField(
                            value = if (descriptionChanged && showingOriginal) initialDescription else currentDescription,
                            onValueChange = { if (canEdit) currentDescription = it },
                            modifier = Modifier.fillMaxWidth(),
                            readOnly = !canEdit || showingOriginal,
                            minLines = 2,
                            maxLines = 3,
                            textStyle = MaterialTheme.typography.bodySmall,
                            placeholder = { Text(context.getString(R.string.please_input_parameter), style = MaterialTheme.typography.bodySmall) }
                        )
                    }
                    
                    // Positive Examples Section
                    item {
                        ExampleSection(
                            title = context.getString(R.string.finetune_positive_examples),
                            examples = positiveExamples,
                            canEdit = canEdit,
                            onAddClick = {
                                positiveImagePicker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            onRemoveClick = { example ->
                                positiveExamples.remove(example)
                                example.bitmap.recycle()
                            },
                            context = context
                        )
                    }
                    
                    // Negative Examples Section
                    item {
                        ExampleSection(
                            title = context.getString(R.string.finetune_negative_examples),
                            examples = negativeExamples,
                            canEdit = canEdit,
                            onAddClick = {
                                negativeImagePicker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            onRemoveClick = { example ->
                                negativeExamples.remove(example)
                                example.bitmap.recycle()
                            },
                            context = context
                        )
                    }
                    
                    // Progress Section
                    if (hasStarted || logs.isNotEmpty()) {
                        item {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            
                            Text(
                                text = context.getString(R.string.finetune_progress),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            
                            Spacer(modifier = Modifier.height(4.dp))
                            
                            if (isOptimizing) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = context.getString(R.string.finetune_iteration_progress, currentIteration, maxIterations),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                LinearProgressIndicator(
                                    progress = { currentIteration.toFloat() / maxIterations },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                            }
                            
                            // Log display
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(100.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                )
                            ) {
                                LazyColumn(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(8.dp)
                                ) {
                                    items(logs.takeLast(10)) { log ->
                                        Text(
                                            text = log,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(vertical = 1.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (isOptimizing) {
                        Button(
                            onClick = { stopOptimization() },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(context.getString(R.string.finetune_stop), style = MaterialTheme.typography.bodyMedium)
                        }
                    } else {
                        // Left button: Cancel / Reconfigure
                        OutlinedButton(
                            onClick = { 
                                if (isComplete) {
                                    resetForReconfigure()
                                } else {
                                    onDismiss()
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            if (isComplete) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(context.getString(R.string.finetune_reconfigure), style = MaterialTheme.typography.bodyMedium)
                            } else {
                                Text(context.getString(R.string.cancel), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        
                        // Right button: Apply / Start
                        if (isComplete) {
                            Button(
                                onClick = { onSave(currentDescription) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(context.getString(R.string.finetune_apply_result), style = MaterialTheme.typography.bodyMedium)
                            }
                        } else {
                            Button(
                                onClick = { startOptimization() },
                                modifier = Modifier.weight(1f),
                                enabled = positiveExamples.isNotEmpty() || negativeExamples.isNotEmpty()
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(context.getString(R.string.finetune_start), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExampleSection(
    title: String,
    examples: List<ExampleImage>,
    canEdit: Boolean,
    onAddClick: () -> Unit,
    onRemoveClick: (ExampleImage) -> Unit,
    context: Context
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "${examples.size}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        Spacer(modifier = Modifier.height(4.dp))
        
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(vertical = 2.dp)
        ) {
            // Add button (only show when can edit)
            if (canEdit) {
                item {
                    AddExampleButton(
                        onClick = onAddClick,
                        context = context
                    )
                }
            }
            
            // Example images
            items(examples, key = { it.id }) { example ->
                ExampleImageItem(
                    example = example,
                    canEdit = canEdit,
                    onRemoveClick = { onRemoveClick(example) },
                    context = context
                )
            }
        }
    }
}

@Composable
private fun AddExampleButton(
    onClick: () -> Unit,
    context: Context
) {
    Card(
        modifier = Modifier
            .size(72.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(6.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = context.getString(R.string.finetune_add_example),
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = context.getString(R.string.finetune_add_example),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 10.sp
                )
            }
        }
    }
}

@Composable
private fun ExampleImageItem(
    example: ExampleImage,
    canEdit: Boolean,
    onRemoveClick: () -> Unit,
    context: Context
) {
    val borderColor = when (example.matchResult) {
        MatchResult.PENDING -> MaterialTheme.colorScheme.outline
        MatchResult.TESTING -> MaterialTheme.colorScheme.primary
        MatchResult.MATCHED -> Color(0xFF4CAF50) // Green
        MatchResult.NOT_MATCHED -> Color(0xFFF44336) // Red
        MatchResult.ERROR -> Color(0xFFFF9800) // Orange
    }
    
    Box(
        modifier = Modifier.size(72.dp)
    ) {
        Card(
            modifier = Modifier
                .fillMaxSize()
                .border(
                    width = 2.dp,
                    color = borderColor,
                    shape = RoundedCornerShape(6.dp)
                ),
            shape = RoundedCornerShape(6.dp)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Image(
                    bitmap = example.bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                
                // Status indicator (only show for non-pending, non-testing states)
                if (example.matchResult != MatchResult.PENDING && example.matchResult != MatchResult.TESTING) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(2.dp)
                            .size(18.dp)
                            .background(
                                color = borderColor,
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        when (example.matchResult) {
                            MatchResult.MATCHED -> Icon(
                                Icons.Default.Check,
                                contentDescription = context.getString(R.string.finetune_passed),
                                tint = Color.White,
                                modifier = Modifier.size(12.dp)
                            )
                            MatchResult.NOT_MATCHED, MatchResult.ERROR -> Icon(
                                Icons.Default.Close,
                                contentDescription = context.getString(R.string.finetune_failed),
                                tint = Color.White,
                                modifier = Modifier.size(12.dp)
                            )
                            else -> {}
                        }
                    }
                }
                
                // Loading indicator for testing state
                if (example.matchResult == MatchResult.TESTING) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(2.dp)
                            .size(18.dp)
                            .background(
                                color = MaterialTheme.colorScheme.primary,
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 1.5.dp,
                            color = Color.White
                        )
                    }
                }
                
                // Confidence score
                if (example.confidence > 0) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(2.dp)
                            .background(
                                color = Color.Black.copy(alpha = 0.6f),
                                shape = RoundedCornerShape(3.dp)
                            )
                            .padding(horizontal = 3.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = "${example.confidence.toInt()}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            fontSize = 9.sp
                        )
                    }
                }
            }
        }
        
        // Delete button (only show when can edit)
        if (canEdit) {
            IconButton(
                onClick = onRemoveClick,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(20.dp)
                    .background(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = CircleShape
                    )
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = context.getString(R.string.delete),
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(12.dp)
                )
            }
        }
    }
}

private fun loadBitmapFromUri(context: Context, uri: Uri): Bitmap? {
    return try {
        val inputStream = context.contentResolver.openInputStream(uri)
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeStream(inputStream, null, options)
        inputStream?.close()
        
        // Calculate sample size to limit image size (max 960px on longer side)
        val maxSize = 960
        val scale = maxOf(
            options.outWidth.toFloat() / maxSize,
            options.outHeight.toFloat() / maxSize
        )
        val sampleSize = maxOf(1, scale.toInt())
        
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
        }
        
        val newInputStream = context.contentResolver.openInputStream(uri)
        val bitmap = BitmapFactory.decodeStream(newInputStream, null, decodeOptions)
        newInputStream?.close()
        
        bitmap
    } catch (e: Exception) {
        null
    }
}
