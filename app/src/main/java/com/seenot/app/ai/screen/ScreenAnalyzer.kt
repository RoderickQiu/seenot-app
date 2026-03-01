package com.seenot.app.ai.screen

import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationParam
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationResult
import com.alibaba.dashscope.common.MultiModalMessage
import com.alibaba.dashscope.common.Role
import com.alibaba.dashscope.exception.ApiException
import com.alibaba.dashscope.exception.NoApiKeyException
import com.google.gson.Gson
import com.seenot.app.config.ApiConfig
import com.seenot.app.data.model.ConstraintType
import com.seenot.app.data.model.RuleRecord
import com.seenot.app.data.repository.RuleRecordRepository
import com.seenot.app.domain.SessionConstraint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Arrays
import java.util.Collections

/**
 * Screen Analyzer - AI vision for monitoring screen content
 *
 * Features:
 * - Dismiss all toasts before screenshot (avoid capturing overlay toasts)
 * - Scale screenshot to max 960px on longer edge
 * - Hash-based deduplication and result caching
 * - Batch constraint checking (multiple constraints per API call)
 * - Confidence thresholds with fallback strategies
 * - Continuous confirmation (2 consecutive violations for forced actions)
 */
class ScreenAnalyzer(
    private val context: Context,
    private val ruleRecordRepository: RuleRecordRepository = RuleRecordRepository(context)
) {

    companion object {
        private const val TAG = "ScreenAnalyzer"

        // Default analysis interval
        const val DEFAULT_ANALYSIS_INTERVAL_MS = 5_000L // 5 seconds

        // Screenshot processing
        const val MAX_LONG_EDGE_PX = 960
        const val TOAST_DISMISS_DELAY_MS = 100L

        // Confidence thresholds
        const val CONFIDENCE_HIGH = 0.90
        const val CONFIDENCE_MEDIUM = 0.70
        const val CONFIDENCE_LOW = 0.50

        // Hash result cache TTL
        const val HASH_RESULT_CACHE_TTL_MS = 25_000L // 25 seconds

        // Consecutive violation threshold for forced actions
        const val VIOLATION_THRESHOLD = 2
    }

    private val gson = Gson()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Analysis state
    private val _isAnalyzing = MutableStateFlow(false)
    val isAnalyzing: StateFlow<Boolean> = _isAnalyzing.asStateFlow()

    private val _lastAnalysisResult = MutableStateFlow<ScreenAnalysisResult?>(null)
    val lastAnalysisResult: StateFlow<ScreenAnalysisResult?> = _lastAnalysisResult.asStateFlow()

    // Analysis settings
    var analysisIntervalMs: Long = DEFAULT_ANALYSIS_INTERVAL_MS

    // Consecutive violation count
    private var consecutiveViolations = 0

    // Hash result cache (for result reuse within TTL)
    private data class CachedAnalysisResult(
        val hash: String,
        val constraintMatches: List<ConstraintMatch>,
        val timestamp: Long
    )
    private val hashResultCache = mutableMapOf<String, CachedAnalysisResult>()

    // Cleanup job for cache
    private var cacheCleanupJob: Job? = null

    // Analysis job
    private var analysisJob: Job? = null

    // Current app being monitored
    var currentPackageName: String? = null

    /**
     * Start periodic screen analysis
     */
    fun startAnalysis(
        packageName: String,
        constraints: List<SessionConstraint>,
        onViolation: (SessionConstraint, Double) -> Unit
    ) {
        currentPackageName = packageName

        if (constraints.isEmpty()) {
            Log.d(TAG, "No constraints to analyze")
            return
        }

        analysisJob?.cancel()
        cacheCleanupJob?.cancel()
        consecutiveViolations = 0

        // Start cache cleanup timer
        cacheCleanupJob = scope.launch {
            while (isActive) {
                delay(HASH_RESULT_CACHE_TTL_MS)
                cleanupHashCache()
            }
        }

        analysisJob = scope.launch {
            while (isActive) {
                delay(analysisIntervalMs)

                val result = analyzeScreen(constraints)
                _lastAnalysisResult.value = result

                // Check for violations
                val violations = result.constraintMatches.filter { it.isViolation }

                if (violations.isNotEmpty()) {
                    // Find the highest confidence violation
                    val worstViolation = violations.maxByOrNull { it.confidence }

                    if (worstViolation != null) {
                        consecutiveViolations++

                        // Determine if we should take action
                        val shouldAct = when {
                            worstViolation.confidence >= CONFIDENCE_HIGH -> {
                                // High confidence - act on first violation
                                true
                            }
                            worstViolation.confidence >= CONFIDENCE_MEDIUM && consecutiveViolations >= 2 -> {
                                // Medium confidence - need 2 consecutive
                                true
                            }
                            else -> {
                                // Low confidence - just warn, don't force
                                false
                            }
                        }

                        if (shouldAct) {
                            onViolation(worstViolation.constraint, worstViolation.confidence)
                        }
                    }
                } else {
                    // No violations - reset counter
                    consecutiveViolations = 0
                }
            }
        }
    }

    /**
     * Stop periodic analysis
     */
    fun stopAnalysis() {
        analysisJob?.cancel()
        analysisJob = null
        cacheCleanupJob?.cancel()
        cacheCleanupJob = null
        _isAnalyzing.value = false
    }

    /**
     * Clean up expired entries from hash result cache
     */
    private fun cleanupHashCache() {
        val now = System.currentTimeMillis()
        hashResultCache.entries.removeIf { (_, cached) ->
            now - cached.timestamp > HASH_RESULT_CACHE_TTL_MS
        }
    }

    /**
     * Analyze current screen against constraints
     */
    suspend fun analyzeScreen(constraints: List<SessionConstraint>): ScreenAnalysisResult {
        _isAnalyzing.value = true

        Log.d(TAG, "═══════════════════════════════════════")
        Log.d(TAG, "🔍 Starting screen analysis")
        Log.d(TAG, "📋 Constraints (${constraints.size}):")
        constraints.forEachIndexed { index, c ->
            Log.d(TAG, "   ${index + 1}. [${c.type}] ${c.description}")
        }
        Log.d(TAG, "═══════════════════════════════════════")

        try {
            val totalStart = System.currentTimeMillis()

            // Dismiss all toasts before screenshot
            Log.d(TAG, "📴 Dismissing all toasts...")
            dismissAllToasts()

            // Capture screenshot with delay to avoid toast
            Log.d(TAG, "📸 Capturing screenshot (delay ${TOAST_DISMISS_DELAY_MS}ms)...")
            val screenshotStart = System.currentTimeMillis()
            val screenshot = captureScreenshotWithDelay()
            val screenshotDuration = System.currentTimeMillis() - screenshotStart

            if (screenshot == null) {
                Log.e(TAG, "❌ Failed to capture screenshot!")
                showToast("截图失败")
                return ScreenAnalysisResult(
                    timestamp = System.currentTimeMillis(),
                    success = false,
                    errorMessage = "Failed to capture screenshot",
                    constraintMatches = emptyList()
                )
            }
            Log.d(TAG, "✅ Screenshot captured: ${screenshot.width}x${screenshot.height} (${screenshotDuration}ms)")

            // Process screenshot: scale + mutable copy
            Log.d(TAG, "⚙️ Processing screenshot (scale to max $MAX_LONG_EDGE_PX px)...")
            val processStart = System.currentTimeMillis()
            val processedBitmap = processScreenshot(screenshot)
            if (screenshot != processedBitmap) {
                screenshot.recycle()
            }
            val processDuration = System.currentTimeMillis() - processStart
            Log.d(TAG, "✅ Screenshot processed: ${processedBitmap.width}x${processedBitmap.height} (${processDuration}ms)")

            // Compute hash after processing
            val hash = computeHash(processedBitmap)
            Log.d(TAG, "📝 Screenshot hash: ${hash.take(12)}...")

            // Check hash result cache first (reuse previous AI result)
            val cachedResult = hashResultCache[hash]
            if (cachedResult != null) {
                val age = System.currentTimeMillis() - cachedResult.timestamp
                if (age <= HASH_RESULT_CACHE_TTL_MS) {
                    Log.d(TAG, "♻️ Reusing cached AI result (age: ${age}ms)")
                    val violations = cachedResult.constraintMatches.filter { it.isViolation }
                    if (violations.isEmpty()) {
                        val reason = cachedResult.constraintMatches.firstOrNull()?.reason?.take(20) ?: "无"
                        showToast("♻️ 符合规则: $reason")
                    } else {
                        val reason = violations.firstOrNull()?.reason?.take(20) ?: "未知"
                        showToast("♻️ 违规: $reason")
                    }
                    processedBitmap.recycle()
                    return ScreenAnalysisResult(
                        timestamp = System.currentTimeMillis(),
                        success = true,
                        screenshotHash = hash,
                        isReusedFromCache = true,
                        constraintMatches = cachedResult.constraintMatches
                    )
                } else {
                    Log.d(TAG, "🗑️ Cache expired (age: ${age}ms), re-analyzing")
                }
            }

            // Call AI to analyze
            Log.d(TAG, "🤖 Calling AI analysis...")
            val aiStart = System.currentTimeMillis()
            val matches = callAIAnalysis(processedBitmap, constraints)
            val aiDuration = System.currentTimeMillis() - aiStart
            Log.d(TAG, "✅ AI analysis complete (${aiDuration}ms)")

            // Log violation results
            val totalDuration = System.currentTimeMillis() - totalStart
            Log.d(TAG, "📊 Total analysis time: ${totalDuration}ms")

            // Log violation results
            val violations = matches.filter { it.isViolation }
            if (violations.isEmpty()) {
                Log.d(TAG, "✅ No violations detected")
                val reason = matches.firstOrNull()?.reason?.take(20) ?: "无"
                showToast("✅ 符合规则: $reason")
            } else {
                Log.w(TAG, "⚠️ VIOLATIONS DETECTED (${violations.size}):")
                violations.forEach { v ->
                    Log.w(TAG, "   - ${v.constraint.description} (confidence: ${v.confidence})")
                }
                val reason = violations.firstOrNull()?.reason?.take(20) ?: "未知"
                showToast("⚠️ 违规: $reason")
            }

            // Save rule records with screenshot
            val packageName = currentPackageName
            val bitmapToSave = processedBitmap // Keep reference for screenshot saving
            if (packageName != null && matches.isNotEmpty()) {
                try {
                    for (match in matches) {
                        val record = RuleRecord(
                            sessionId = 0, // TODO: get actual session ID
                            appName = packageName,
                            packageName = packageName,
                            screenshotHash = hash,
                            constraintId = match.constraint.id?.toLongOrNull(),
                            constraintType = match.constraint.type,
                            constraintContent = match.constraint.description,
                            isConditionMatched = !match.isViolation,
                            aiResult = match.reason,
                            confidence = match.confidence,
                            elapsedTimeMs = aiDuration
                        )
                        val savedRecord = ruleRecordRepository.saveRecord(record)

                        // Save screenshot for the record (synchronous, before bitmap is recycled)
                        ruleRecordRepository.saveScreenshotForRecord(savedRecord.id, bitmapToSave, false)
                        Log.d(TAG, "💾 Saved screenshot for record: ${savedRecord.id}")

                        Log.d(TAG, "💾 Saved rule record for constraint: ${match.constraint.description}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to save rule records", e)
                }
            }

            // Cache the result
            if (matches.isNotEmpty()) {
                hashResultCache[hash] = CachedAnalysisResult(hash, matches, System.currentTimeMillis())
                Log.d(TAG, "💾 Cached result (${hashResultCache.size} entries)")
            }

            // Recycle bitmap AFTER saving is complete
            processedBitmap.recycle()

            return ScreenAnalysisResult(
                timestamp = System.currentTimeMillis(),
                success = true,
                screenshotHash = hash,
                constraintMatches = matches
            )

        } catch (e: Exception) {
            // Don't show toast for cancelled coroutines (user left app)
            if (e.message?.contains("cancelled", ignoreCase = true) == true) {
                Log.d(TAG, "Analysis cancelled (user left app)")
                return ScreenAnalysisResult(
                    timestamp = System.currentTimeMillis(),
                    success = false,
                    errorMessage = "Cancelled",
                    constraintMatches = emptyList()
                )
            }

            Log.e(TAG, "❌ Analysis failed: ${e.message}", e)
            showToast("分析失败: ${e.message}")
            return ScreenAnalysisResult(
                timestamp = System.currentTimeMillis(),
                success = false,
                errorMessage = e.message,
                constraintMatches = emptyList()
            )
        } finally {
            _isAnalyzing.value = false
        }
    }

    /**
     * Show toast on main thread
     */
    private fun showToast(message: String) {
        try {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to show toast: ${e.message}")
        }
    }

    /**
     * Dismiss all toasts to avoid capturing them in screenshot
     */
    private fun dismissAllToasts() {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            // cancelAll() removes all active notifications, including toast notifications
            notificationManager.cancelAll()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to dismiss toasts", e)
        }
    }

    /**
     * Capture screenshot with delay to ensure toasts are dismissed
     * OPTIMIZED: Just take screenshot with delay - overlay will appear in screenshot (which is fine)
     */
    private suspend fun captureScreenshotWithDelay(): Bitmap? = suspendCancellableCoroutine { continuation ->
        // Small delay to ensure toasts are dismissed, but keep overlay visible
        Handler(Looper.getMainLooper()).postDelayed({
            val service = com.seenot.app.service.SeenotAccessibilityService.instance
            if (service != null) {
                service.takeScreenshot { bitmap ->
                    continuation.resume(bitmap, null)
                }
            } else {
                continuation.resume(null, null)
            }
        }, TOAST_DISMISS_DELAY_MS)
    }

    /**
     * Re-show floating overlay if session is still active
     * NOTE: This should be called by SessionManager when session starts/resumes,
     * NOT after every screenshot (that was causing performance issues)
     */
    fun reShowOverlayIfNeeded() {
        try {
            val service = com.seenot.app.service.SeenotAccessibilityService.instance ?: return
            val sessionManager = com.seenot.app.domain.SessionManager.getInstance(context)
            val session = sessionManager.activeSession.value

            if (session != null && session.constraints.isNotEmpty()) {
                // Re-show overlay with session info
                com.seenot.app.ui.overlay.FloatingIndicatorOverlay.show(
                    context = context,
                    appName = session.appDisplayName,
                    packageName = session.appPackageName,
                    sessionManager = sessionManager,
                    onIntentConfirmed = { /* ignore - session already exists */ }
                )
                Log.d(TAG, "Re-showed floating overlay")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to re-show overlay: ${e.message}")
        }
    }

    /**
     * Process screenshot: scale to max edge and convert to mutable bitmap
     */
    private fun processScreenshot(bitmap: Bitmap): Bitmap {
        // Scale to max edge (960px)
        val scaled = scaleBitmap(bitmap, MAX_LONG_EDGE_PX)

        // Convert to mutable bitmap if needed
        return ensureMutableBitmap(scaled)
    }

    /**
     * Scale bitmap so longer edge is at most maxEdgePx
     */
    private fun scaleBitmap(bitmap: Bitmap, maxEdgePx: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        val longerEdge = maxOf(width, height)
        if (longerEdge <= maxEdgePx) {
            return bitmap
        }

        val scale = maxEdgePx.toFloat() / longerEdge
        val newWidth = (width * scale).toInt()
        val newHeight = (height * scale).toInt()

        val matrix = Matrix()
        matrix.postScale(scale, scale)

        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    /**
     * Ensure bitmap is mutable (convert from hardware bitmap if needed)
     */
    private fun ensureMutableBitmap(bitmap: Bitmap): Bitmap {
        if (bitmap.isMutable) {
            return bitmap
        }

        // Create mutable copy
        val mutable = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        if (mutable != bitmap) {
            return mutable
        }

        // Fallback: create new bitmap with same config
        return bitmap.copy(Bitmap.Config.ARGB_8888, true)
    }

    /**
     * Call AI to analyze screen content using DashScope SDK
     */
    private suspend fun callAIAnalysis(
        screenshot: Bitmap,
        constraints: List<SessionConstraint>
    ): List<ConstraintMatch> = withContext(Dispatchers.IO) {

        // Convert bitmap to base64
        val imageBase64 = bitmapToBase64(screenshot)
        Log.d(TAG, "[AI] Image base64 size: ${imageBase64.length} chars")

        // Build prompt with constraints
        val prompt = buildAnalysisPrompt(constraints)
        Log.d(TAG, "[AI] Prompt length: ${prompt.length} chars")

        try {
            val apiKey = ApiConfig.getApiKey()
            if (apiKey.isBlank()) {
                Log.e(TAG, "[AI] API key is empty!")
                return@withContext emptyList()
            }

            Log.d(TAG, "[AI] Using DashScope SDK with model: qwen-vl-plus")

            // Build user message with image and text
            val userContent = listOf(
                mapOf("image" to "data:image/jpeg;base64,$imageBase64" as Any),
                mapOf("text" to prompt as Any)
            )

            val userMessage = MultiModalMessage.builder()
                .role(Role.USER.getValue())
                .content(userContent)
                .build()

            // Build conversation param
            val param = MultiModalConversationParam.builder()
                .apiKey(apiKey)
                .model("qwen-vl-plus")
                .message(userMessage)
                .temperature(0.3f)
                .maxTokens(1000)
                .enableThinking(false)
                .build()

            Log.d(TAG, "[AI] Calling DashScope SDK...")

            // Call SDK with timing
            val callStart = System.currentTimeMillis()
            val conversation = MultiModalConversation()
            val result: MultiModalConversationResult = conversation.call(param)
            val callDuration = System.currentTimeMillis() - callStart

            Log.d(TAG, "[AI] SDK call completed, duration: ${callDuration}ms")

            // Parse response
            val responseContent = result.output.choices[0].message.content
            if (responseContent != null && responseContent.isNotEmpty()) {
                val responseText = responseContent[0]["text"] as? String ?: ""
                Log.d(TAG, "[AI] Response text length: ${responseText.length} chars")
                Log.d(TAG, "[AI] Response (full): $responseText")

                // Parse the JSON response from AI
                val matches = parseAIResponseFromText(responseText, constraints)
                Log.d(TAG, "[AI] Parsed ${matches.size} constraint matches")
                matches
            } else {
                Log.e(TAG, "[AI] Empty response from SDK")
                emptyList()
            }

        } catch (e: NoApiKeyException) {
            Log.e(TAG, "[AI] No API key: ${e.message}", e)
            emptyList()
        } catch (e: ApiException) {
            Log.e(TAG, "[AI] API error: ${e.message}", e)
            emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "[AI] Analysis failed: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Parse AI response text (JSON format) to constraint matches
     */
    private fun parseAIResponseFromText(responseText: String, constraints: List<SessionConstraint>): List<ConstraintMatch> {
        return try {
            // Clean the response text - extract JSON from potential markdown or text wrapper
            val cleanedText = cleanJSONResponse(responseText)
            Log.d(TAG, "[AI] Cleaned response length: ${cleanedText.length} chars")

            // Use lenient parsing
            val parser = com.google.gson.JsonParser.parseString(cleanedText)

            val results = if (parser.isJsonArray) {
                parser.asJsonArray
            } else if (parser.isJsonObject) {
                // If it's an object, check if it has a "results" or "analysis" field
                val obj = parser.asJsonObject
                if (obj.has("results")) {
                    obj.getAsJsonArray("results")
                } else if (obj.has("analysis")) {
                    obj.getAsJsonArray("analysis")
                } else {
                    // Single object - wrap in array
                    com.google.gson.JsonArray().apply { add(obj) }
                }
            } else {
                Log.e(TAG, "[AI] Unexpected JSON type: ${parser}")
                return emptyList()
            }

            results.mapNotNull { element ->
                try {
                    val obj = element.asJsonObject

                    // Support multiple ID field names
                    val constraintId = obj.get("constraint_id")?.asString
                        ?: obj.get("constraintId")?.asString
                        ?: obj.get("id")?.asString
                        ?: return@mapNotNull null

                    // AI 先输出 reason（界面描述），再输出 decision（是否符合）
                    val reason = obj.get("reason")?.asString ?: ""
                    val decision = obj.get("decision")?.asString?.lowercase() ?: "unknown"

                    val constraint = constraints.find { it.id == constraintId }
                        ?: constraints.getOrNull(constraintId.toIntOrNull()?.minus(1) ?: -1)
                        ?: return@mapNotNull null

                    // Determine violation based on constraint type:
                    // - DENY: matches = violation
                    // - ALLOW: no_matches = violation
                    // - unknown = no violation (conservative)
                    val isViolation = when (constraint.type) {
                        ConstraintType.DENY -> decision == "matches"
                        ConstraintType.ALLOW -> decision == "no_matches"
                        ConstraintType.TIME_CAP -> false // handled elsewhere
                    }

                    ConstraintMatch(
                        constraint = constraint,
                        isViolation = isViolation,
                        confidence = 0.9,
                        reason = reason.ifBlank { "未知界面" }
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "[AI] Failed to parse result element: ${e.message}")
                    null
                }
            }.also { matches ->
                Log.d(TAG, "[AI] Successfully parsed ${matches.size} constraint matches")
            }
        } catch (e: Exception) {
            Log.e(TAG, "[AI] Failed to parse response: ${e.message}")
            Log.d(TAG, "[AI] Raw response for debug: ${responseText.take(500)}")
            emptyList()
        }
    }

    /**
     * Clean AI response to extract valid JSON
     * Handles markdown code blocks, extra text, etc.
     */
    private fun cleanJSONResponse(text: String): String {
        var cleaned = text.trim()

        // Remove markdown code block markers
        if (cleaned.startsWith("```")) {
            // Find the start of JSON content
            val firstNewline = cleaned.indexOf('\n')
            if (firstNewline > 0) {
                cleaned = cleaned.substring(firstNewline + 1)
            }
        }

        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length - 3)
        }

        // Also remove trailing code block markers that might be on separate lines
        cleaned = cleaned.trimEnd('`')

        // If still starts with ```, try again
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(3).trim()
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length - 3).trim()
        }

        // Try to find JSON array or object
        val jsonStart = cleaned.indexOf('[')
        val jsonStartObj = cleaned.indexOf('{')

        when {
            jsonStart >= 0 && (jsonStartObj < 0 || jsonStart < jsonStartObj) -> {
                // Array found
                val jsonEnd = cleaned.lastIndexOf(']')
                if (jsonEnd > jsonStart) {
                    cleaned = cleaned.substring(jsonStart, jsonEnd + 1)
                }
            }
            jsonStartObj >= 0 -> {
                // Object found
                // Find matching closing brace
                var braceCount = 0
                var inString = false
                var escape = false
                val start = jsonStartObj
                for (i in start until cleaned.length) {
                    val c = cleaned[i]
                    when {
                        escape -> escape = false
                        c == '\\' -> escape = true
                        c == '"' -> inString = !inString
                        !inString -> when (c) {
                            '{' -> braceCount++
                            '}' -> {
                                braceCount--
                                if (braceCount == 0) {
                                    cleaned = cleaned.substring(start, i + 1)
                                    break
                                }
                            }
                        }
                    }
                }
            }
        }

        return cleaned.trim()
    }

    /**
     * Build analysis prompt with constraints
     */
    private fun buildAnalysisPrompt(constraints: List<SessionConstraint>): String {
        val constraintsText = constraints.mapIndexed { index, constraint ->
            val typeLabel = when (constraint.type) {
                ConstraintType.ALLOW -> "只允许"
                ConstraintType.DENY -> "禁止"
                ConstraintType.TIME_CAP -> "时间限制"
            }
            "${index + 1}. [$typeLabel] ${constraint.description}"
        }.joinToString("\n")

        return """
你是屏幕场景识别AI，帮助用户保持专注。

你的任务是：判断用户当前所在的界面类型，是否违反其设定的约束。

⚠️ 核心原则：简单直接，不要过度分析！

1. **只识别界面类型**：
   - 用户在群聊界面 → 违反"禁止群"
   - 用户在文章阅读界面 → 违反"只看文章"
   - 用户在短视频界面 → 违反"不刷短视频"
   - 不要去分析内容是否"违规"，只判断界面类型

2. **忽略悬浮窗**：右侧/角落的规则提示浮层不是内容，忽略。

3. **只描述界面，不判断**：准确描述用户当前在什么界面即可。

根据以下约束：
$constraintsText

输出JSON数组：
[
  { "constraint_id": "1", "reason": "界面描述", "decision": "matches/no_matches/unknown" }
]
        """.trimIndent()
    }

    /**
     * Parse AI response
     */
    private fun parseAIResponse(response: String, constraints: List<SessionConstraint>): List<ConstraintMatch> {
        return try {
            val json = com.google.gson.JsonParser.parseString(response).asJsonObject
            val choices = json.getAsJsonArray("choices")
            if (choices == null || choices.size() == 0) return emptyList()

            val message = choices[0].asJsonObject.getAsJsonObject("message")
            val content = message?.get("content")?.asString ?: return emptyList()

            // Parse JSON array from content
            val results = com.google.gson.JsonParser.parseString(content).asJsonArray

            results.mapNotNull { element ->
                try {
                    val obj = element.asJsonObject
                    val constraintId = obj.get("constraint_id")?.asString ?: return@mapNotNull null
                    val isViolation = obj.get("is_violation")?.asBoolean ?: false
                    val confidence = obj.get("confidence")?.asDouble ?: 0.0
                    val reason = obj.get("reason")?.asString

                    val constraint = constraints.find { it.id == constraintId }
                        ?: constraints.getOrNull(constraintId.toIntOrNull() ?: -1)
                        ?: return@mapNotNull null

                    ConstraintMatch(
                        constraint = constraint,
                        isViolation = isViolation,
                        confidence = confidence,
                        reason = reason
                    )
                } catch (e: Exception) {
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse AI response", e)
            emptyList()
        }
    }

    /**
     * Convert bitmap to base64
     */
    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
        val bytes = outputStream.toByteArray()
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }

    /**
     * Compute hash of bitmap for deduplication
     * OPTIMIZED: Use sampling instead of full compression
     */
    private fun computeHash(bitmap: Bitmap): String {
        try {
            // Sample large images to improve performance (same as seenot-reborn)
            val sampleSize = maxOf(1, maxOf(bitmap.width, bitmap.height) / 100)
            val sampledBitmap = Bitmap.createScaledBitmap(
                bitmap,
                bitmap.width / sampleSize,
                bitmap.height / sampleSize,
                false
            )

            // Calculate pixel data hash directly
            val pixels = IntArray(sampledBitmap.width * sampledBitmap.height)
            sampledBitmap.getPixels(pixels, 0, sampledBitmap.width, 0, 0, sampledBitmap.width, sampledBitmap.height)

            // Simple and fast hash algorithm
            var hash = 0L
            for (pixel in pixels) {
                hash = hash * 31 + pixel.toLong()
            }

            sampledBitmap.recycle()

            // Combine dimension info and pixel hash
            return "${bitmap.width}x${bitmap.height}_${hash.hashCode()}"
        } catch (e: Exception) {
            Log.w(TAG, "Error generating screenshot hash, using fallback", e)
            // Fallback to original method if sampling fails
            return computeHashFallback(bitmap)
        }
    }

    /**
     * Fallback hash method (original implementation)
     */
    private fun computeHashFallback(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 50, outputStream)
        val bytes = outputStream.toByteArray()

        val digest = MessageDigest.getInstance("MD5")
        val hashBytes = digest.digest(bytes)
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Release resources
     */
    fun release() {
        stopAnalysis()
        scope.cancel()
        hashResultCache.clear()
    }
}

/**
 * Screen analysis result
 */
data class ScreenAnalysisResult(
    val timestamp: Long,
    val success: Boolean,
    val isDuplicate: Boolean = false,
    val isReusedFromCache: Boolean = false,
    val screenshotHash: String? = null,
    val errorMessage: String? = null,
    val constraintMatches: List<ConstraintMatch>
)

/**
 * Constraint match result
 */
data class ConstraintMatch(
    val constraint: SessionConstraint,
    val isViolation: Boolean,
    val confidence: Double,
    val reason: String?
)
