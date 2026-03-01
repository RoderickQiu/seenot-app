package com.seenot.app.ui.overlay

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.seenot.app.ai.voice.VoiceInputManager
import com.seenot.app.ai.voice.VoiceRecordingState
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
 * Floating indicator overlay - 统一状态管理版本
 *
 * 状态流:
 * - 无会话: 显示"点击说话"
 * - 有会话: 显示规则内容 + 剩余时间
 * - 录音中: 显示"正在录音..."
 * - 处理中: 显示"处理中..."
 * - 展示规则: 显示解析出的规则，等待确认
 */
class FloatingIndicatorOverlay(
    private val context: Context,
    private val appName: String,
    private val packageName: String,
    private val sessionManager: SessionManager,
    private val onIntentConfirmedCallback: (List<SessionConstraint>) -> Unit
) {
    // ===== 统一状态模型 =====
    private data class OverlayState(
        val mode: Mode = Mode.IDLE,
        val session: ActiveSession? = null,
        val hasActiveSession: Boolean = false, // 立即确认，不等回调
        val recognizedText: String? = null,
        val pendingConstraints: List<SessionConstraint>? = null,
        val displayedConstraints: List<SessionConstraint>? = null, // 已确认的规则，用于显示
        val isViolating: Boolean = false
    ) {
        enum class Mode {
            IDLE,           // 无会话，等待用户点击
            RECORDING,       // 录音中
            PROCESSING,      // 处理中
            SHOWING_RULES,   // 展示规则，等待确认
        }
    }

    private var state = OverlayState()

    // ===== 依赖 =====
    private var windowManager: WindowManager? = null
    private var voiceInputManager: VoiceInputManager? = null
    private var indicatorView: View? = null
    private var indicatorParams: WindowManager.LayoutParams? = null

    // ===== UI 元素引用 =====
    private var rootContainer: LinearLayout? = null
    private var iconView: ImageView? = null
    private var statusTextView: TextView? = null

    // ===== 内部状态 =====
    private var lastX = 0
    private var lastY = 0
    private var isDragging = false
    private var timeCountdownHandler: Handler? = null
    private var violationResetHandler: Handler? = null
    private val SILENCE_THRESHOLD_MS = 2000L

    // ===== 主题颜色 =====
    private val isDarkMode: Boolean
        get() = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES

    private val primaryColor get() = if (isDarkMode) Color.parseColor("#90CAF9") else Color.parseColor("#2196F3")
    private val recordingColor get() = if (isDarkMode) Color.parseColor("#EF9A9A") else Color.parseColor("#F44336")
    private val successColor get() = if (isDarkMode) Color.parseColor("#A5D6A7") else Color.parseColor("#4CAF50")
    private val processingColor get() = if (isDarkMode) Color.parseColor("#FFCC80") else Color.parseColor("#FF9800")
    private val surfaceColor get() = if (isDarkMode) Color.parseColor("#2D2D2D") else Color.WHITE
    private val surfaceBorderColor get() = if (isDarkMode) Color.parseColor("#424242") else Color.parseColor("#E0E0E0")
    private val textColor get() = if (isDarkMode) Color.parseColor("#E0E0E0") else Color.parseColor("#333333")
    private val subtleTextColor get() = if (isDarkMode) Color.parseColor("#9E9E9E") else Color.parseColor("#666666")
    private val timeGreenColor get() = if (isDarkMode) Color.parseColor("#A5D6A7") else Color.parseColor("#4CAF50")
    private val timeYellowColor get() = if (isDarkMode) Color.parseColor("#FFE082") else Color.parseColor("#FFC107")
    private val timeRedColor get() = if (isDarkMode) Color.parseColor("#EF9A9A") else Color.parseColor("#F44336")

    private val density = context.resources.displayMetrics.density
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    // ===== 入口 =====
    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    fun show() {
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // 检查权限
        val hasAudioPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasAudioPermission) {
            Toast.makeText(context, "需要麦克风权限才能使用语音输入", Toast.LENGTH_SHORT).show()
            return
        }

        voiceInputManager = VoiceInputManager(context)

        // 监听会话状态
        observeSession()

        // 监听语音输入状态
        observeVoiceInput()

        // 监听违规事件
        observeViolation()

        // 初始渲染
        render()
    }

    // ===== 状态观察 =====
    private fun observeSession() {
        scope.launch {
            sessionManager.activeSession.collectLatest { session ->
                updateState {
                    copy(
                        session = session,
                        hasActiveSession = session != null && session.constraints.isNotEmpty(),
                        displayedConstraints = if (session != null && session.constraints.isNotEmpty()) {
                            // 会话创建后，用session的规则覆盖显示
                            session.constraints
                        } else {
                            // 会话结束，清除显示
                            null
                        }
                    )
                }
            }
        }
    }

    private fun observeVoiceInput() {
        val manager = voiceInputManager ?: return

        scope.launch {
            manager.recordingState.collectLatest { recordingState ->
                when (recordingState) {
                    VoiceRecordingState.RECORDING -> {
                        updateState { copy(mode = OverlayState.Mode.RECORDING) }
                    }
                    VoiceRecordingState.PROCESSING -> {
                        updateState { copy(mode = OverlayState.Mode.PROCESSING) }
                    }
                    VoiceRecordingState.PARSED -> {
                        val parsed = manager.parsedIntent.value
                        if (parsed != null && parsed.constraints.isNotEmpty()) {
                            updateState {
                                copy(
                                    mode = OverlayState.Mode.SHOWING_RULES,
                                    pendingConstraints = parsed.constraints
                                )
                            }
                            // 立即确认规则
                            confirmConstraints()
                        } else {
                            Toast.makeText(context, "未能识别意图", Toast.LENGTH_SHORT).show()
                            updateState { copy(mode = OverlayState.Mode.IDLE) }
                        }
                    }
                    VoiceRecordingState.ERROR -> {
                        Toast.makeText(context, manager.error.value ?: "出错", Toast.LENGTH_SHORT).show()
                        updateState { copy(mode = OverlayState.Mode.IDLE) }
                    }
                    VoiceRecordingState.IDLE -> {
                        updateState { copy(mode = OverlayState.Mode.IDLE) }
                    }
                    else -> {}
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

    // ===== 统一状态更新 =====
    private fun updateState(update: OverlayState.() -> OverlayState) {
        state = state.update()
        render()
    }

    // ===== 触发违规反馈 =====
    private fun triggerViolationFeedback() {
        updateState { copy(isViolating = true) }

        // 震动
        try {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } catch (e: Exception) { /* ignore */ }

        // 1秒后恢复
        violationResetHandler = Handler(Looper.getMainLooper())
        violationResetHandler?.postDelayed({
            updateState { copy(isViolating = false) }
        }, 1000)
    }

    // ===== 统一渲染 =====
    private fun render() {
        // 移除旧视图
        indicatorView?.let { view ->
            try { windowManager?.removeView(view) } catch (e: Exception) { /* ignore */ }
        }

        // 构建新视图
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

    // ===== 构建视图 =====
    @SuppressLint("SetTextI18n")
    private fun buildView(): View {
        val session = state.session
        // 优先用 hasActiveSession（立即确认），否则检查 session
        val isSessionActive = state.hasActiveSession || (session != null && session.constraints.isNotEmpty())

        // 根容器
        rootContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(10.dp(), 6.dp(), 10.dp(), 6.dp())
        }

        // 背景
        rootContainer?.background = GradientDrawable().apply {
            setColor(surfaceColor)
            cornerRadius = 20.dp().toFloat()
            setStroke(1, surfaceBorderColor)
        }

        // 图标
        val iconSize = 32.dp()
        val iconBgColor = when {
            state.isViolating -> timeRedColor
            state.mode == OverlayState.Mode.RECORDING -> recordingColor
            state.mode == OverlayState.Mode.PROCESSING -> processingColor
            isSessionActive -> getTimeColor(session, state.hasActiveSession)
            else -> primaryColor
        }

        iconView = ImageView(context).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                marginStart = 4.dp()
                marginEnd = 4.dp()
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(iconBgColor)
            }
            scaleType = ImageView.ScaleType.CENTER
        }
        rootContainer?.addView(iconView)

        // 文本内容
        val displayText = when (state.mode) {
            OverlayState.Mode.RECORDING -> "正在录音..."
            OverlayState.Mode.PROCESSING -> "处理中..."
            OverlayState.Mode.SHOWING_RULES -> formatConstraints(state.pendingConstraints)
            OverlayState.Mode.IDLE -> {
                if (isSessionActive) {
                    // 有会话显示规则内容（优先用已保存的显示规则）
                    formatConstraints(state.displayedConstraints ?: session?.constraints)
                } else {
                    "点击说话"
                }
            }
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
                marginEnd = 4.dp()
            }
        }
        rootContainer?.addView(statusTextView)

        // 触摸事件
        rootContainer?.setOnTouchListener { _, event ->
            handleTouch(event)
            true
        }

        return rootContainer!!
    }

    // ===== 触摸处理 =====
    private fun handleTouch(event: MotionEvent) {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isDragging = false
                lastX = event.rawX.toInt()
                lastY = event.rawY.toInt()
                true
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
                true
            }
            MotionEvent.ACTION_UP -> {
                if (!isDragging) {
                    handleClick()
                }
                isDragging = false
                true
            }
            else -> false
        }
    }

    // ===== 点击处理 =====
    private fun handleClick() {
        when (state.mode) {
            OverlayState.Mode.RECORDING -> {
                // 停止录音
                voiceInputManager?.stopRecording()
                // 启动2秒静默检测
                startSilenceDetection()
            }
            OverlayState.Mode.SHOWING_RULES -> {
                // 立即确认规则
                confirmConstraints()
            }
            OverlayState.Mode.PROCESSING -> {
                // 忽略
            }
            OverlayState.Mode.IDLE -> {
                val currentSession = state.session
                if (currentSession != null && currentSession.constraints.isNotEmpty()) {
                    // TODO: 展开显示更多操作（暂停、结束）
                } else {
                    // 开始录音
                    voiceInputManager?.setCurrentApp(packageName, appName)
                    voiceInputManager?.startRecording()
                }
            }
        }
    }

    // ===== 辅助方法 =====
    private fun formatConstraints(constraints: List<SessionConstraint>?): String {
        if (constraints.isNullOrEmpty()) return "点击说话"

        val constraint = constraints.first()

        // ALLOW/DENY: 优先显示内容
        // TIME_CAP: 优先显示时间
        return when (constraint.type) {
            ConstraintType.ALLOW -> {
                // 允许看微信
                buildString {
                    append("允许")
                    if (constraint.description.isNotEmpty()) {
                        append(" ")
                        append(constraint.description.take(8))
                    }
                }
            }
            ConstraintType.DENY -> {
                // 禁止看朋友圈
                buildString {
                    append("禁止")
                    if (constraint.description.isNotEmpty()) {
                        append(" ")
                        append(constraint.description.take(8))
                    }
                }
            }
            ConstraintType.TIME_CAP -> {
                // 限时 3分
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
        // 如果会话已激活但 session 还未更新（异步创建中），保持绿色
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

    private fun confirmConstraints() {
        val constraints = state.pendingConstraints ?: return
        sessionManager.saveLastIntent(packageName, constraints)

        // 立即更新状态：显示规则内容（不等session创建）
        updateState {
            copy(
                pendingConstraints = null,
                mode = OverlayState.Mode.IDLE,
                hasActiveSession = true,
                displayedConstraints = constraints
            )
        }

        // 创建真正的会话
        onIntentConfirmedCallback(constraints)
    }

    private fun startSilenceDetection() {
        // 暂时不需要，录音会自动停止
    }

    // ===== 销毁 =====
    fun dismiss() {
        timeCountdownHandler?.removeCallbacksAndMessages(null)
        violationResetHandler?.removeCallbacksAndMessages(null)
        voiceInputManager?.cancelRecording()
        voiceInputManager?.release()
        voiceInputManager = null
        scope.cancel()
        indicatorView?.let { view ->
            try { windowManager?.removeView(view) } catch (e: Exception) { /* ignore */ }
        }
        indicatorView = null
    }

    // ===== 工具 =====
    private fun Int.dp() = (this * density).roundToInt()

    companion object {
        private var currentOverlay: FloatingIndicatorOverlay? = null

        fun show(
            context: Context,
            appName: String,
            packageName: String,
            sessionManager: SessionManager,
            onIntentConfirmed: (List<SessionConstraint>) -> Unit
        ) {
            dismiss()
            val overlay = FloatingIndicatorOverlay(context, appName, packageName, sessionManager, onIntentConfirmed)
            overlay.show()
            currentOverlay = overlay
        }

        fun dismiss() {
            currentOverlay?.dismiss()
            currentOverlay = null
        }
    }
}
