package com.seenot.app.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.seenot.app.data.model.AppHint
import com.seenot.app.data.repository.AppHintRepository
import com.seenot.app.domain.SessionConstraint
import com.seenot.app.domain.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ConfigurationExporter(private val context: Context) {

    private val gson: Gson = GsonBuilder()
        .setPrettyPrinting()
        .create()

    private data class ImportedAppConfig(
        val packageName: String = "",
        val appName: String = "",
        val defaultRuleId: String? = null,
        val presetRules: List<SessionConstraint> = emptyList(),
        val lastIntent: List<SessionConstraint> = emptyList(),
        val intentHistory: List<List<SessionConstraint>> = emptyList(),
        val supplementalHints: List<AppHint> = emptyList()
    )

    suspend fun exportConfiguration(onProgress: (String) -> Unit = {}): Uri? {
        return withContext(Dispatchers.IO) {
            try {
                onProgress("正在整理配置...")

                val sessionManager = SessionManager.getInstance(context)
                val appHintRepository = AppHintRepository(context)
                val packageManager = context.packageManager
                val controlledApps = sessionManager.getControlledAppsSnapshot().sorted()

                val monitoredApps = controlledApps.map { packageName ->
                    val appName = runCatching {
                        val info = packageManager.getApplicationInfo(packageName, 0)
                        packageManager.getApplicationLabel(info).toString()
                    }.getOrDefault(packageName)

                    val presetRules = sessionManager.loadPresetRules(packageName)
                    val defaultRule = sessionManager.getDefaultRule(packageName)
                    val lastIntent = sessionManager.loadLastIntent(packageName).orEmpty()
                    val history = sessionManager.loadIntentHistory(packageName)
                    val hints = appHintRepository.getHintsForPackage(packageName)

                    mapOf(
                        "packageName" to packageName,
                        "appName" to appName,
                        "defaultRuleId" to defaultRule?.id,
                        "presetRules" to presetRules,
                        "lastIntent" to lastIntent,
                        "intentHistory" to history,
                        "supplementalHints" to hints
                    )
                }

                val payload = mapOf(
                    "exportType" to "seenot_configuration",
                    "exportedAt" to System.currentTimeMillis(),
                    "exportedAtText" to SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                        .format(Date()),
                    "monitoredAppCount" to monitoredApps.size,
                    "monitoredApps" to monitoredApps
                )

                val exportDir = File(context.cacheDir, "exports").apply {
                    if (!exists()) mkdirs()
                }
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                    .format(Date())
                val exportFile = File(exportDir, "seenot_configuration_$timestamp.json")
                exportFile.writeText(gson.toJson(payload), Charsets.UTF_8)

                onProgress("配置导出完成！")

                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    exportFile
                )
            } catch (e: Exception) {
                onProgress("配置导出失败: ${e.message}")
                null
            }
        }
    }

    fun shareExportedFile(uri: Uri, onError: (String) -> Unit = {}) {
        try {
            val shareIntent = Intent().apply {
                action = Intent.ACTION_SEND
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooserIntent = Intent.createChooser(shareIntent, "分享 SeeNot 导出")
            chooserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooserIntent)
        } catch (e: Exception) {
            onError("分享失败: ${e.message}")
        }
    }

    suspend fun importConfiguration(
        uri: Uri,
        onProgress: (String) -> Unit = {}
    ): Result<Int> {
        return withContext(Dispatchers.IO) {
            runCatching {
                onProgress("正在读取导入文件...")
                val jsonText = context.contentResolver.openInputStream(uri)?.use { input ->
                    input.bufferedReader().readText()
                } ?: error("无法读取导入文件")

                val root = JsonParser.parseString(jsonText).asJsonObject
                val exportType = root.get("exportType")?.asString
                require(exportType == "seenot_configuration") { "不是 SeeNot 配置导出文件" }

                val appsElement = root.get("monitoredApps")
                    ?: error("导入文件缺少 monitoredApps")
                val listType = object : TypeToken<List<ImportedAppConfig>>() {}.type
                val importedApps: List<ImportedAppConfig> = gson.fromJson(appsElement, listType)

                val sessionManager = SessionManager.getInstance(context)
                val appHintRepository = AppHintRepository(context)
                val mergedControlledApps = sessionManager.getControlledAppsSnapshot().toMutableSet()

                importedApps.forEachIndexed { index, appConfig ->
                    val packageName = appConfig.packageName.trim()
                    if (packageName.isBlank()) return@forEachIndexed

                    onProgress("正在导入 ${index + 1}/${importedApps.size}: $packageName")
                    mergedControlledApps += packageName
                    sessionManager.savePresetRules(packageName, appConfig.presetRules)
                    sessionManager.replaceLastIntent(packageName, appConfig.lastIntent)
                    sessionManager.saveIntentHistory(packageName, appConfig.intentHistory)
                    if (appConfig.defaultRuleId.isNullOrBlank()) {
                        sessionManager.clearDefaultRule(packageName)
                    } else {
                        sessionManager.setDefaultRule(packageName, appConfig.defaultRuleId)
                    }

                    appHintRepository.deleteHintsForPackage(packageName)
                    appConfig.supplementalHints.forEach { hint ->
                        appHintRepository.saveHint(
                            hint.copy(
                                packageName = packageName,
                                hintText = hint.hintText.trim()
                            )
                        )
                    }
                }

                sessionManager.setControlledApps(mergedControlledApps)
                onProgress("导入完成！")
                importedApps.count { it.packageName.isNotBlank() }
            }
        }
    }
}
