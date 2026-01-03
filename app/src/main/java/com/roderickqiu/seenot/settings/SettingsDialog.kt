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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
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
import com.roderickqiu.seenot.utils.LanguageManager

// AI Model data class
data class AiModel(
    val id: String,
    val displayName: String
)

// Language Option data class
data class LanguageOption(
    val code: String,
    val displayName: String
)

private val LANGUAGES = listOf(
    LanguageOption("auto", "跟随系统"),
    LanguageOption("zh", "简体中文"),
    LanguageOption("en", "English")
)

private val LANGUAGES_EN = listOf(
    LanguageOption("auto", "Follow System"),
    LanguageOption("zh", "简体中文"),
    LanguageOption("en", "English")
)

// Available AI models list
private val AI_MODELS = listOf(
    AiModel(id = "qwen3-vl-plus", displayName = "Qwen3 VL Plus"),
    AiModel(id = "qwen3-vl-flash", displayName = "Qwen3 VL Flash")
)

private const val AI_PREFS = "seenot_ai"
private const val KEY_MODEL = "model"
private const val KEY_API_KEY = "api_key"
private const val KEY_AUTO_SAVE_SCREENSHOT = "auto_save_screenshot"
private const val KEY_SHOW_RULE_RESULT_TOAST = "show_rule_result_toast"
private const val KEY_ENABLE_RULE_RECORDING = "enable_rule_recording"
private const val DEFAULT_MODEL_ID = "qwen3-vl-plus"

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

private fun loadAutoSaveScreenshot(context: Context): Boolean {
    val prefs = context.getSharedPreferences(AI_PREFS, Context.MODE_PRIVATE)
    return prefs.getBoolean(KEY_AUTO_SAVE_SCREENSHOT, false)
}

private fun loadShowRuleResultToast(context: Context): Boolean {
    val prefs = context.getSharedPreferences(AI_PREFS, Context.MODE_PRIVATE)
    return prefs.getBoolean(KEY_SHOW_RULE_RESULT_TOAST, false)
}

private fun loadEnableRuleRecording(context: Context): Boolean {
    val prefs = context.getSharedPreferences(AI_PREFS, Context.MODE_PRIVATE)
    return prefs.getBoolean(KEY_ENABLE_RULE_RECORDING, true)
}

private fun saveAiSettings(context: Context, modelId: String, apiKey: String, autoSaveScreenshot: Boolean, showRuleResultToast: Boolean, enableRuleRecording: Boolean) {
    val prefs = context.getSharedPreferences(AI_PREFS, Context.MODE_PRIVATE)
    prefs.edit()
        .putString(KEY_MODEL, modelId)
        .putString(KEY_API_KEY, apiKey)
        .putBoolean(KEY_AUTO_SAVE_SCREENSHOT, autoSaveScreenshot)
        .putBoolean(KEY_SHOW_RULE_RESULT_TOAST, showRuleResultToast)
        .putBoolean(KEY_ENABLE_RULE_RECORDING, enableRuleRecording)
        .apply()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDialog(onDismiss: () -> Unit, onLanguageChanged: (() -> Unit)? = null) {
    val context = LocalContext.current

    var expanded by remember { mutableStateOf(false) }
    var textFieldSize by remember { mutableStateOf(Size.Zero) }
    
    val initialModelId = loadAiModelId(context)
    var selectedModel by remember { mutableStateOf(getModelById(initialModelId)) }
    var apiKey by remember { mutableStateOf(loadAiKey(context)) }
    var autoSaveScreenshot by remember { mutableStateOf(loadAutoSaveScreenshot(context)) }
    var showRuleResultToast by remember { mutableStateOf(loadShowRuleResultToast(context)) }
    var enableRuleRecording by remember { mutableStateOf(loadEnableRuleRecording(context)) }
    
    // Language settings
    val currentLanguage = LanguageManager.getSavedLanguage(context)
    var selectedLanguage by remember { mutableStateOf(currentLanguage) }
    val systemLanguage = context.resources.configuration.locales[0].language
    val languageList = remember(selectedLanguage, systemLanguage) {
        val displayLang = when (selectedLanguage) {
            "en" -> "en"
            "auto" -> systemLanguage
            else -> "zh"
        }
        if (displayLang == "en") {
            LANGUAGES_EN
        } else {
            LANGUAGES
        }
    }
    val selectedLanguageOption = remember(selectedLanguage, languageList) {
        languageList.find { it.code == selectedLanguage } ?: languageList.first()
    }
    
    var languageExpanded by remember { mutableStateOf(false) }
    var languageTextFieldSize by remember { mutableStateOf(Size.Zero) }

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

                    Text(
                            text = context.getString(R.string.debug_options),
                            modifier = Modifier.padding(top = 16.dp)
                    )
                    
                    Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = context.getString(R.string.auto_save_screenshot))
                        Switch(
                                checked = autoSaveScreenshot,
                                onCheckedChange = { autoSaveScreenshot = it }
                        )
                    }
                    
                    Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = context.getString(R.string.show_rule_result_toast))
                        Switch(
                                checked = showRuleResultToast,
                                onCheckedChange = { showRuleResultToast = it }
                        )
                    }

                    Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
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
                                checked = enableRuleRecording,
                                onCheckedChange = { enableRuleRecording = it }
                        )
                    }
                    
                    // Language settings section
                    Text(
                            text = context.getString(R.string.language_settings),
                            modifier = Modifier.padding(top = 24.dp, bottom = 8.dp)
                    )
                    Box {
                        OutlinedTextField(
                                value = selectedLanguageOption.displayName,
                                onValueChange = {},
                                readOnly = true,
                                modifier =
                                        Modifier.fillMaxWidth()
                                                .onGloballyPositioned { coordinates ->
                                                    languageTextFieldSize = coordinates.size.toSize()
                                                }
                                                .clickable { languageExpanded = true },
                                trailingIcon = {
                                    IconButton(onClick = { languageExpanded = !languageExpanded }) {
                                        Icon(
                                                imageVector =
                                                        if (languageExpanded) Icons.Filled.KeyboardArrowUp
                                                        else Icons.Filled.KeyboardArrowDown,
                                                contentDescription = null
                                        )
                                    }
                                }
                        )
                        DropdownMenu(
                                expanded = languageExpanded,
                                onDismissRequest = { languageExpanded = false },
                                modifier =
                                        Modifier.width(
                                                with(LocalDensity.current) {
                                                    languageTextFieldSize.width.toDp()
                                                }
                                        )
                        ) {
                            languageList.forEach { language ->
                                DropdownMenuItem(
                                        text = { Text(language.displayName) },
                                        onClick = {
                                            selectedLanguage = language.code
                                            languageExpanded = false
                                        }
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                        onClick = {
                            saveAiSettings(context, selectedModel.id, apiKey, autoSaveScreenshot, showRuleResultToast, enableRuleRecording)
                            if (selectedLanguage != currentLanguage) {
                                LanguageManager.saveLanguage(context, selectedLanguage)
                                onDismiss()
                                onLanguageChanged?.invoke()
                            } else {
                                onDismiss()
                            }
                        }
                ) { Text(text = context.getString(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(text = context.getString(R.string.cancel)) }
            }
    )
}
