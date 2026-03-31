package com.seenot.app.ai.screen

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.hardware.display.DisplayManager
import android.view.Display
import com.seenot.app.utils.Logger
import com.seenot.app.ui.overlay.FloatingIndicatorOverlay
import com.seenot.app.ui.overlay.InterventionFeedbackDialogOverlay
import com.seenot.app.ui.overlay.IntentInputDialogOverlay
import com.seenot.app.ui.overlay.ToastOverlay
import com.seenot.app.ui.overlay.VoiceInputOverlay
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
import com.seenot.app.config.RuleRecordingPrefs
import com.seenot.app.domain.SessionConstraint
import com.seenot.app.domain.SessionManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream

/**
 * Screen Analyzer - AI vision for monitoring screen content
 *
 * Features:
 * - Dismiss all toasts before screenshot (avoid capturing overlay toasts)
 * - Scale screenshot to max 960px on longer edge
 * - Keyframe detection (skip AI calls for unchanged frames)
 * - Batch constraint checking (multiple constraints per API call)
 * - Confidence thresholds with fallback strategies
 * - Continuous confirmation (2 consecutive violations for forced actions)
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
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
        private const val BLACK_FRAME_SAMPLE_SIZE = 48
        private const val BLACK_FRAME_LUMA_THRESHOLD = 18.0
        private const val BLACK_FRAME_AVG_LUMA_THRESHOLD = 12.0
        private const val BLACK_FRAME_DARK_PIXEL_RATIO_THRESHOLD = 0.985

        // Confidence thresholds
        const val CONFIDENCE_HIGH = 0.90
        const val CONFIDENCE_MEDIUM = 0.70
        const val CONFIDENCE_LOW = 0.50

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

    // Keyframe detection: track last screenshot hash to skip unchanged frames
    private var lastQuickHash: String? = null
    private var lastViolationState: Map<String, Boolean> = emptyMap() // constraintId -> wasViolation

    // Analysis job
    private var analysisJob: Job? = null

    // Current app being monitored
    var currentPackageName: String? = null
    var currentAppDisplayName: String? = null

    /**
     * Start periodic screen analysis
     */
    fun startAnalysis(
        packageName: String,
        displayName: String,
        constraints: List<SessionConstraint>,
        onViolation: (SessionConstraint, Double) -> Unit
    ) {
        currentPackageName = packageName
        currentAppDisplayName = displayName

        if (constraints.isEmpty()) {
            Logger.d(TAG, "No active constraints to analyze")
            return
        }

        val activeConstraints = constraints

        analysisJob?.cancel()
        consecutiveViolations = 0

        analysisJob = scope.launch {
            while (isActive) {
                delay(analysisIntervalMs)

                val deviceState = getDeviceAnalysisState()
                if (!deviceState.shouldAnalyze) {
                    Logger.d(
                        TAG,
                        "⏸️ Device state unsuitable for analysis, skipping frame " +
                            "(interactive=${deviceState.isInteractive}, " +
                            "deviceLocked=${deviceState.isDeviceLocked}, " +
                            "keyguardLocked=${deviceState.isKeyguardLocked}, " +
                            "displayState=${deviceState.displayState})"
                    )
                    resetTransientAnalysisState()
                    continue
                }

                val blockingOverlay = getBlockingOverlayName()
                if (blockingOverlay != null) {
                    Logger.d(TAG, "⏸️ Blocking overlay visible ($blockingOverlay), skipping screenshot analysis")
                    continue
                }

                // Capture screenshot for hash comparison FIRST (before full processing)
                val screenshot = captureScreenshotWithDelay() ?: continue
                if (isLikelyBlackFrame(screenshot)) {
                    Logger.d(TAG, "⏸️ Likely black/invalid frame detected, skipping screenshot analysis")
                    screenshot.recycle()
                    resetTransientAnalysisState()
                    continue
                }
                val quickHash = computeQuickHash(screenshot)

                // Keyframe detection: if screen hasn't changed, skip AI call but preserve violation handling
                val previousHash = lastQuickHash
                val isSameFrame = previousHash != null && quickHash == previousHash

                if (isSameFrame) {
                    screenshot.recycle()
                    Logger.d(TAG, "🖼️ Same frame (hash: ${quickHash.take(12)}...), skipping AI")

                    // 画面没变但上次有 violation？这次还要干预
                    val previousViolations = lastViolationState.filter { it.value }.keys
                    if (previousViolations.isNotEmpty()) {
                        Logger.d(TAG, "🔄 Previous violations still active: ${previousViolations.size}, re-triggering")
                        for (constraintId in previousViolations) {
                            val result = _lastAnalysisResult.value ?: continue
                            val match = result.constraintMatches.find { it.constraint.id == constraintId }
                            if (match != null) {
                                consecutiveViolations++
                                val shouldAct = when {
                                    match.confidence >= CONFIDENCE_HIGH -> true
                                    match.confidence >= CONFIDENCE_MEDIUM && consecutiveViolations >= 2 -> true
                                    else -> false
                                }
                                if (shouldAct) {
                                    onViolation(match.constraint, match.confidence)
                                }
                            }
                        }
                    }
                    continue
                }

                lastQuickHash = quickHash
                Logger.d(TAG, "🆕 New frame (hash: ${quickHash.take(12)}...), analyzing")

                val result = analyzeScreen(activeConstraints, screenshot)
                _lastAnalysisResult.value = result

                // Save violation state for keyframe detection
                lastViolationState = result.constraintMatches
                    .filter { it.isViolation }
                    .associate { it.constraint.id to true }

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
        scope.cancel()
        _isAnalyzing.value = false
        // Reset keyframe detection state
        lastQuickHash = null
        lastViolationState = emptyMap()
        consecutiveViolations = 0
    }

    private fun getBlockingOverlayName(): String? {
        return when {
            InterventionFeedbackDialogOverlay.isShowing() -> "InterventionFeedbackDialogOverlay"
            IntentInputDialogOverlay.isShowing() -> "IntentInputDialogOverlay"
            else -> null
        }
    }

    private fun resetTransientAnalysisState() {
        lastQuickHash = null
        lastViolationState = emptyMap()
        consecutiveViolations = 0
        _lastAnalysisResult.value = null
    }

    private fun getDeviceAnalysisState(): DeviceAnalysisState {
        val powerManager = context.getSystemService(PowerManager::class.java)
        val keyguardManager = context.getSystemService(KeyguardManager::class.java)
        val displayManager = context.getSystemService(DisplayManager::class.java)
        val displayState = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)?.state

        val isInteractive = powerManager?.isInteractive == true
        val isDeviceLocked = keyguardManager?.isDeviceLocked ?: true
        val isKeyguardLocked = keyguardManager?.isKeyguardLocked ?: true
        val isDisplayUsable = displayState != null && displayState != Display.STATE_OFF

        return DeviceAnalysisState(
            shouldAnalyze = isInteractive && !isDeviceLocked && !isKeyguardLocked && isDisplayUsable,
            isInteractive = isInteractive,
            isDeviceLocked = isDeviceLocked,
            isKeyguardLocked = isKeyguardLocked,
            displayState = displayState
        )
    }

    /**
     * Analyze current screen against constraints
     * @param preCapturedScreenshot Optional screenshot already captured (for keyframe detection loop reuse)
     */
    suspend fun analyzeScreen(
        constraints: List<SessionConstraint>,
        preCapturedScreenshot: Bitmap? = null
    ): ScreenAnalysisResult {
        _isAnalyzing.value = true

        // Track bitmap for proper cleanup
        var bitmapToRecycle: Bitmap? = null

        Logger.d(TAG, "═══════════════════════════════════════")
        Logger.d(TAG, "🔍 Starting screen analysis")
        Logger.d(TAG, "📋 Constraints (${constraints.size}):")
        constraints.forEachIndexed { index, c ->
            Logger.d(TAG, "   ${index + 1}. [${c.type}] ${c.description}")
        }
        Logger.d(TAG, "═══════════════════════════════════════")

        try {
            val totalStart = System.currentTimeMillis()

            // Dismiss all toasts before screenshot
            Logger.d(TAG, "📴 Dismissing all toasts...")
            dismissAllToasts()

            // Use pre-captured screenshot if provided (from keyframe detection loop)
            // Otherwise capture a new one
            val screenshot: Bitmap?
            val screenshotDuration: Long
            if (preCapturedScreenshot != null) {
                screenshot = preCapturedScreenshot
                screenshotDuration = 0
                Logger.d(TAG, "📸 Using pre-captured screenshot: ${screenshot.width}x${screenshot.height}")
            } else {
                Logger.d(TAG, "📸 Capturing screenshot (delay ${TOAST_DISMISS_DELAY_MS}ms)...")
                val screenshotStart = System.currentTimeMillis()
                screenshot = captureScreenshotWithDelay()
                screenshotDuration = System.currentTimeMillis() - screenshotStart

                if (screenshot == null) {
                    Logger.e(TAG, "❌ Failed to capture screenshot!")
                    showToast("截图失败")
                    return ScreenAnalysisResult(
                        timestamp = System.currentTimeMillis(),
                        success = false,
                        errorMessage = "Failed to capture screenshot",
                        constraintMatches = emptyList()
                    )
                }
                Logger.d(TAG, "✅ Screenshot captured: ${screenshot.width}x${screenshot.height} (${screenshotDuration}ms)")
            }

            // Process screenshot: scale + mutable copy
            Logger.d(TAG, "⚙️ Processing screenshot (scale to max $MAX_LONG_EDGE_PX px)...")
            val processStart = System.currentTimeMillis()
            val processedBitmap = processScreenshot(screenshot)
            bitmapToRecycle = processedBitmap
            if (screenshot != processedBitmap) {
                screenshot.recycle()
            }
            val processDuration = System.currentTimeMillis() - processStart
            Logger.d(TAG, "✅ Screenshot processed: ${processedBitmap.width}x${processedBitmap.height} (${processDuration}ms)")

            // Call AI to analyze
            Logger.d(TAG, "🤖 Calling AI analysis...")
            val aiStart = System.currentTimeMillis()
            val matches = callAIAnalysis(processedBitmap, constraints, currentAppDisplayName ?: "", currentPackageName ?: "")
            val aiDuration = System.currentTimeMillis() - aiStart
            Logger.d(TAG, "✅ AI analysis complete (${aiDuration}ms)")

            // Log violation results
            val totalDuration = System.currentTimeMillis() - totalStart
            Logger.d(TAG, "📊 Total analysis time: ${totalDuration}ms")

            // Get SessionManager instance once
            val sessionManager = com.seenot.app.domain.SessionManager.getInstance(context)

            // Update content match states in SessionManager
            matches.forEach { match ->
                // For DENY constraints: matching = in forbidden content (but we still track it)
                val isInTargetContent = when (match.constraint.type) {
                    ConstraintType.DENY -> match.isViolation // Violating DENY = in forbidden content
                    ConstraintType.TIME_CAP -> false // Legacy, not used
                }
                sessionManager.updateContentMatchState(match.constraint.id, isInTargetContent)
            }

            // Log violation results
            val violations = matches.filter { it.isViolation }
            if (violations.isEmpty()) {
                Logger.d(TAG, "✅ No violations detected")
            } else {
                Logger.w(TAG, "⚠️ VIOLATIONS DETECTED (${violations.size}):")
                violations.forEach { v ->
                    Logger.w(TAG, "   - ${v.constraint.description} (confidence: ${v.confidence})")
                }
            }

            // Show toast based on constraint types
            showAnalysisToast(matches, constraints)

            // Save rule records with screenshot
            val packageName = currentPackageName
            val bitmapToSave = processedBitmap // Keep reference for screenshot saving

            // Get session ID from sessionManager
            val sessionId = sessionManager.activeSession.value?.sessionId ?: 0L
            
            if (packageName != null && matches.isNotEmpty()) {
                try {
                    for (match in matches) {
                        // For DENY: isConditionMatched = !isViolation (true = safe, false = violates)
                        // For TIME_CAP: isConditionMatched = isInScope (true = in_scope, false = out_of_scope)
                        val isConditionMatched = when (match.constraint.type) {
                            ConstraintType.TIME_CAP -> {
                                // Check if in scope from SessionManager state
                                sessionManager.activeSession.value?.let { session ->
                                    session.constraintTimeRemaining[match.constraint.id]?.let { remaining ->
                                        remaining > 0
                                    }
                                } ?: false
                            }
                            else -> !match.isViolation
                        }

                        val record = RuleRecord(
                            sessionId = sessionId,
                            appName = packageName,
                            packageName = packageName,
                            screenshotHash = lastQuickHash ?: "unknown",
                            constraintId = match.constraint.id.toLongOrNull(),
                            constraintType = match.constraint.type,
                            constraintContent = match.constraint.description,
                            isConditionMatched = isConditionMatched,
                            aiResult = match.reason,
                            confidence = match.confidence,
                            elapsedTimeMs = aiDuration
                        )
                        val savedRecord = ruleRecordRepository.saveRecord(record)

                        // Save screenshot for the record (synchronous, before bitmap is recycled)
                        ruleRecordRepository.saveScreenshotForRecord(savedRecord.id, bitmapToSave, false)
                        Logger.d(TAG, "💾 Saved screenshot for record: ${savedRecord.id}")

                        Logger.d(TAG, "💾 Saved rule record for constraint: ${match.constraint.description}")
                    }
                } catch (e: Exception) {
                    Logger.e(TAG, "Failed to save rule records", e)
                }
            }

            // Recycle bitmap AFTER saving is complete
            bitmapToRecycle?.recycle()
            bitmapToRecycle = null

            return ScreenAnalysisResult(
                timestamp = System.currentTimeMillis(),
                success = true,
                screenshotHash = lastQuickHash,
                constraintMatches = matches
            )

        } catch (e: Exception) {
            // Don't show toast for cancelled coroutines (user left app)
            if (e.message?.contains("cancelled", ignoreCase = true) == true) {
                Logger.d(TAG, "Analysis cancelled (user left app)")
                return ScreenAnalysisResult(
                    timestamp = System.currentTimeMillis(),
                    success = false,
                    errorMessage = "Cancelled",
                    constraintMatches = emptyList()
                )
            }

            Logger.e(TAG, "❌ Analysis failed: ${e.message}", e)
            showToast("分析失败: ${e.message}")
            return ScreenAnalysisResult(
                timestamp = System.currentTimeMillis(),
                success = false,
                errorMessage = e.message,
                constraintMatches = emptyList()
            )
        } finally {
            _isAnalyzing.value = false
            bitmapToRecycle?.recycle()
            bitmapToRecycle = null
        }
    }

    /**
     * Show toast on main thread
     */
    private fun showToast(message: String) {
        try {
            Handler(Looper.getMainLooper()).post {
                ToastOverlay.show(context, message)
            }
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to show toast: ${e.message}")
        }
    }

    /**
     * Show analysis result toast based on constraint types
     */
    private fun showAnalysisToast(matches: List<ConstraintMatch>, allConstraints: List<SessionConstraint>) {
        // Separate by constraint type
        val violations = matches.filter { it.isViolation }
        val timeCapMatches = matches.filter { it.constraint.type == ConstraintType.TIME_CAP }
        val denyAllowMatches = matches.filter { it.constraint.type != ConstraintType.TIME_CAP }

        // Build toast message - always give feedback for any analysis result
        val message = buildString {
            // Show violations first (DENY only)
            if (violations.isNotEmpty()) {
                val reason = violations.firstOrNull()?.reason?.take(20) ?: "未知"
                append("⚠️ 违规: $reason")
            } else if (denyAllowMatches.isNotEmpty()) {
                val reason = denyAllowMatches.firstOrNull()?.reason?.take(20) ?: "无"
                append("✅ 符合规则: $reason")
            }

            // Show TIME_CAP status - always show, regardless of in/out of scope
            if (timeCapMatches.isNotEmpty()) {
                val inScopeCount = timeCapMatches.count { match ->
                    match.reason?.contains("in_scope", ignoreCase = true) == true
                }

                if (isNotEmpty()) append(" | ")
                if (inScopeCount > 0) {
                    val reason = timeCapMatches.firstOrNull()?.reason?.take(10) ?: ""
                    append("⏱️ $reason 计时中")
                } else {
                    val reason = timeCapMatches.firstOrNull()?.reason?.take(10) ?: ""
                    append("⏱️ $reason 范围外")
                }
            }
        }

        if (message.isNotEmpty()) {
            showToast(message)
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
            Logger.w(TAG, "Failed to dismiss toasts", e)
        }
    }

    /**
     * Capture screenshot with delay to ensure toasts are dismissed
     * Skip the capture entirely if one of SeeNot's blocking overlays is visible.
     */
    private suspend fun captureScreenshotWithDelay(): Bitmap? = suspendCancellableCoroutine { continuation ->
        // Small delay to ensure toasts are dismissed.
        Handler(Looper.getMainLooper()).postDelayed({
            val blockingOverlay = getBlockingOverlayName()
            if (blockingOverlay != null) {
                Logger.d(TAG, "⏸️ Blocking overlay appeared before capture ($blockingOverlay), cancelling screenshot")
                continuation.resume(null, null)
                return@postDelayed
            }

            val svc = com.seenot.app.service.SeenotAccessibilityService.instance
            if (svc != null) {
                svc.takeScreenshot { bitmap ->
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
            // Just check that service is available, don't assign
            com.seenot.app.service.SeenotAccessibilityService.instance ?: return
            val sessionManager = com.seenot.app.domain.SessionManager.getInstance(context)
            val session = sessionManager.activeSession.value

            if (session != null && session.constraints.isNotEmpty()) {
                com.seenot.app.ui.overlay.FloatingIndicatorOverlay.showWithConstraints(
                    context = context,
                    appName = session.appDisplayName,
                    packageName = session.appPackageName,
                    sessionManager = sessionManager,
                    constraints = session.constraints,
                    onTapToReopen = { /* handled by service */ }
                )
                Logger.d(TAG, "Re-showed floating overlay")
            }
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to re-show overlay: ${e.message}")
        }
    }

    /**
     * Process screenshot: scale to max edge and convert to mutable bitmap
     */
    private fun processScreenshot(bitmap: Bitmap): Bitmap {
        // Scale to max edge (960px)
        val scaled = scaleBitmap(bitmap, MAX_LONG_EDGE_PX)

        // Convert to mutable bitmap if needed
        val mutable = ensureMutableBitmap(scaled)

        // Calculate scale factor for overlay mask
        val scaleFactor = if (bitmap.width > MAX_LONG_EDGE_PX || bitmap.height > MAX_LONG_EDGE_PX) {
            MAX_LONG_EDGE_PX.toFloat() / maxOf(bitmap.width, bitmap.height)
        } else {
            1f
        }

        // Draw white mask over overlay area to avoid AI confusion
        drawOverlayMask(mutable, scaleFactor)

        return mutable
    }

    /**
     * Draw mask over floating overlay areas in screenshot
     * Uses white in light mode, black in dark mode
     * @param scaleFactor the scaling factor applied to the original screenshot
     */
    private fun drawOverlayMask(bitmap: Bitmap, scaleFactor: Float) {
        try {
            val canvas = Canvas(bitmap)

            // Detect dark mode and use appropriate mask color
            val isDarkMode = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES
            val maskColor = if (isDarkMode) Color.BLACK else Color.WHITE

            Logger.d(TAG, "Dark mode: $isDarkMode, using mask color: ${if (isDarkMode) "BLACK" else "WHITE"}")

            val paint = Paint().apply {
                color = maskColor
                style = Paint.Style.FILL
            }

            // Mask FloatingIndicatorOverlay
            val indicatorBounds = FloatingIndicatorOverlay.getCurrentOverlayBounds()
            if (indicatorBounds != null) {
                val scaledLeft = (indicatorBounds.left * scaleFactor).toInt()
                val scaledTop = (indicatorBounds.top * scaleFactor).toInt()
                val scaledRight = (indicatorBounds.right * scaleFactor).toInt()
                val scaledBottom = (indicatorBounds.bottom * scaleFactor).toInt()

                Logger.d(TAG, "Masking FloatingIndicatorOverlay at: left=$scaledLeft, top=$scaledTop, right=$scaledRight, bottom=$scaledBottom")

                canvas.drawRect(
                    scaledLeft.toFloat(),
                    scaledTop.toFloat(),
                    scaledRight.toFloat(),
                    scaledBottom.toFloat(),
                    paint
                )
            }

            // Mask VoiceInputOverlay
            val voiceBounds = VoiceInputOverlay.getCurrentOverlayBounds()
            if (voiceBounds != null) {
                val scaledLeft = (voiceBounds.left * scaleFactor).toInt()
                val scaledTop = (voiceBounds.top * scaleFactor).toInt()
                val scaledRight = (voiceBounds.right * scaleFactor).toInt()
                val scaledBottom = (voiceBounds.bottom * scaleFactor).toInt()

                Logger.d(TAG, "Masking VoiceInputOverlay at: left=$scaledLeft, top=$scaledTop, right=$scaledRight, bottom=$scaledBottom")

                canvas.drawRect(
                    scaledLeft.toFloat(),
                    scaledTop.toFloat(),
                    scaledRight.toFloat(),
                    scaledBottom.toFloat(),
                    paint
                )
            }

            if (indicatorBounds == null && voiceBounds == null) {
                Logger.d(TAG, "No overlay bounds available for masking")
            } else {
                Logger.d(TAG, "Overlay masks drawn successfully")
            }
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to draw overlay mask: ${e.message}")
        }
    }

    /**
     * Scale bitmap so longer edge is at most maxEdgePx
     * Always returns a copy to avoid aliasing with the source bitmap
     */
    private fun scaleBitmap(bitmap: Bitmap, maxEdgePx: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        val longerEdge = maxOf(width, height)
        if (longerEdge <= maxEdgePx) {
            // Return a copy even when no scaling needed - caller may recycle the original
            return bitmap.copy(Bitmap.Config.ARGB_8888, false)
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

    private fun isLikelyBlackFrame(bitmap: Bitmap): Boolean {
        var sampledBitmap: Bitmap? = null
        return try {
            sampledBitmap = Bitmap.createScaledBitmap(
                bitmap,
                BLACK_FRAME_SAMPLE_SIZE,
                BLACK_FRAME_SAMPLE_SIZE,
                false
            )

            val pixels = IntArray(BLACK_FRAME_SAMPLE_SIZE * BLACK_FRAME_SAMPLE_SIZE)
            sampledBitmap.getPixels(
                pixels,
                0,
                BLACK_FRAME_SAMPLE_SIZE,
                0,
                0,
                BLACK_FRAME_SAMPLE_SIZE,
                BLACK_FRAME_SAMPLE_SIZE
            )

            var darkPixels = 0
            var totalLuma = 0.0

            for (pixel in pixels) {
                val luma = (
                    Color.red(pixel) * 0.299 +
                        Color.green(pixel) * 0.587 +
                        Color.blue(pixel) * 0.114
                    )
                totalLuma += luma
                if (luma <= BLACK_FRAME_LUMA_THRESHOLD) {
                    darkPixels++
                }
            }

            val avgLuma = totalLuma / pixels.size
            val darkRatio = darkPixels.toDouble() / pixels.size
            val isBlackFrame =
                darkRatio >= BLACK_FRAME_DARK_PIXEL_RATIO_THRESHOLD &&
                    avgLuma <= BLACK_FRAME_AVG_LUMA_THRESHOLD

            if (isBlackFrame) {
                Logger.d(
                    TAG,
                    "Black frame metrics: avgLuma=${"%.2f".format(avgLuma)}, " +
                        "darkRatio=${"%.4f".format(darkRatio)}"
                )
            }

            isBlackFrame
        } catch (e: Exception) {
            Logger.w(TAG, "Black frame detection failed, treating frame as valid", e)
            false
        } finally {
            sampledBitmap?.recycle()
        }
    }

    /**
     * Call AI to analyze screen content using DashScope SDK
     */
    private suspend fun callAIAnalysis(
        screenshot: Bitmap,
        constraints: List<SessionConstraint>,
        appName: String = "",
        packageName: String = ""
    ): List<ConstraintMatch> = withContext(Dispatchers.IO) {

        // Convert bitmap to base64
        val imageBase64 = bitmapToBase64(screenshot)
        Logger.d(TAG, "[AI] Image base64 size: ${imageBase64.length} chars")

        // Build prompt with constraints
        val prompt = buildAnalysisPrompt(constraints, appName, packageName)
        Logger.d(TAG, "[AI] Prompt length: ${prompt.length} chars")

        try {
            val apiKey = ApiConfig.getApiKey()
            if (apiKey.isBlank()) {
                Logger.e(TAG, "[AI] API key is empty!")
                return@withContext emptyList()
            }

            Logger.d(TAG, "[AI] Using DashScope SDK with model: qwen3.5-plus")

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
                .model("qwen3.5-plus")
                .message(userMessage)
                .temperature(0.3f)
                .maxTokens(1000)
                .enableThinking(false)
                .build()

            Logger.d(TAG, "[AI] Calling DashScope SDK...")

            // Call SDK with timing
            val callStart = System.currentTimeMillis()
            val conversation = MultiModalConversation()
            val result: MultiModalConversationResult = conversation.call(param)
            val callDuration = System.currentTimeMillis() - callStart

            Logger.d(TAG, "[AI] SDK call completed, duration: ${callDuration}ms")

            // Parse response
            val responseContent = result.output.choices[0].message.content
            if (responseContent != null && responseContent.isNotEmpty()) {
                val responseText = responseContent[0]["text"] as? String ?: ""
                Logger.d(TAG, "[AI] Response text length: ${responseText.length} chars")
                Logger.d(TAG, "[AI] Response (full): $responseText")

                // Parse the JSON response from AI
                val matches = parseAIResponseFromText(responseText, constraints)
                Logger.d(TAG, "[AI] Parsed ${matches.size} constraint matches")
                matches
            } else {
                Logger.e(TAG, "[AI] Empty response from SDK")
                emptyList()
            }

        } catch (e: NoApiKeyException) {
            Logger.e(TAG, "[AI] No API key: ${e.message}", e)
            emptyList()
        } catch (e: ApiException) {
            Logger.e(TAG, "[AI] API error: ${e.message}", e)
            emptyList()
        } catch (e: Exception) {
            Logger.e(TAG, "[AI] Analysis failed: ${e.message}", e)
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
            Logger.d(TAG, "[AI] Cleaned response length: ${cleanedText.length} chars")

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
                Logger.e(TAG, "[AI] Unexpected JSON type: ${parser}")
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

                    // AI 先输出 reason（界面描述），再输出 decision（是否违规）
                    val reason = obj.get("reason")?.asString ?: ""
                    val decision = obj.get("decision")?.asString?.lowercase() ?: "unknown"

                    // Extract confidence from AI response (0-100 scale from seenot-reborn)
                    // Convert to 0.0-1.0 scale for internal use
                    val rawConfidence = obj.get("confidence")?.asDouble 
                        ?: obj.get("confidence")?.asInt?.toDouble()
                        ?: 50.0 // Default to 50 if not provided
                    val confidence = rawConfidence / 100.0

                    val constraint = constraints.find { it.id == constraintId }
                        ?: constraints.getOrNull(constraintId.toIntOrNull()?.minus(1) ?: -1)
                        ?: return@mapNotNull null

                    // Determine violation based on decision and constraint type:
                    // For DENY:
                    //   - violates = violation
                    //   - safe = no violation
                    // For TIME_CAP:
                    //   - in_scope = user is in target scope (start timing, no violation)
                    //   - out_of_scope = user is not in target scope (stop timing, no violation)
                    // - unknown = no violation (conservative)
                    val isViolation = when (constraint.type) {
                        ConstraintType.TIME_CAP -> false // TIME_CAP never triggers violation
                        else -> decision == "violates"
                    }

                    // For TIME_CAP, update content match state for timing
                    if (constraint.type == ConstraintType.TIME_CAP) {
                        val isInScope = decision == "in_scope"
                        val sessionManager = SessionManager.getInstance(context)
                        sessionManager.updateContentMatchState(constraint.id, isInScope)
                    }

                    Logger.d(TAG, "[AI] Parsed constraint_id=$constraintId, decision=$decision, confidence=$rawConfidence (scaled: ${"%.2f".format(confidence)})")

                    ConstraintMatch(
                        constraint = constraint,
                        isViolation = isViolation,
                        confidence = confidence,
                        reason = reason.ifBlank { "未知界面" }
                    )
                } catch (e: Exception) {
                    Logger.w(TAG, "[AI] Failed to parse result element: ${e.message}")
                    null
                }
            }.also { matches ->
                Logger.d(TAG, "[AI] Successfully parsed ${matches.size} constraint matches")
            }
        } catch (e: Exception) {
            Logger.e(TAG, "[AI] Failed to parse response: ${e.message}")
            Logger.d(TAG, "[AI] Raw response for debug: ${responseText.take(500)}")
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
    private fun buildAnalysisPrompt(
        constraints: List<SessionConstraint>,
        appName: String = "",
        packageName: String = ""
    ): String {
        // Group constraints by type
        val denyAllowConstraints = constraints.filter { it.type != ConstraintType.TIME_CAP }
        val timeCapConstraints = constraints.filter { it.type == ConstraintType.TIME_CAP }

        // Build constraint list text
        val constraintsText = constraints.mapIndexed { index, constraint ->
            val typeLabel = when (constraint.type) {
                ConstraintType.DENY -> "禁止"
                ConstraintType.TIME_CAP -> "时间限制"
            }
            "${index + 1}. [$typeLabel] ${constraint.description}"
        }.joinToString("\n")

        // Build type-specific rules
        val typeSpecificRules = buildString {
            if (denyAllowConstraints.isNotEmpty()) {
                appendLine("3. **违规判断规则（针对 [禁止] 约束）：**")
                appendLine("   - [禁止] 约束：用户在被禁止的功能 → violates")
                appendLine("     例：[禁止] QQ空间 → 只有在QQ空间才违规，QQ群聊不违规")
                appendLine("   - **多条件约束（OR逻辑）**：如果约束描述包含多个条件（如\"朋友圈和视频号\"），判断屏幕是否包含**任一**条件")
                appendLine("     例：[禁止] 朋友圈和视频号 → 在朋友圈违规，在视频号也违规，在聊天界面不违规")
                appendLine("   - 必须精确匹配功能名称，不要泛化")
                if (timeCapConstraints.isNotEmpty()) appendLine()
            }

            if (timeCapConstraints.isNotEmpty()) {
                appendLine("3. **范围判断规则（针对 [时间限制] 约束）：**")
                appendLine("   - 判断用户是否在目标功能范围内（用于计时，不是违规判断）")
                appendLine("   - 在目标范围内 → in_scope")
                appendLine("   - 不在目标范围内 → out_of_scope")
                appendLine("   - 例：[时间限制] 小红书首页 → 在小红书首页返回 in_scope，在其他页面返回 out_of_scope")
                appendLine("   - 注意：时间限制类型永远不返回 violates/safe，只返回 in_scope/out_of_scope")
                appendLine("   - 必须精确匹配功能名称，不要泛化")
            }
        }.trimEnd()

        // Build decision values based on constraint types
        val decisionValues = buildString {
            if (denyAllowConstraints.isNotEmpty()) {
                appendLine("- violates: 违反约束（用于 [禁止]）")
                appendLine("- safe: 未违反约束（用于 [禁止]）")
            }
            if (timeCapConstraints.isNotEmpty()) {
                appendLine("- in_scope: 在目标范围内（用于 [时间限制]）")
                appendLine("- out_of_scope: 不在目标范围内（用于 [时间限制]）")
            }
            appendLine("- unknown: 无法判断")
        }.trimEnd()

        // Similar to seenot-reborn's AI_PROMPT - require confidence output
        val envInfo = buildString {
            if (appName.isNotBlank()) appendLine("- 应用名称：$appName")
            if (packageName.isNotBlank()) appendLine("- 应用包名：$packageName")
        }.trimEnd()

        return """
**当前环境：**
$envInfo

你是屏幕场景识别AI，判断用户当前行为与约束的关系。

**核心任务：**
1. 识别用户当前所在的具体功能模块
2. 根据约束类型进行相应判断
3. 输出置信度分数

**判断规则：**

1. **精确识别功能模块**，例如：
   - QQ群聊 ≠ QQ空间（完全不同的功能）
   - 微信群聊 ≠ 微信朋友圈 ≠ 微信公众号
   - 小红书图文 ≠ 小红书短视频
   - 必须准确识别当前界面属于哪个具体功能

2. **区分"入口" vs "使用中"**：
   - 看到入口/图标 → 不算使用
   - 进入详情页/播放页 → 才算使用
   - 例外：抖音/快手首页本身就是短视频流，算使用

3. **流量推荐类判断：**
   - 信息流/推荐列表中出现某内容：不算违规（用户无主动意图，是系统推荐的内容）
   - 用户主动点击并浏览该内容：才算违规（用户有主动意图，是用户主动选择的内容）

$typeSpecificRules

**当前约束：**
$constraintsText

**输出格式（严格JSON）：**
[
  {
    "constraint_id": "1",
    "reason": "用户在[应用名]-[具体功能模块]",
    "decision": "见下方说明",
    "confidence": 0-100的置信度分数
  }
]

decision取值：
$decisionValues

**重要：必须输出真实的置信度分数**
- confidence: 0-100，其中：
  - 0 = 绝对不匹配
  - 100 = 绝对匹配
  - 50 = 完全不确定
- 考虑部分匹配：部分元素匹配但不是全部，给出中间分数
- 校准原则：80分意味着你给出这个分数时有80%的正确率
- reason必须简洁，10-20个字解释置信度
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
            Logger.e(TAG, "Failed to parse AI response", e)
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
     * Compute quick hash for keyframe detection (before full processing)
     * Uses aggressive downsampling for speed - we only need to detect significant changes
     */
    private fun computeQuickHash(screenshot: Bitmap): String {
        try {
            // Very aggressive downsampling: 50x50 pixels is enough for change detection
            val sampleSize = maxOf(1, maxOf(screenshot.width, screenshot.height) / 50)
            val sampledBitmap = Bitmap.createScaledBitmap(
                screenshot,
                screenshot.width / sampleSize,
                screenshot.height / sampleSize,
                false
            )

            val pixels = IntArray(sampledBitmap.width * sampledBitmap.height)
            sampledBitmap.getPixels(pixels, 0, sampledBitmap.width, 0, 0, sampledBitmap.width, sampledBitmap.height)

            // Simple xor-based hash (faster than MD5)
            var hash = 0L
            for (pixel in pixels) {
                hash = hash xor (pixel.toLong() * 31)
            }

            sampledBitmap.recycle()

            // Include dimensions and return hex string for readability
            return "${screenshot.width}x${screenshot.height}_${hash.toULong().toString(16)}"
        } catch (e: Exception) {
            Logger.w(TAG, "Quick hash failed, using fallback", e)
            return "${screenshot.width}x${screenshot.height}_${System.currentTimeMillis()}"
        }
    }

    /**
     * Release resources
     */
    fun release() {
        stopAnalysis()
        scope.cancel()
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

private data class DeviceAnalysisState(
    val shouldAnalyze: Boolean,
    val isInteractive: Boolean,
    val isDeviceLocked: Boolean,
    val isKeyguardLocked: Boolean,
    val displayState: Int?
)
