package com.seenot.app.ui.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.seenot.app.R
import com.seenot.app.config.AppLocalePrefs
import com.seenot.app.config.RuleRecordingPrefs
import com.seenot.app.data.model.ConstraintType
import com.seenot.app.data.model.TimeScope
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
import java.util.Locale

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
        val isViolating: Boolean = false,
        val isExpanded: Boolean = false,
        val matchStates: Map<String, Boolean> = emptyMap()
    )

    private data class RuleTypeStatus(
        val type: ConstraintType,
        val title: String,
        val label: String,
        val isConditionMatched: Boolean,
        val color: Int,
        val details: List<String>
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
    private val strongTextColor get() = if (isDarkMode) Color.parseColor("#F5F5F5") else Color.parseColor("#111827")
    private val safeColor get() = if (isDarkMode) Color.parseColor("#A5D6A7") else Color.parseColor("#4CAF50")
    private val warningColor get() = if (isDarkMode) Color.parseColor("#FFE082") else Color.parseColor("#FFC107")
    private val riskColor get() = if (isDarkMode) Color.parseColor("#EF9A9A") else Color.parseColor("#F44336")
    private val mutedButtonColor get() = if (isDarkMode) Color.parseColor("#3F3F46") else Color.parseColor("#F3F4F6")

    private val density = context.resources.displayMetrics.density
    private var scope = CoroutineScope(Dispatchers.Main + Job())
    private var observersStarted = false

    fun showWithConstraints(constraints: List<SessionConstraint>) {
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        state = state.copy(
            hasActiveSession = true,
            displayedConstraints = constraints
        )
        startObserversIfNeeded()
        render()
    }

    fun show() {
        Logger.d("FloatingIndicator", "show() called, indicatorView=${indicatorView != null}")
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (indicatorView == null) {
            recreateScope()
        }
        state = state.copy(hasActiveSession = true)
        startObserversIfNeeded()
        render()
    }

    private fun startObserversIfNeeded() {
        if (observersStarted) return
        observersStarted = true
        observeSession()
        observeViolation()
        observeMatchStates()
    }

    private fun recreateScope() {
        try {
            scope.cancel()
        } catch (_: Exception) {
        }
        scope = CoroutineScope(Dispatchers.Main + Job())
        observersStarted = false
    }

    private fun observeSession() {
        scope.launch {
            var lastSessionSignature: String? = null
            var lastPausedState: Boolean? = null

            sessionManager.activeSession.collectLatest { session ->
                val sessionSignature = session?.constraints?.joinToString { it.id } ?: ""
                val isPaused = session?.isPaused ?: true
                val hasValidConstraints = session != null && session.constraints.isNotEmpty()
                val shouldShow = if (session != null) {
                    !isPaused && hasValidConstraints
                } else {
                    state.hasActiveSession
                }

                if (shouldShow) {
                    if (indicatorView == null) {
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

                        if (sessionSignature != lastSessionSignature || isPaused != lastPausedState) {
                            render()
                        } else {
                            updateCompactVisuals()
                        }
                    }
                } else if (indicatorView != null) {
                    try {
                        windowManager?.removeView(indicatorView)
                    } catch (_: Exception) {
                    }
                    indicatorView = null
                    rootContainer = null
                    dotView = null
                    statusTextView = null
                }

                lastSessionSignature = sessionSignature
                lastPausedState = isPaused
            }
        }
    }

    private var lastProcessedEventId: String? = null

    private fun observeViolation() {
        scope.launch {
            sessionManager.sessionEvents
                .distinctUntilChanged { old, new -> oldEventId(old) == oldEventId(new) }
                .collect { event ->
                    val eventId = oldEventId(event)
                    if (eventId == lastProcessedEventId) return@collect
                    lastProcessedEventId = eventId

                    when (event) {
                        is com.seenot.app.domain.SessionEvent.ViolationDetected -> triggerViolationFeedback()
                        is com.seenot.app.domain.SessionEvent.SessionEnded,
                        is com.seenot.app.domain.SessionEvent.SessionCleared -> dismiss()
                        else -> Unit
                    }
                }
        }
    }

    private fun observeMatchStates() {
        scope.launch {
            sessionManager.constraintMatchStateFlow.collectLatest { matchStates ->
                state = state.copy(matchStates = matchStates)
                if (state.isExpanded) {
                    render()
                } else {
                    updateCompactVisuals()
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
            is com.seenot.app.domain.SessionEvent.ViolationDetected -> {
                "Violation_${event.constraint.id}_${System.currentTimeMillis() / 1000}"
            }
        }
    }

    private fun triggerViolationFeedback() {
        state = state.copy(isViolating = true)
        updateCompactVisuals()

        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager
                vibratorManager.defaultVibrator.vibrate(
                    VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                vibrator.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } catch (_: Exception) {
        }

        violationResetHandler = Handler(Looper.getMainLooper())
        violationResetHandler?.postDelayed({
            state = state.copy(isViolating = false)
            updateCompactVisuals()
        }, 1000)
    }

    private fun render() {
        val previousX = indicatorParams?.x
        val previousY = indicatorParams?.y

        indicatorView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (_: Exception) {
            }
        }

        indicatorView = buildView()
        indicatorParams = createLayoutParams().apply {
            previousX?.let { x = it }
            previousY?.let { y = it }
        }

        try {
            windowManager?.addView(indicatorView, indicatorParams)
        } catch (e: Exception) {
            Logger.e("FloatingIndicator", "Failed to show overlay", e)
        }
    }

    private fun updateCompactVisuals() {
        if (state.isExpanded) return
        statusTextView?.text = buildCompactStatusText()
        statusTextView?.setTextColor(if (state.isViolating) riskColor else subtleTextColor)
        val dotColor = when {
            state.isViolating -> riskColor
            else -> getIndicatorColor()
        }
        (dotView?.background as? GradientDrawable)?.setColor(dotColor)
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

    private fun buildView(): View {
        return if (state.isExpanded) buildExpandedView() else buildCompactView()
    }

    @SuppressLint("SetTextI18n")
    private fun buildCompactView(): View {
        val hideCompactText = RuleRecordingPrefs.isCompactHudTextHidden(context)
        rootContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            if (hideCompactText) {
                setPadding(10.dp(), 10.dp(), 10.dp(), 10.dp())
            } else {
                setPadding(8.dp(), 5.dp(), 10.dp(), 5.dp())
            }
            background = GradientDrawable().apply {
                setColor(surfaceColor)
                cornerRadius = if (hideCompactText) 999.dp().toFloat() else 16.dp().toFloat()
                setStroke(1, surfaceBorderColor)
            }
            setOnTouchListener { _, event ->
                handleCompactTouch(event)
                true
            }
        }

        dotView = View(context).apply {
            val dotSize = if (hideCompactText) 12.dp() else 10.dp()
            layoutParams = LinearLayout.LayoutParams(dotSize, dotSize).apply {
                if (!hideCompactText) {
                    marginStart = 4.dp()
                    marginEnd = 6.dp()
                }
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (this@FloatingIndicatorOverlay.state.isViolating) riskColor else getIndicatorColor())
            }
        }
        rootContainer?.addView(dotView)

        statusTextView = null
        if (!hideCompactText) {
            statusTextView = TextView(context).apply {
                text = buildCompactStatusText()
                textSize = 12f
                setTextColor(if (this@FloatingIndicatorOverlay.state.isViolating) riskColor else subtleTextColor)
                val isChineseUi = AppLocalePrefs.getLanguage(context) == AppLocalePrefs.LANG_ZH
                maxLines = if (isChineseUi) 1 else 4
                if (!isChineseUi) {
                    setLineSpacing(0f, 1.08f)
                }
            }
            rootContainer?.addView(statusTextView)
        }

        return rootContainer!!
    }

    private fun buildExpandedView(): View {
        val statuses = buildRuleTypeStatuses()

        rootContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp(), 16.dp(), 16.dp(), 14.dp())
            layoutParams = LinearLayout.LayoutParams(260.dp(), LinearLayout.LayoutParams.WRAP_CONTENT)
            background = GradientDrawable().apply {
                setColor(surfaceColor)
                cornerRadius = 18.dp().toFloat()
                setStroke(1, surfaceBorderColor)
            }
        }

        rootContainer?.addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL

                addView(
                    TextView(context).apply {
                        text = appName
                        textSize = 16f
                        setTextColor(strongTextColor)
                        typeface = Typeface.DEFAULT_BOLD
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                            marginEnd = 12.dp()
                        }
                    }
                )

                addView(
                    buildHeaderButton(context.getString(R.string.hud_btn_change_intent)) {
                        state = state.copy(isExpanded = false)
                        render()
                        onTapToReopen()
                    }.apply {
                        (layoutParams as LinearLayout.LayoutParams).marginEnd = 8.dp()
                    }
                )

                addView(
                    buildHeaderButton(context.getString(R.string.hud_btn_collapse)) {
                        state = state.copy(isExpanded = false)
                        render()
                    }
                )
            }
        )

        rootContainer?.addView(
            TextView(context).apply {
                text = buildCompactStatusText()
                textSize = 12f
                setTextColor(subtleTextColor)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 8.dp()
                    bottomMargin = 14.dp()
                }
            }
        )

        statuses.forEachIndexed { index, status ->
            rootContainer?.addView(buildStatusCard(status).apply {
                if (index > 0) {
                    (layoutParams as LinearLayout.LayoutParams).topMargin = 10.dp()
                }
            })
        }

        if (statuses.isEmpty()) {
            rootContainer?.addView(
                TextView(context).apply {
                    text = context.getString(R.string.hud_no_intent_yet)
                    textSize = 13f
                    setTextColor(subtleTextColor)
                }
            )
        }

        return rootContainer!!
    }

    private fun buildStatusCard(status: RuleTypeStatus): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12.dp(), 12.dp(), 12.dp(), 12.dp())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            background = GradientDrawable().apply {
                setColor(if (isDarkMode) Color.parseColor("#1F1F1F") else Color.parseColor("#FAFAFA"))
                cornerRadius = 14.dp().toFloat()
                setStroke(1, surfaceBorderColor)
            }

            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL

                    addView(
                        TextView(context).apply {
                            text = status.title
                            textSize = 14f
                            setTextColor(strongTextColor)
                            typeface = Typeface.DEFAULT_BOLD
                            layoutParams = LinearLayout.LayoutParams(
                                0,
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                1f
                            )
                        }
                    )

                    addView(
                        TextView(context).apply {
                            text = status.label
                            textSize = 12f
                            setTextColor(status.color)
                            typeface = Typeface.DEFAULT_BOLD
                            background = GradientDrawable().apply {
                                setColor(adjustAlpha(status.color, 0.12f))
                                cornerRadius = 999.dp().toFloat()
                            }
                            setPadding(10.dp(), 4.dp(), 10.dp(), 4.dp())
                        }
                    )
                }
            )

            addView(
                TextView(context).apply {
                    text = when (status.type) {
                        ConstraintType.DENY -> context.getString(
                            R.string.hud_judgment_deny_format,
                            if (status.isConditionMatched) context.getString(R.string.hud_judgment_deny_safe)
                            else context.getString(R.string.hud_judgment_deny_at_risk)
                        )
                        ConstraintType.TIME_CAP -> context.getString(
                            R.string.hud_judgment_deny_format,
                            if (status.isConditionMatched) context.getString(R.string.hud_judgment_time_cap_timing)
                            else context.getString(R.string.hud_judgment_time_cap_not_timing)
                        )
                        ConstraintType.NO_MONITOR -> context.getString(R.string.hud_judgment_no_monitor)
                    }
                    textSize = 12f
                    setTextColor(subtleTextColor)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = 8.dp()
                        bottomMargin = 10.dp()
                    }
                }
            )

            if (status.details.isNotEmpty()) {
                addView(
                    TextView(context).apply {
                        text = status.details.joinToString("\n")
                        textSize = 12f
                        setTextColor(strongTextColor)
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            bottomMargin = 10.dp()
                        }
                    }
                )
            }

            if (status.type != ConstraintType.NO_MONITOR) {
                addView(
                    Button(context).apply {
                        text = context.getString(R.string.hud_wrong_judgment)
                        textSize = 14f
                        setTextColor(strongTextColor)
                        typeface = Typeface.DEFAULT_BOLD
                        background = GradientDrawable().apply {
                            setColor(mutedButtonColor)
                            cornerRadius = 12.dp().toFloat()
                        }
                        setOnClickListener {
                            FalsePositiveRuleReviewOverlay.show(
                                context = context,
                                titleResId = R.string.hud_judgment_wrong_title,
                                subtitleResId = R.string.hud_false_positive_review_subtitle,
                                onGenerate = { callback ->
                                    sessionManager.previewCurrentJudgmentFalsePositiveRule(
                                        constraintType = status.type,
                                        isConditionMatched = status.isConditionMatched,
                                        onComplete = callback
                                    )
                                },
                                onSave = { ruleText, scopeType, callback ->
                                    sessionManager.saveCurrentJudgmentFalsePositiveRule(
                                        constraintType = status.type,
                                        isConditionMatched = status.isConditionMatched,
                                        confirmedRule = ruleText,
                                        scopeType = scopeType,
                                        source = "floating_overlay",
                                        onComplete = callback
                                    )
                                }
                            )
                        }
                    }
                )
            }
        }
    }

    private fun buildHeaderButton(text: String, onClick: () -> Unit): View {
        return TextView(context).apply {
            this.text = text
            textSize = 12f
            setTextColor(primaryColor)
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(adjustAlpha(primaryColor, 0.12f))
                cornerRadius = 999.dp().toFloat()
            }
            setPadding(10.dp(), 6.dp(), 10.dp(), 6.dp())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { onClick() }
        }
    }

    private fun handleCompactTouch(event: MotionEvent) {
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
                        val rawDeltaX = event.rawX.toInt() - lastX
                        val rawDeltaY = event.rawY.toInt() - lastY
                        val isEndAligned = params.gravity and Gravity.END == Gravity.END

                        params.x += if (isEndAligned) -rawDeltaX else rawDeltaX
                        params.y += rawDeltaY
                        indicatorView?.let { view ->
                            windowManager?.updateViewLayout(view, params)
                        }
                    }
                    lastX = event.rawX.toInt()
                    lastY = event.rawY.toInt()
                }
            }

            MotionEvent.ACTION_UP -> {
                if (!isDragging) {
                    if (hasRulesToShow()) {
                        state = state.copy(isExpanded = true)
                        render()
                    } else {
                        onTapToReopen()
                    }
                }
                isDragging = false
            }
        }
    }

    private fun buildCompactStatusText(): String {
        val constraints = currentConstraints()
        val isChineseUi = AppLocalePrefs.getLanguage(context) == AppLocalePrefs.LANG_ZH
        return if (constraints.isEmpty()) {
            context.getString(R.string.hud_tap_to_set_intent)
        } else {
            if (isChineseUi) {
                constraints.take(2).joinToString(" | ") { constraint ->
                    when (constraint.type) {
                        ConstraintType.DENY -> {
                            val status = if (state.matchStates[constraint.id] == true)
                                context.getString(R.string.hud_status_risk) else context.getString(R.string.hud_status_normal)
                            "${formatConstraintDetailSingleLine(constraint)}${context.getString(R.string.hud_compact_separator)}$status"
                        }
                        ConstraintType.TIME_CAP -> {
                            val status = if (state.matchStates[constraint.id] == true)
                                context.getString(R.string.hud_status_timing) else context.getString(R.string.hud_status_not_timing)
                            "${formatConstraintDetailSingleLine(constraint)}${context.getString(R.string.hud_compact_separator)}$status"
                        }
                        ConstraintType.NO_MONITOR -> {
                            "${formatConstraintDetailSingleLine(constraint)}${context.getString(R.string.hud_compact_separator)}${context.getString(R.string.hud_status_no_monitor)}"
                        }
                    }
                } + if (constraints.size > 2) context.getString(R.string.hud_more_count, constraints.size - 2) else ""
            } else {
                buildList {
                    constraints.take(2).forEach { constraint ->
                        add(formatCompactConstraintBlock(constraint))
                    }
                    if (constraints.size > 2) {
                        add(context.getString(R.string.hud_more_count, constraints.size - 2))
                    }
                }.joinToString("\n")
            }
        }
    }

    private fun formatConstraintDetailSingleLine(constraint: SessionConstraint): String {
        return when (constraint.type) {
            ConstraintType.DENY -> {
                buildString {
                    append(context.getString(R.string.hud_rule_detail_deny, constraint.description.take(10)))
                    constraint.timeLimitMs?.let { ms ->
                        val min = ms / 60000.0
                        val minText = if (min % 1.0 == 0.0) min.toInt().toString() else min.toString()
                        val scopeStr = constraint.timeScope?.let { context.getString(it.displayLabelResId()) } ?: ""
                        append(" $scopeStr$minText${context.getString(R.string.unit_minute)}")
                    }
                }
            }
            ConstraintType.TIME_CAP -> {
                val min = constraint.timeLimitMs?.let { it / 60000.0 } ?: 0.0
                val minText = if (min % 1.0 == 0.0) min.toInt().toString() else min.toString()
                val scopeStr = constraint.timeScope?.let { context.getString(it.displayLabelResId()) } ?: ""
                val desc = constraint.description.take(10)
                if (desc.isNotEmpty()) {
                    context.getString(R.string.hud_rule_detail_time_cap, desc, scopeStr, minText)
                } else {
                    context.getString(R.string.hud_rule_detail_time_cap_no_desc, scopeStr, minText)
                }
            }
            ConstraintType.NO_MONITOR -> context.getString(R.string.hud_rule_detail_no_monitor)
        }
    }

    private fun buildRuleTypeStatuses(): List<RuleTypeStatus> {
        val constraints = currentConstraints()
        if (constraints.isEmpty()) return emptyList()

        val statuses = mutableListOf<RuleTypeStatus>()

        val denyConstraints = constraints.filter { it.type == ConstraintType.DENY }
        if (denyConstraints.isNotEmpty()) {
            val isRisk = denyConstraints.any { state.matchStates[it.id] == true }
            statuses += RuleTypeStatus(
                type = ConstraintType.DENY,
                title = context.getString(R.string.constraint_type_deny),
                label = if (isRisk) context.getString(R.string.hud_status_risk) else context.getString(R.string.hud_status_normal),
                isConditionMatched = !isRisk,
                color = if (isRisk) riskColor else safeColor,
                details = denyConstraints.map { formatConstraintDetail(it) }
            )
        }

        val timeCapConstraints = constraints.filter { it.type == ConstraintType.TIME_CAP }
        if (timeCapConstraints.isNotEmpty()) {
            val isInScope = timeCapConstraints.any { state.matchStates[it.id] == true }
            statuses += RuleTypeStatus(
                type = ConstraintType.TIME_CAP,
                title = context.getString(R.string.hud_rule_type_time_cap),
                label = if (isInScope) context.getString(R.string.hud_status_timing) else context.getString(R.string.hud_status_not_timing),
                isConditionMatched = isInScope,
                color = if (isInScope) safeColor else warningColor,
                details = timeCapConstraints.map { formatConstraintDetail(it) }
            )
        }

        val noMonitorConstraints = constraints.filter { it.type == ConstraintType.NO_MONITOR }
        if (noMonitorConstraints.isNotEmpty()) {
            statuses += RuleTypeStatus(
                type = ConstraintType.NO_MONITOR,
                title = context.getString(R.string.hud_rule_type_no_monitor),
                label = context.getString(R.string.hud_status_no_monitor),
                isConditionMatched = true,
                color = subtleTextColor,
                details = noMonitorConstraints.map { formatConstraintDetail(it) }
            )
        }

        return statuses
    }

    private fun currentConstraints(): List<SessionConstraint> {
        return state.displayedConstraints ?: state.session?.constraints ?: emptyList()
    }

    private fun hasRulesToShow(): Boolean = currentConstraints().isNotEmpty()

    private fun formatConstraintDetail(constraint: SessionConstraint): String {
        return when (constraint.type) {
            ConstraintType.DENY -> {
                val title = context.getString(
                    R.string.hud_rule_detail_deny,
                    truncateCompactDescription(constraint.description)
                )
                val meta = buildCompactTimeCapMeta(constraint)
                if (meta.isBlank()) title else "$title\n$meta"
            }
            ConstraintType.TIME_CAP -> {
                val title = buildCompactTimeCapTitle(constraint)
                val meta = buildCompactTimeCapMeta(constraint)
                if (meta.isBlank()) title else "$title\n$meta"
            }
            ConstraintType.NO_MONITOR -> context.getString(R.string.hud_rule_detail_no_monitor)
        }
    }

    private fun formatCompactConstraintBlock(constraint: SessionConstraint): String {
        return when (constraint.type) {
            ConstraintType.DENY -> {
                val status = if (state.matchStates[constraint.id] == true) {
                    context.getString(R.string.hud_compact_status_risk)
                } else {
                    context.getString(R.string.hud_compact_status_normal)
                }
                val title = context.getString(
                    R.string.hud_rule_detail_deny,
                    truncateCompactDescription(constraint.description)
                )
                "$title\n$status"
            }
            ConstraintType.TIME_CAP -> {
                val status = if (state.matchStates[constraint.id] == true) {
                    context.getString(R.string.hud_compact_status_timing_now)
                } else {
                    context.getString(R.string.hud_compact_status_not_timing)
                }
                val title = buildCompactTimeCapTitle(constraint)
                val meta = buildCompactTimeCapMeta(constraint)
                val subtitle = listOf(meta, status).filter { it.isNotBlank() }.joinToString(" · ")
                "$title\n$subtitle"
            }
            ConstraintType.NO_MONITOR -> {
                "${context.getString(R.string.hud_rule_detail_no_monitor)}\n${context.getString(R.string.hud_status_no_monitor)}"
            }
        }
    }

    private fun buildCompactTimeCapTitle(constraint: SessionConstraint): String {
        val description = truncateCompactDescription(constraint.description)
        val prefix = context.getString(R.string.hud_compact_time_limit_prefix)
        return if (description.isBlank()) prefix else "$prefix $description"
    }

    private fun buildCompactTimeCapMeta(constraint: SessionConstraint): String {
        val durationText = formatDurationMinutesShort(constraint.timeLimitMs)
        val scopeText = constraint.timeScope?.let { context.getString(it.displayLabelResId()) }.orEmpty()
        return listOf(scopeText, durationText).filter { it.isNotBlank() }.joinToString(" ")
    }

    private fun formatDurationMinutesShort(timeLimitMs: Long?): String {
        if (timeLimitMs == null) return ""
        val minutes = timeLimitMs / 60000.0
        val value = if (minutes % 1.0 == 0.0) {
            minutes.toInt().toString()
        } else {
            String.format(Locale.US, "%.1f", minutes)
        }
        return context.getString(R.string.hud_duration_minutes_short, value)
    }

    private fun truncateCompactDescription(description: String, maxChars: Int = 12): String {
        val text = description.trim()
        if (text.length <= maxChars) return text
        return text.take(maxChars).trimEnd() + "…"
    }

    private fun getIndicatorColor(): Int {
        val statuses = buildRuleTypeStatuses()
        if (statuses.any { it.type == ConstraintType.DENY && !it.isConditionMatched }) {
            return riskColor
        }

        return when {
            statuses.any { it.type == ConstraintType.TIME_CAP && it.isConditionMatched } -> warningColor
            statuses.any { it.type == ConstraintType.DENY && it.isConditionMatched } -> safeColor
            else -> primaryColor
        }
    }

    fun dismiss() {
        violationResetHandler?.removeCallbacksAndMessages(null)
        indicatorView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (_: Exception) {
            }
        }
        indicatorView = null
        rootContainer = null
        dotView = null
        statusTextView = null
        JudgmentFeedbackConfirmOverlay.dismiss()
        scope.cancel()
    }

    fun isExpandedInternal(): Boolean = state.isExpanded

    fun getOverlayBounds(): android.graphics.Rect? {
        val view = indicatorView ?: return null
        val params = indicatorParams ?: return null

        val width = if (view.width > 0) view.width else params.width
        val height = if (view.height > 0) view.height else params.height
        val actualWidth = if (width > 0) {
            width
        } else if (state.isExpanded) {
            260.dp()
        } else if (RuleRecordingPrefs.isCompactHudTextHidden(context)) {
            36.dp()
        } else {
            180.dp()
        }
        val actualHeight = if (height > 0) height else if (state.isExpanded) 220.dp() else 40.dp()
        val screenWidth = context.resources.displayMetrics.widthPixels
        val left = screenWidth - params.x - actualWidth

        return android.graphics.Rect(
            left,
            params.y,
            left + actualWidth,
            params.y + actualHeight
        )
    }

    private fun adjustAlpha(color: Int, alpha: Float): Int {
        val clampedAlpha = (Color.alpha(color) * alpha).roundToInt().coerceIn(0, 255)
        return Color.argb(clampedAlpha, Color.red(color), Color.green(color), Color.blue(color))
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
            val localizedContext = AppLocalePrefs.createLocalizedContext(context)
            val overlay = FloatingIndicatorOverlay(localizedContext, appName, packageName, sessionManager, onTapToReopen)
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
            val localizedContext = AppLocalePrefs.createLocalizedContext(context)
            val overlay = FloatingIndicatorOverlay(localizedContext, appName, packageName, sessionManager, onTapToReopen)
            overlay.showWithConstraints(constraints)
            currentOverlay = overlay
        }

        fun dismiss() {
            currentOverlay?.dismiss()
            currentOverlay = null
        }

        fun isShowing(): Boolean = currentOverlay?.indicatorView != null

        fun isExpanded(): Boolean = currentOverlay?.isExpandedInternal() == true

        fun getCurrentOverlayBounds(): android.graphics.Rect? {
            return currentOverlay?.getOverlayBounds()
        }

        fun refreshCurrentOverlay() {
            currentOverlay?.render()
        }
    }
}
