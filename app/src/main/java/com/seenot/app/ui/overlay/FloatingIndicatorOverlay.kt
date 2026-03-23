package com.seenot.app.ui.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.seenot.app.data.model.ConstraintType
import com.seenot.app.data.model.displayLabel
import com.seenot.app.domain.ActiveSession
import com.seenot.app.domain.SessionConstraint
import com.seenot.app.domain.SessionManager
import com.seenot.app.utils.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Compact floating indicator overlay - shown after user confirms rules.
 *
 * Displays a small pill with:
 * - A small colored dot (top-left indicator) showing session status
 * - Rule summary text
 *
 * No recording functionality - all voice input happens in IntentInputDialogOverlay.
 * Tapping the indicator reopens the dialog for rule modification.
 */
class FloatingIndicatorOverlay(
    private val context: Context,
    private val appName: String,
    private val packageName: String,
    private val sessionManager: SessionManager,
    private val onTapToReopen: () -> Unit
) {
    private data class OverlayState(
        val session: ActiveSession? = null,
        val hasActiveSession: Boolean = false,
        val displayedConstraints: List<SessionConstraint>? = null,
        val isViolating: Boolean = false
    )

    private var state = OverlayState()

    private var windowManager: WindowManager? = null
    private var indicatorView: View? = null
    private var indicatorParams: WindowManager.LayoutParams? = null

    private var rootContainer: LinearLayout? = null
    private var dotView: View? = null
    private var statusTextView: TextView? = null

    private var lastX = 0
    private var lastY = 0
    private var isDragging = false
    private var violationResetHandler: Handler? = null

    private val isDarkMode: Boolean
        get() = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES

    private val primaryColor get() = if (isDarkMode) Color.parseColor("#90CAF9") else Color.parseColor("#2196F3")
    private val surfaceColor get() = if (isDarkMode) Color.parseColor("#2D2D2D") else Color.WHITE
    private val surfaceBorderColor get() = if (isDarkMode) Color.parseColor("#424242") else Color.parseColor("#E0E0E0")
    private val subtleTextColor get() = if (isDarkMode) Color.parseColor("#9E9E9E") else Color.parseColor("#666666")
    private val timeGreenColor get() = if (isDarkMode) Color.parseColor("#A5D6A7") else Color.parseColor("#4CAF50")
    private val timeYellowColor get() = if (isDarkMode) Color.parseColor("#FFE082") else Color.parseColor("#FFC107")
    private val timeRedColor get() = if (isDarkMode) Color.parseColor("#EF9A9A") else Color.parseColor("#F44336")

    private val density = context.resources.displayMetrics.density
    private var scope = CoroutineScope(Dispatchers.Main + Job())
    private var observersStarted = false

    /**
     * Show with pre-confirmed constraints (no voice input needed)
     */
    fun showWithConstraints(constraints: List<SessionConstraint>) {
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        state = state.copy(
            hasActiveSession = true,
            displayedConstraints = constraints
        )
        startObserversIfNeeded()
        render()
    }

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    fun show() {
        Logger.d("FloatingIndicator", "show() called, indicatorView=${indicatorView != null}")
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (indicatorView == null) {
            Logger.d("FloatingIndicator", "Recreating scope")
            recreateScope()
        }
        state = state.copy(hasActiveSession = true)
        startObserversIfNeeded()
        render()
    }

    private fun startObserversIfNeeded() {
        if (!observersStarted) {
            observersStarted = true
            observeSession()
            observeViolation()
        }
    }

    private fun recreateScope() {
        try { scope.cancel() } catch (e: Exception) { /* ignore */ }
        scope = CoroutineScope(Dispatchers.Main + Job())
        observersStarted = false
    }

    private fun observeSession() {
        scope.launch {
            var lastSessionId: String? = null
            var lastPausedState: Boolean? = null
            sessionManager.activeSession.collectLatest { session ->
                val sessionId = session?.constraints?.joinToString { it.id } ?: ""
                val isPaused = session?.isPaused ?: true
                val hasValidConstraints = session != null && session.constraints.isNotEmpty()

                // If there's a session, follow session state; otherwise follow manual show flag
                val shouldShow = if (session != null) {
                    !isPaused && hasValidConstraints
                } else {
                    state.hasActiveSession
                }

                if (shouldShow) {
                    if (indicatorView == null) {
                        Logger.d("FloatingIndicator", "Showing overlay: session=${session != null}, manual=${state.hasActiveSession}")
                        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                        state = state.copy(
                            session = session,
                            displayedConstraints = session?.constraints ?: state.displayedConstraints
                        )
                        render()
                    } else {
                        state = state.copy(
                            session = session,
                            displayedConstraints = session?.constraints ?: state.displayedConstraints
                        )
                    }
                } else {
                    if (indicatorView != null) {
                        Logger.d("FloatingIndicator", "Hiding overlay: session paused or ended")
                        try { windowManager?.removeView(indicatorView) } catch (e: Exception) { /* ignore */ }
                        indicatorView = null
                    }
                }

                if (sessionId != lastSessionId || isPaused != lastPausedState) {
                    Logger.d("FloatingIndicator", "State changed: sessionId=$sessionId, isPaused=$isPaused")
                    lastSessionId = sessionId
                    lastPausedState = isPaused
                }

                updateTimeDisplay(session)
            }
        }
    }

    /**
     * Update time display without full re-render
     */
    private fun updateTimeDisplay(session: com.seenot.app.domain.ActiveSession?) {
        try {
            val currentSession = state.session
            if (currentSession == null || session == null) return

            // Only update if time remaining actually changed
            if (currentSession.constraintTimeRemaining == session.constraintTimeRemaining) return

            // Update state but don't re-render
            state = state.copy(session = session)

            // Update dot color based on new time
            dotView?.let { dot ->
                val dotColor = getTimeColor(session, state.hasActiveSession)
                (dot.background as? android.graphics.drawable.GradientDrawable)?.setColor(dotColor)
            }
        } catch (e: Exception) {
            // Ignore update errors
        }
    }

    private var lastProcessedEventId: String? = null
    
    private fun observeViolation() {
        Logger.d("FloatingIndicator", "observeViolation started")
        scope.launch {
            Logger.d("FloatingIndicator", "observeViolation collecting sessionEvents")
            sessionManager.sessionEvents.distinctUntilChanged { old, new ->
                oldEventId(old) == oldEventId(new)
            }.collect { event ->
                val eventId = oldEventId(event)
                if (eventId == lastProcessedEventId) {
                    Logger.d("FloatingIndicator", "Duplicate event ignored: $eventId")
                    return@collect
                }
                lastProcessedEventId = eventId

                Logger.d("FloatingIndicator", "sessionEvents received: $event")
                when (event) {
                    is com.seenot.app.domain.SessionEvent.ViolationDetected -> {
                        triggerViolationFeedback()
                    }
                    is com.seenot.app.domain.SessionEvent.SessionEnded -> {
                        Logger.d("FloatingIndicator", "SessionEnded event received, dismissing")
                        dismiss()
                    }
                    is com.seenot.app.domain.SessionEvent.SessionCleared -> {
                        Logger.d("FloatingIndicator", "SessionCleared event received, dismissing")
                        dismiss()
                    }
                    else -> {}
                }
            }
        }
    }
    
    private fun oldEventId(event: com.seenot.app.domain.SessionEvent): String {
        return when (event) {
            is com.seenot.app.domain.SessionEvent.ShowVoiceInput -> "ShowVoiceInput_${event.packageName}"
            is com.seenot.app.domain.SessionEvent.SessionStarted -> "SessionStarted_${event.session.sessionId}"
            is com.seenot.app.domain.SessionEvent.SessionResumed -> "SessionResumed_${event.session.sessionId}"
            is com.seenot.app.domain.SessionEvent.SessionPaused -> "SessionPaused_${event.session.sessionId}"
            is com.seenot.app.domain.SessionEvent.SessionEnded -> "SessionEnded_${event.session.sessionId}"
            is com.seenot.app.domain.SessionEvent.SessionCleared -> "SessionCleared"
            is com.seenot.app.domain.SessionEvent.ConstraintsModified -> "ConstraintsModified"
            is com.seenot.app.domain.SessionEvent.ViolationDetected -> "Violation_${event.constraint.id}_${System.currentTimeMillis() / 1000}"
        }
    }

    private fun updateState(update: OverlayState.() -> OverlayState) {
        state = state.update()
        render()
    }

    private fun triggerViolationFeedback() {
        updateState { copy(isViolating = true) }

        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                // Use VibratorManager for Android 12+
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager
                val vibrator = vibratorManager.defaultVibrator
                vibrator.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
            } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                vibrator.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } catch (e: Exception) { /* ignore */ }

        violationResetHandler = Handler(Looper.getMainLooper())
        violationResetHandler?.postDelayed({
            updateState { copy(isViolating = false) }
        }, 1000)
    }

    private fun render() {
        indicatorView?.let { view ->
            try { windowManager?.removeView(view) } catch (e: Exception) { /* ignore */ }
        }

        indicatorView = buildView()
        indicatorParams = createLayoutParams()

        try {
            windowManager?.addView(indicatorView, indicatorParams)
        } catch (e: Exception) {
            android.util.Log.e("FloatingIndicator", "Failed to show overlay", e)
        }
    }

    private fun createLayoutParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.END
        x = 16.dp()
        y = 100.dp()
    }

    @SuppressLint("SetTextI18n")
    private fun buildView(): View {
        val session = state.session
        val isSessionActive = state.hasActiveSession || (session != null && session.constraints.isNotEmpty())

        rootContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8.dp(), 5.dp(), 10.dp(), 5.dp())
        }

        rootContainer?.background = GradientDrawable().apply {
            setColor(surfaceColor)
            cornerRadius = 16.dp().toFloat()
            setStroke(1, surfaceBorderColor)
        }

        // Small colored dot indicator (replaces the large icon)
        val dotSize = 10.dp()
        val dotColor = when {
            state.isViolating -> timeRedColor
            isSessionActive -> getTimeColor(session, state.hasActiveSession)
            else -> primaryColor
        }

        dotView = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dotSize, dotSize).apply {
                marginStart = 4.dp()
                marginEnd = 6.dp()
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(dotColor)
            }
        }
        rootContainer?.addView(dotView)

        // Status text
        val displayText = if (isSessionActive) {
            formatConstraints(state.displayedConstraints ?: session?.constraints)
        } else {
            "点击设置规则"
        }

        statusTextView = TextView(context).apply {
            text = displayText
            textSize = 12f
            setTextColor(if (state.isViolating) timeRedColor else subtleTextColor)
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = 2.dp()
            }
        }
        rootContainer?.addView(statusTextView)

        rootContainer?.setOnTouchListener { _, event ->
            handleTouch(event)
            true
        }

        return rootContainer!!
    }

    private fun handleTouch(event: MotionEvent) {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isDragging = false
                lastX = event.rawX.toInt()
                lastY = event.rawY.toInt()
            }
            MotionEvent.ACTION_MOVE -> {
                val deltaX = kotlin.math.abs(event.rawX.toInt() - lastX)
                val deltaY = kotlin.math.abs(event.rawY.toInt() - lastY)
                if (deltaX > 10 || deltaY > 10) {
                    isDragging = true
                    indicatorParams?.let { params ->
                        params.x = params.x + (event.rawX.toInt() - lastX)
                        params.y = params.y + (event.rawY.toInt() - lastY)
                        windowManager?.updateViewLayout(rootContainer, params)
                    }
                    lastX = event.rawX.toInt()
                    lastY = event.rawY.toInt()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!isDragging) {
                    onTapToReopen()
                }
                isDragging = false
            }
        }
    }

    private fun formatConstraints(constraints: List<SessionConstraint>?): String {
        if (constraints.isNullOrEmpty()) return "点击设置规则"

        return constraints.take(2).joinToString(" | ") { constraint ->
            when (constraint.type) {
                ConstraintType.DENY -> {
                    buildString {
                        append("✗")
                        if (constraint.description.isNotEmpty()) {
                            append(constraint.description.take(6))
                        }
                        constraint.timeLimitMs?.let { ms ->
                            val min = ms / 60000
                            val scopeStr = constraint.timeScope?.displayLabel ?: ""
                            append(" ${scopeStr}${min}分")
                        }
                    }
                }
                ConstraintType.TIME_CAP -> {
                    val timeStr = constraint.timeLimitMs?.let { ms ->
                        val min = ms / 60000
                        val scopeStr = constraint.timeScope?.displayLabel ?: ""
                        "${scopeStr}${min}分"
                    } ?: "不限时"

                    buildString {
                        append("⏱")
                        if (constraint.description.isNotEmpty()) {
                            append(constraint.description.take(4))
                        }
                        append(timeStr)
                    }
                }
            }
        } + if (constraints.size > 2) " +${constraints.size - 2}" else ""
    }

    private fun getTimeColor(session: ActiveSession?, hasActiveSession: Boolean): Int {
        if (hasActiveSession && session == null) {
            return timeGreenColor
        }

        session ?: return timeGreenColor

        // Find the lowest time percentage across all constraints
        var lowestPercentage = 100f
        for (constraint in session.constraints) {
            val limit = constraint.timeLimitMs ?: continue
            val remaining = session.constraintTimeRemaining[constraint.id] ?: continue
            val percentage = (remaining.toFloat() / limit.toFloat()) * 100
            if (percentage < lowestPercentage) {
                lowestPercentage = percentage
            }
        }

        if (lowestPercentage >= 100f) return timeGreenColor

        return when {
            lowestPercentage > 50 -> timeGreenColor
            lowestPercentage > 20 -> timeYellowColor
            else -> timeRedColor
        }
    }

    fun dismiss() {
        violationResetHandler?.removeCallbacksAndMessages(null)
        indicatorView?.let { view ->
            try { windowManager?.removeView(view) } catch (e: Exception) { /* ignore */ }
        }
        indicatorView = null
        scope.cancel()
        Logger.d("FloatingIndicator", "dismiss() called, scope cancelled")
    }

    /**
     * Get overlay position and size for masking in screenshots
     * Returns null if overlay is not visible
     */
    fun getOverlayBounds(): android.graphics.Rect? {
        val view = indicatorView ?: return null
        val params = indicatorParams ?: return null

        // Get view dimensions (may be 0 if not measured yet)
        val width = if (view.width > 0) view.width else params.width
        val height = if (view.height > 0) view.height else params.height

        if (width <= 0 || height <= 0) {
            // Try to estimate based on typical size
            val estimatedWidth = 150.dp() // Approximate width in pixels
            val estimatedHeight = 40.dp()
            // For WRAP_CONTENT, estimate based on typical text width
            val actualWidth = 150.dp()
            val actualHeight = 40.dp()
            // Convert from right-aligned coordinates (Gravity.END) to screen coordinates
            val screenWidth = (context.resources.displayMetrics.widthPixels)
            val left = screenWidth - params.x - actualWidth
            return android.graphics.Rect(
                left,
                params.y,
                left + actualWidth,
                params.y + actualHeight
            )
        }

        // Convert from right-aligned coordinates (Gravity.END) to screen coordinates
        // params.x is the distance from the right edge
        val screenWidth = (context.resources.displayMetrics.widthPixels)
        val left = screenWidth - params.x - width

        return android.graphics.Rect(
            left,
            params.y,
            left + width,
            params.y + height
        )
    }

    private fun Int.dp() = (this * density).roundToInt()

    companion object {
        @Volatile
        private var currentOverlay: FloatingIndicatorOverlay? = null

        fun show(
            context: Context,
            appName: String,
            packageName: String,
            sessionManager: SessionManager,
            onTapToReopen: () -> Unit
        ) {
            dismiss()
            val overlay = FloatingIndicatorOverlay(context, appName, packageName, sessionManager, onTapToReopen)
            overlay.show()
            currentOverlay = overlay
        }

        fun showWithConstraints(
            context: Context,
            appName: String,
            packageName: String,
            sessionManager: SessionManager,
            constraints: List<SessionConstraint>,
            onTapToReopen: () -> Unit
        ) {
            dismiss()
            val overlay = FloatingIndicatorOverlay(context, appName, packageName, sessionManager, onTapToReopen)
            overlay.showWithConstraints(constraints)
            currentOverlay = overlay
        }

        fun dismiss() {
            currentOverlay?.dismiss()
            currentOverlay = null
        }

        fun isShowing(): Boolean = currentOverlay?.indicatorView != null

        /**
         * Get current overlay bounds for masking in screenshots
         */
        fun getCurrentOverlayBounds(): android.graphics.Rect? {
            return currentOverlay?.getOverlayBounds()
        }
    }
}
