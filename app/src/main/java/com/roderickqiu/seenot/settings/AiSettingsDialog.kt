package com.roderickqiu.seenot.settings

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import com.roderickqiu.seenot.R

// AI Model data class
data class AiModel(
    val id: String,
    val displayName: String
)

// Available AI models list
private val AI_MODELS = listOf(
    AiModel(id = "qwen3-vl-plus", displayName = "Qwen3 VL Plus"),
    AiModel(id = "qwen3-vl-flash", displayName = "Qwen3 VL Flash")
)

private const val AI_PREFS = "seenot_ai"
private const val KEY_MODEL = "model"
private const val KEY_API_KEY = "api_key"
private const val DEFAULT_MODEL_ID = "qwen3-vl-flash"

private fun loadAiModelId(context: Context): String {
    val prefs = context.getSharedPreferences(AI_PREFS, Context.MODE_PRIVATE)
    return prefs.getString(KEY_MODEL, DEFAULT_MODEL_ID) ?: DEFAULT_MODEL_ID
}

private fun getModelById(id: String): AiModel {
    return AI_MODELS.find { it.id == id } ?: AI_MODELS.first()
}

private fun loadAiKey(context: Context): String {
    val prefs = context.getSharedPreferences(AI_PREFS, Context.MODE_PRIVATE)
    return prefs.getString(KEY_API_KEY, "") ?: ""
}

private fun saveAiSettings(context: Context, modelId: String, apiKey: String) {
    val prefs = context.getSharedPreferences(AI_PREFS, Context.MODE_PRIVATE)
    prefs.edit().putString(KEY_MODEL, modelId).putString(KEY_API_KEY, apiKey).apply()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSettingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current

    var expanded by remember { mutableStateOf(false) }
    var textFieldSize by remember { mutableStateOf(Size.Zero) }
    
    val initialModelId = loadAiModelId(context)
    var selectedModel by remember { mutableStateOf(getModelById(initialModelId)) }
    var apiKey by remember { mutableStateOf(loadAiKey(context)) }

    AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(text = context.getString(R.string.ai_settings)) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(text = context.getString(R.string.ai_model))
                    Box {
                        OutlinedTextField(
                                value = selectedModel.displayName,
                                onValueChange = {},
                                readOnly = true,
                                modifier =
                                        Modifier.fillMaxWidth()
                                                .onGloballyPositioned { coordinates ->
                                                    textFieldSize = coordinates.size.toSize()
                                                }
                                                .clickable { expanded = true },
                                trailingIcon = {
                                    IconButton(onClick = { expanded = !expanded }) {
                                        Icon(
                                                imageVector =
                                                        if (expanded) Icons.Filled.KeyboardArrowUp
                                                        else Icons.Filled.KeyboardArrowDown,
                                                contentDescription = null
                                        )
                                    }
                                }
                        )
                        DropdownMenu(
                                expanded = expanded,
                                onDismissRequest = { expanded = false },
                                modifier =
                                        Modifier.width(
                                                with(LocalDensity.current) {
                                                    textFieldSize.width.toDp()
                                                }
                                        )
                        ) {
                            AI_MODELS.forEach { model ->
                                DropdownMenuItem(
                                        text = { Text(model.displayName) },
                                        onClick = {
                                            selectedModel = model
                                            expanded = false
                                        }
                                )
                            }
                        }
                    }

                    Text(
                            text = context.getString(R.string.ai_key),
                            modifier = Modifier.padding(top = 16.dp)
                    )
                    TextField(
                            value = apiKey,
                            onValueChange = { apiKey = it },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp)
                    )
                }
            },
            confirmButton = {
                TextButton(
                        onClick = {
                            saveAiSettings(context, selectedModel.id, apiKey)
                            onDismiss()
                        }
                ) { Text(text = context.getString(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(text = context.getString(R.string.cancel)) }
            }
    )
}
