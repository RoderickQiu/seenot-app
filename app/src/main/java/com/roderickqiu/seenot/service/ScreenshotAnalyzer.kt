package com.roderickqiu.seenot.service

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.view.Display
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.chat.ContentPart
import com.aallam.openai.api.chat.ContentPartBuilder
import com.aallam.openai.api.http.Timeout
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.OpenAI
import com.aallam.openai.client.OpenAIHost
import com.roderickqiu.seenot.data.AppDataStore
import com.roderickqiu.seenot.data.ConditionType
import com.roderickqiu.seenot.data.Rule
import com.roderickqiu.seenot.data.TimeConstraint
import com.roderickqiu.seenot.service.AIServiceUtils
import com.roderickqiu.seenot.service.BitmapUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

class ScreenshotAnalyzer(
    private val context: Context,
    private val appDataStore: AppDataStore,
    private val constraintManager: ConstraintManager,
    private val actionExecutor: ActionExecutor,
    private val notificationManager: NotificationManager
) {
    private var isTakingScreenshot: Boolean = false
    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    
    var currentMonitoredAppName: String? = null
    var currentMonitoredPackage: String? = null

    fun tryTakeScreenshot(
        service: android.accessibilityservice.AccessibilityService,
        reason: String
    ) {
        if (isTakingScreenshot) {
            return
        }
        isTakingScreenshot = true
        val executor = ContextCompat.getMainExecutor(context)
        try {
            service.takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    executor,
                    object : android.accessibilityservice.AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(
                                screenshotResult: android.accessibilityservice.AccessibilityService.ScreenshotResult
                        ) {
                            isTakingScreenshot = false
                            Log.d(
                                    "A11yService",
                                    "Screenshot success for $currentMonitoredAppName ($currentMonitoredPackage), reason=$reason"
                            )

                            try {
                                val hb = screenshotResult.hardwareBuffer
                                val cs = screenshotResult.colorSpace
                                val hardwareBitmap = Bitmap.wrapHardwareBuffer(hb, cs)
                                if (hardwareBitmap != null) {
                                    val originalWidth = hardwareBitmap.width
                                    val originalHeight = hardwareBitmap.height

                                    // Create a mutable copy for processing
                                    val mutableBitmap =
                                            hardwareBitmap.copy(Bitmap.Config.ARGB_8888, true)

                                    // Save original screenshot to gallery if enabled
                                    if (AIServiceUtils.loadAutoSaveScreenshot(context)) {
                                        // Create a separate copy for saving to gallery to avoid interference
                                        val galleryBitmap = mutableBitmap.copy(Bitmap.Config.ARGB_8888, false)
                                        coroutineScope.launch {
                                            try {
                                                BitmapUtils.saveBitmapToGallery(context, galleryBitmap, currentMonitoredAppName, reason)
                                                galleryBitmap.recycle()
                                            } catch (e: Exception) {
                                                Log.e("A11yService", "Error saving screenshot to gallery", e)
                                                galleryBitmap.recycle()
                                            }
                                        }
                                    }

                                    // Scale image: max 960px on longer side, maintain aspect ratio
                                    val maxSize = 960
                                    val scale = if (originalWidth > originalHeight) {
                                        maxSize.toFloat() / originalWidth
                                    } else {
                                        maxSize.toFloat() / originalHeight
                                    }
                                    val scaledWidth = (originalWidth * scale).toInt()
                                    val scaledHeight = (originalHeight * scale).toInt()

                                    val scaledBitmap =
                                            Bitmap.createScaledBitmap(
                                                    mutableBitmap,
                                                    scaledWidth,
                                                    scaledHeight,
                                                    true
                                            )

                                    Log.d(
                                            "A11yService",
                                            "Bitmap created and scaled from screenshot: ${scaledBitmap.width}x${scaledBitmap.height} (original: ${originalWidth}x${originalHeight})"
                                    )

                                    // Describe image using AI
                                    describeImageWithAI(scaledBitmap)
                                } else {
                                    Log.w(
                                            "A11yService",
                                            "Failed to wrap hardware buffer into Bitmap"
                                    )
                                }
                            } catch (t: Throwable) {
                                Log.e("A11yService", "Error creating Bitmap from screenshot", t)
                            } finally {
                                try {
                                    screenshotResult.hardwareBuffer.close()
                                } catch (_: Throwable) {}
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            isTakingScreenshot = false
                            Log.w(
                                    "A11yService",
                                    "Screenshot failed (code=$errorCode) for $currentMonitoredAppName ($currentMonitoredPackage), reason=$reason"
                            )
                        }
                    }
            )
        } catch (t: Throwable) {
            isTakingScreenshot = false
            Log.e("A11yService", "Screenshot exception for reason=$reason", t)
        }
    }

    private fun describeImageWithAI(bitmap: Bitmap) {
        val apiKey = AIServiceUtils.loadAiKey(context)
        val modelId = AIServiceUtils.loadAiModelId(context)

        if (apiKey.isEmpty()) {
            Log.w("A11yService", "AI API key not configured, skipping image description")
            return
        }

        coroutineScope.launch {
            try {
                // Convert bitmap to base64
                val base64Image = BitmapUtils.bitmapToBase64(bitmap)
                Log.d("A11yService", "Image converted to base64, size: ${base64Image.length} chars")

                // Get activated rules only for the currently monitored app
                val currentAppName = currentMonitoredAppName
                if (currentAppName == null) {
                    Log.d("A11yService", "No currently monitored app, skipping AI evaluation")
                    bitmap.recycle()
                    return@launch
                }

                val monitoringApps = appDataStore.loadMonitoringApps()
                val activatedRules = mutableListOf<Pair<String, Rule>>()
                
                // Find the current monitored app and get its rules
                val currentApp = monitoringApps.find { it.name == currentAppName && it.isEnabled }
                if (currentApp != null) {
                    for (rule in currentApp.rules) {
                        // Only process conditions that can be evaluated visually
                        if (rule.condition.type == ConditionType.ON_PAGE) {
                            activatedRules.add(Pair(currentApp.name, rule))
                        }
                    }
                }

                Log.d("A11yService", "Found ${activatedRules.size} activated rules to evaluate for app=$currentAppName")

                if (activatedRules.isEmpty()) {
                    Log.d("A11yService", "No activated rules found, skipping AI evaluation")
                    bitmap.recycle()
                    return@launch
                }

                // Create OpenAI client with custom base URL for DashScope
                val openAI = OpenAI(
                    token = apiKey,
                    timeout = Timeout(socket = 60.seconds),
                    host = OpenAIHost(baseUrl = AIServiceUtils.AI_BASE_URL)
                )

                val imageContent = "data:image/png;base64,$base64Image"

                // Process each activated rule
                for ((appName, rule) in activatedRules) {
                    val startTime = System.currentTimeMillis()
                    try {
                        if (!constraintManager.areRulesEnabled()) {
                            Log.d("A11yService", "Rules paused - skipping rule ${rule.id} for $appName")
                            constraintManager.endShortTermRecord(rule.id, appName)
                            continue
                        }

                        val condition = rule.condition
                        val action = rule.action
                        
                        // Build question from condition
                        val question = if (condition.type == ConditionType.ON_PAGE) {
                            val description = condition.parameter ?: ""
                            "Does the current screen match this description: $description?"
                        } else {
                            null
                        }

                        if (question == null) {
                            continue
                        }

                        // Build app context with available information only
                        val appContext = if (currentMonitoredAppName != null && currentMonitoredPackage != null) {
                            "Current app: $currentMonitoredAppName (package: $currentMonitoredPackage)"
                        } else {
                            null
                        }
                        
                        // Combine AI_PROMPT with the condition question and app context
                        val fullPrompt = if (appContext != null) {
                            "${AIServiceUtils.AI_PROMPT}\n\n$appContext\n\nUser's question: $question"
                        } else {
                            "${AIServiceUtils.AI_PROMPT}\n\nUser's question: $question"
                        }

                        // Create chat completion request
                        val contentList: List<ContentPart> = ContentPartBuilder().apply {
                            image(imageContent)
                            text(fullPrompt)
                        }.build()
                        
                        val request = ChatCompletionRequest(
                            model = ModelId(modelId),
                            messages = listOf(
                                ChatMessage(
                                    role = ChatRole.User,
                                    content = contentList
                                )
                            ),
                            maxTokens = 1000
                        )

                        // Call API
                        val response = openAI.chatCompletion(request)
                        val elapsedTime = System.currentTimeMillis() - startTime

                        // Extract and log response with both condition and action
                        val result = response.choices.firstOrNull()?.message?.content
                        if (result != null) {
                            Log.d("A11yService", "AI Result for app=$appName, question=$question, context=$appContext, condition=${condition.type}, action=${action.type}: $result, elapsed time: ${elapsedTime}ms")
                            
                            // Parse AI result to check if condition matches
                            val isConditionMatch = AIServiceUtils.parseAIResult(result)
                            
                            // Show toast if debug option is enabled
                            if (AIServiceUtils.loadShowRuleResultToast(context)) {
                                val resultText = if (isConditionMatch) "YES" else "NO"
                                notificationManager.showToast("$question: $resultText", Toast.LENGTH_SHORT)
                            }
                            
                            // Handle time constraint if present
                            if (isConditionMatch && rule.timeConstraint != null) {
                                constraintManager.handleTimeConstraint(rule, appName, isConditionMatch)
                            } else if (isConditionMatch && rule.timeConstraint == null) {
                                // No time constraint - trigger immediately
                                actionExecutor.executeAction(rule, appName)
                            } else {
                                // Condition doesn't match - end current record if any
                                constraintManager.endShortTermRecord(rule.id, appName)
                            }
                        } else {
                            Log.w("A11yService", "No result returned from AI for app=$appName, question=$question, context=$appContext, condition=${condition.type}, action=${action.type}, elapsed time: ${elapsedTime}ms")
                        }
                    } catch (e: Exception) {
                        val elapsedTime = System.currentTimeMillis() - startTime
                        Log.e("A11yService", "Error evaluating rule for app=$appName, condition=${rule.condition.type}, action=${rule.action.type} (elapsed: ${elapsedTime}ms)", e)
                        e.message?.let { 
                            Log.e("A11yService", "Error details: $it")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("A11yService", "Error in describeImageWithAI", e)
                e.message?.let { Log.e("A11yService", "Error details: $it") }
            } finally {
                bitmap.recycle()
            }
        }
    }
}

