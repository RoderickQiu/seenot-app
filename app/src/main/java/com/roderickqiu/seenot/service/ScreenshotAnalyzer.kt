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
import com.roderickqiu.seenot.data.RuleRecord
import com.roderickqiu.seenot.data.RuleRecordRepo
import com.roderickqiu.seenot.data.TimeConstraint
import com.roderickqiu.seenot.service.AIServiceUtils
import com.roderickqiu.seenot.service.BitmapUtils
import com.roderickqiu.seenot.utils.GenericUtils
import com.roderickqiu.seenot.utils.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

class ScreenshotAnalyzer(
    private val context: Context,
    private val appDataStore: AppDataStore,
    private val constraintManager: ConstraintManager,
    private val actionExecutor: ActionExecutor,
    private val notificationManager: NotificationManager,
    private val ruleRecordRepo: RuleRecordRepo = RuleRecordRepo(context)
) {
    private var isTakingScreenshot: Boolean = false
    private val coroutineScope = CoroutineScope(Dispatchers.IO)

    // Screenshot hash deduplication related fields
    private val processedScreenshotHashes = mutableMapOf<String, Long>()
    private val hashRetentionTimeMs = 25000L // Hash retention for 25 seconds
    // Store AI results for each hash: hash -> (appName -> ruleId -> isConditionMatch)
    private val hashAiResults = mutableMapOf<String, MutableMap<String, MutableMap<String, Boolean>>>()
    
    // Minimum time to allow user to read the feedback toast before next screenshot clears it
    private var lastAiResultTime: Long = 0
    private val MIN_FEEDBACK_READ_TIME_MS = 2000L
    
    var currentMonitoredAppName: String? = null
    var currentMonitoredPackage: String? = null

    fun shouldTakePeriodicScreenshot(lastScreenshotTime: Long): Boolean {
        val now = System.currentTimeMillis()
        val intervalPassed = (now - lastScreenshotTime) >= A11yService.LOG_INTERVAL_MS
        
        // If result toast is disabled in settings, we don't need to wait for feedback read time
        if (!AIServiceUtils.loadShowRuleResultToast(context)) {
            return intervalPassed
        }
        
        // Ensure the user has enough time to read the previous result toast
        // if one was shown recently
        val feedbackReadTimePassed = (now - lastAiResultTime) >= MIN_FEEDBACK_READ_TIME_MS
        
        return intervalPassed && feedbackReadTimePassed
    }

    fun tryTakeScreenshot(
        service: android.accessibilityservice.AccessibilityService,
        reason: String
    ) {
        if (isTakingScreenshot) {
            return
        }
        isTakingScreenshot = true
        
        // Dismiss any existing overlay toasts to prevent them from appearing in the screenshot
        notificationManager.dismissAllToasts()
        
        // Delay screenshot capture to allow UI to refresh after toast dismissal
        // This ensures the toast is visually removed from the screen before capture
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            takeScreenshotInternal(service, reason)
        }, 100L) // 100ms delay for UI refresh (6 frames at 60fps)
    }
    
    private fun takeScreenshotInternal(
        service: android.accessibilityservice.AccessibilityService,
        reason: String
    ) {
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

                                    Logger.d(
                                            "A11yService",
                                            "Screenshot success for $currentMonitoredAppName ($currentMonitoredPackage), reason=$reason, scaled: ${scaledBitmap.width}x${scaledBitmap.height} (original: ${originalWidth}x${originalHeight})"
                                    )

                                    // Describe image using AI
                                    describeImageWithAI(scaledBitmap)
                                } else {
                                    Logger.w(
                                            "A11yService",
                                            "Failed to wrap hardware buffer into Bitmap"
                                    )
                                }
                            } catch (t: Throwable) {
                                Logger.e("A11yService", "Error creating Bitmap from screenshot", t)
                            } finally {
                                try {
                                    screenshotResult.hardwareBuffer.close()
                                } catch (_: Throwable) {}
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            isTakingScreenshot = false
                            Logger.w(
                                    "A11yService",
                                    "Screenshot failed (code=$errorCode) for $currentMonitoredAppName ($currentMonitoredPackage), reason=$reason"
                            )
                        }
                    }
            )
        } catch (t: Throwable) {
            isTakingScreenshot = false
            Logger.e("A11yService", "Screenshot exception for reason=$reason", t)
        }
    }

    private fun describeImageWithAI(bitmap: Bitmap) {
        val apiKey = AIServiceUtils.loadAiKey(context)
        val modelId = AIServiceUtils.loadAiModelId(context)

        if (apiKey.isEmpty()) {
            Logger.e("A11yService", "AI API key not configured, skipping image description")
            return
        }

        // Screenshot content deduplication check
        val screenshotHash = generateScreenshotHash(bitmap)
        val currentAppName = currentMonitoredAppName
        
        if (screenshotHash != null && currentAppName != null) {
            val now = System.currentTimeMillis()
            val existingTimestamp = processedScreenshotHashes[screenshotHash]
            
            if (existingTimestamp != null && (now - existingTimestamp) < hashRetentionTimeMs) {
                // Reuse previous AI results for this duplicate screenshot
                val previousResults = hashAiResults[screenshotHash]?.get(currentAppName)
                if (previousResults != null) {
                    Logger.d("A11yService", "Reusing previous AI results for duplicate screenshot (hash: $screenshotHash, age: ${now - existingTimestamp}ms)")
                    bitmap.recycle()
                    reusePreviousResults(currentAppName, previousResults)
                    return
                } else {
                    Logger.d("A11yService", "Duplicate screenshot but no previous results found, processing normally (hash: $screenshotHash)")
                }
            }

            // Record processed hash with timestamp
            processedScreenshotHashes[screenshotHash] = now
            cleanupExpiredHashes(now)
        } else {
            if (screenshotHash == null) {
                Logger.w("A11yService", "Failed to generate screenshot hash, skipping deduplication check")
            }
        }

        // Periodically clean up expired action records in ActionExecutor
        actionExecutor.cleanupExpiredActionRecords()

        // Process with AI (will store results for future reuse)
        processWithAI(bitmap, screenshotHash, currentAppName, apiKey, modelId)
    }

    private fun reusePreviousResults(appName: String, previousResults: Map<String, Boolean>) {
        val monitoringApps = appDataStore.loadMonitoringApps()
        val currentApp = monitoringApps.find { it.name == appName && it.isEnabled }
        
        if (currentApp == null) {
            Logger.d("A11yService", "App $appName not found or disabled, skipping result reuse")
            return
        }

        for (rule in currentApp.rules) {
            if (rule.condition.type != ConditionType.ON_PAGE) {
                continue
            }

            val isConditionMatch = previousResults[rule.id] ?: continue

            if (!constraintManager.areRulesEnabled()) {
                Logger.d("A11yService", "Rules paused - skipping rule ${rule.id} for $appName")
                constraintManager.endShortTermRecord(rule.id, appName)
                continue
            }

            // Show toast if debug option is enabled
            if (AIServiceUtils.loadShowRuleResultToast(context)) {
                val resultText = if (isConditionMatch) "YES" else "NO"
                val description = rule.condition.parameter ?: ""
                val truncatedDescription = if (description.length > GenericUtils.TOAST_TEXT_MAX_LENGTH) {
                    description.take(GenericUtils.TOAST_TEXT_MAX_LENGTH) + "..."
                } else {
                    description
                }
                notificationManager.showToast("$truncatedDescription: $resultText (reused)", Toast.LENGTH_SHORT)
                lastAiResultTime = System.currentTimeMillis()
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
        }
    }

    private fun processWithAI(bitmap: Bitmap, screenshotHash: String?, appNameParam: String?, apiKey: String, modelId: String) {
        coroutineScope.launch {
            try {
                // Convert bitmap to base64
                val base64Image = BitmapUtils.bitmapToBase64(bitmap)

                // Get activated rules only for the currently monitored app
                val targetAppName = appNameParam ?: currentMonitoredAppName
                if (targetAppName == null) {
                    Logger.d("A11yService", "No currently monitored app, skipping AI evaluation")
                    bitmap.recycle()
                    return@launch
                }

                val monitoringApps = appDataStore.loadMonitoringApps()
                val activatedRules = mutableListOf<Pair<String, Rule>>()
                
                // Find the current monitored app and get its rules
                val currentApp = monitoringApps.find { it.name == targetAppName && it.isEnabled }
                if (currentApp != null) {
                    for (rule in currentApp.rules) {
                        // Only process conditions that can be evaluated visually
                        if (rule.condition.type == ConditionType.ON_PAGE) {
                            activatedRules.add(Pair(currentApp.name, rule))
                        }
                    }
                }

                Logger.d("A11yService", "Found ${activatedRules.size} activated rules to evaluate for app=$targetAppName")

                if (activatedRules.isEmpty()) {
                    Logger.d("A11yService", "No activated rules found, skipping AI evaluation")
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

                // Save screenshot for records (create a copy since bitmap will be recycled)
                val recordBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)

                // Process each activated rule
                for ((appName, rule) in activatedRules) {
                    val startTime = System.currentTimeMillis()
                    try {
                        if (!constraintManager.areRulesEnabled()) {
                            Logger.d("A11yService", "Rules paused - skipping rule ${rule.id} for $appName")
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
                            val confidence = AIServiceUtils.parseConfidence(result)
                            val isConditionMatch = confidence >= AIServiceUtils.CONFIDENCE_THRESHOLD
                            
                            Logger.d("A11yService", "AI Result for app=$appName, question=$question, context=$appContext, condition=${condition.type}, action=${action.type}: $result, confidence=$confidence, match=$isConditionMatch, elapsed time: ${elapsedTime}ms")
                            
                            // Store AI result for potential reuse
                            if (screenshotHash != null) {
                                hashAiResults.getOrPut(screenshotHash) { mutableMapOf() }
                                    .getOrPut(appName) { mutableMapOf() }[rule.id] = isConditionMatch
                            }

                            // Create and save rule record if recording is enabled
                            if (AIServiceUtils.loadEnableRuleRecording(context)) {
                                try {
                                    val record = RuleRecord(
                                        appName = appName,
                                        packageName = currentMonitoredPackage,
                                        ruleId = rule.id,
                                        condition = condition,
                                        action = action,
                                        isConditionMatched = isConditionMatch,
                                        aiResult = result,
                                        confidence = confidence,
                                        elapsedTimeMs = elapsedTime
                                    )

                                    // Save record with image
                                    val savedRecord = ruleRecordRepo.saveRecord(record)

                                    // Save screenshot for this record (marked status is false by default for new records)
                                    ruleRecordRepo.saveScreenshotForRecord(savedRecord.id, recordBitmap, savedRecord.isMarked)

                                    Logger.d("A11yService", "Saved rule record: ${savedRecord.id} for rule ${rule.id}")
                                } catch (recordError: Exception) {
                                    Logger.e("A11yService", "Error saving rule record", recordError)
                                }
                            }

                            // Show toast if debug option is enabled
                            if (AIServiceUtils.loadShowRuleResultToast(context)) {
                                val resultText = if (isConditionMatch) "YES ($confidence)" else "NO ($confidence)"
                                // Truncate description to first N characters for toast display
                                val description = condition.parameter ?: ""
                                val truncatedDescription = if (description.length > GenericUtils.TOAST_TEXT_MAX_LENGTH) {
                                    description.take(GenericUtils.TOAST_TEXT_MAX_LENGTH) + "..."
                                } else {
                                    description
                                }
                                notificationManager.showToast("$truncatedDescription: $resultText", Toast.LENGTH_SHORT)
                                lastAiResultTime = System.currentTimeMillis()
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
                            Logger.w("A11yService", "No result returned from AI for app=$appName, question=$question, context=$appContext, condition=${condition.type}, action=${action.type}, elapsed time: ${elapsedTime}ms")
                        }
                    } catch (e: Exception) {
                        val elapsedTime = System.currentTimeMillis() - startTime
                        Logger.e("A11yService", "Error evaluating rule for app=$appName, condition=${rule.condition.type}, action=${rule.action.type} (elapsed: ${elapsedTime}ms)", e)
                        e.message?.let { 
                            Logger.e("A11yService", "Error details: $it")
                        }
                    }
                }

                // Recycle the record bitmap copy
                recordBitmap.recycle()
            } catch (e: Exception) {
                Logger.e("A11yService", "Error in describing image with AI", e)
                e.message?.let { Logger.e("A11yService", "Error details: $it") }
            } finally {
                bitmap.recycle()
            }
        }
    }

    /**
     * Generate hash value for screenshot content
     * Uses bitmap dimensions, pixel sampling, and simple hash algorithm
     * Returns null if hash generation fails (will skip deduplication)
     */
    private fun generateScreenshotHash(bitmap: Bitmap): String? {
        try {
            // Sample large images to improve performance
            val sampleSize = maxOf(1, maxOf(bitmap.width, bitmap.height) / 100)
            val sampledBitmap = Bitmap.createScaledBitmap(
                bitmap,
                bitmap.width / sampleSize,
                bitmap.height / sampleSize,
                false
            )

            // Calculate pixel data hash
            val pixels = IntArray(sampledBitmap.width * sampledBitmap.height)
            sampledBitmap.getPixels(pixels, 0, sampledBitmap.width, 0, 0, sampledBitmap.width, sampledBitmap.height)

            // Use simple hash algorithm
            var hash = 0L
            for (pixel in pixels) {
                hash = hash * 31 + pixel.toLong()
            }

            sampledBitmap.recycle()

            // Combine dimension info and pixel hash
            return "${bitmap.width}x${bitmap.height}_${hash.hashCode()}"

        } catch (e: Exception) {
            Logger.w("A11yService", "Error generating screenshot hash, skipping deduplication", e)
            return null
        }
    }

    /**
     * Clean up expired screenshot hash records based on time
     */
    private fun cleanupExpiredHashes(currentTime: Long) {
        // Remove expired hashes (older than retention time)
        val expiredKeys = processedScreenshotHashes.filter { (_, timestamp) ->
            (currentTime - timestamp) >= hashRetentionTimeMs
        }.keys
        
        if (expiredKeys.isNotEmpty()) {
            expiredKeys.forEach { 
                processedScreenshotHashes.remove(it)
                hashAiResults.remove(it) // Also clean up associated AI results
            }
            Logger.d("A11yService", "Cleaned up ${expiredKeys.size} expired screenshot hash records, remaining: ${processedScreenshotHashes.size}")
        }
        
        // Safety cleanup: if still too many records, remove oldest ones
        if (processedScreenshotHashes.size > 100) {
            val sortedByTime = processedScreenshotHashes.toList().sortedBy { it.second }
            val toRemove = sortedByTime.take(processedScreenshotHashes.size - 50).map { it.first }
            toRemove.forEach { 
                processedScreenshotHashes.remove(it)
                hashAiResults.remove(it) // Also clean up associated AI results
            }
            Logger.d("A11yService", "Safety cleanup: removed ${toRemove.size} oldest hash records, remaining: ${processedScreenshotHashes.size}")
        }
    }
}

