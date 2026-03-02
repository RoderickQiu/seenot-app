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
import com.seenot.app.domain.ActiveSession
import com.seenot.app.domain.SessionConstraint
import com.seenot.app.domain.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
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
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    /**
     * Show with pre-confirmed constraints (no voice input needed)
     */
    fun showWithConstraints(constraints: List<SessionConstraint>) {
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        state = state.copy(
            hasActiveSession = true,
            displayedConstraints = constraints
        )
        observeSession()
        observeViolation()
        render()
    }

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    fun show() {
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        observeSession()
        observeViolation()
        render()
    }

    private fun observeSession() {
        scope.launch {
            sessionManager.activeSession.collectLatest { session ->
                updateState {
                    copy(
                        session = session,
                        hasActiveSession = session != null && session.constraints.isNotEmpty(),
                        displayedConstraints = if (session != null && session.constraints.isNotEmpty()) {
                            session.constraints
                        } else {
                            displayedConstraints
                        }
                    )
                }
            }
        }
    }

    private fun observeViolation() {
        scope.launch {
            sessionManager.sessionEvents.collect { event ->
                when (event) {
                    is com.seenot.app.domain.SessionEvent.ViolationDetected -> {
                        triggerViolationFeedback()
                    }
                    else -> {}
                }
            }
        }
    }

    private fun updateState(update: OverlayState.() -> OverlayState) {
        state = state.update()
        render()
    }

    private fun triggerViolationFeedback() {
        updateState { copy(isViolating = true) }

        try {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
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

        val constraint = constraints.first()

        return when (constraint.type) {
            ConstraintType.ALLOW -> {
                buildString {
                    append("允许")
                    if (constraint.description.isNotEmpty()) {
                        append(" ")
                        append(constraint.description.take(8))
                    }
                }
            }
            ConstraintType.DENY -> {
                buildString {
                    append("禁止")
                    if (constraint.description.isNotEmpty()) {
                        append(" ")
                        append(constraint.description.take(8))
                    }
                }
            }
            ConstraintType.TIME_CAP -> {
                val timeStr = constraint.timeLimitMs?.let { ms ->
                    val min = ms / 60000
                    val scopeStr = when (constraint.timeScope) {
                        com.seenot.app.data.model.TimeScope.CONTINUOUS -> "连"
                        com.seenot.app.data.model.TimeScope.PER_CONTENT -> "每"
                        else -> ""
                    }
                    "${scopeStr}${min}分"
                } ?: "不限时"

                buildString {
                    append("限时 ")
                    append(timeStr)
                }
            }
        }
    }

    private fun getTimeColor(session: ActiveSession?, hasActiveSession: Boolean): Int {
        if (hasActiveSession && session == null) {
            return timeGreenColor
        }

        session ?: return timeGreenColor

        val remaining = session.timeRemainingMs
        val limit = session.constraints
            .filter { it.timeLimitMs != null }
            .sumOf { it.timeLimitMs ?: 0L }

        if (remaining == null || limit <= 0) return timeGreenColor

        val percentage = (remaining.toFloat() / limit.toFloat()) * 100
        return when {
            percentage > 50 -> timeGreenColor
            percentage > 20 -> timeYellowColor
            else -> timeRedColor
        }
    }

    fun dismiss() {
        violationResetHandler?.removeCallbacksAndMessages(null)
        scope.cancel()
        indicatorView?.let { view ->
            try { windowManager?.removeView(view) } catch (e: Exception) { /* ignore */ }
        }
        indicatorView = null
    }

    private fun Int.dp() = (this * density).roundToInt()

    companion object {
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
    }
}
