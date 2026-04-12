package com.seenot.app.ui.overlay

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import com.seenot.app.utils.Logger
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.seenot.app.ui.overlay.ToastOverlay
import androidx.core.content.ContextCompat
import com.seenot.app.config.AiProvider
import com.seenot.app.config.ApiConfig
import com.seenot.app.ai.voice.VoiceInputManager
import com.seenot.app.ai.voice.VoiceRecordingState
import com.seenot.app.data.model.ConstraintType
import com.seenot.app.data.model.displayLabel
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
 * Full-screen dialog overlay shown when user enters a controlled app.
 * Prompts user to declare intent via voice, or pick from history.
 * After confirmation, transitions to the compact FloatingIndicatorOverlay.
 */
class IntentInputDialogOverlay(
    private val context: Context,
    private val appName: String,
    private val packageName: String,
    private val sessionManager: SessionManager,
    private val onIntentConfirmed: (List<SessionConstraint>) -> Unit,
    private val onDismissed: () -> Unit
) {
    companion object {
        private const val TAG = "IntentInputDialog"
        @Volatile
        private var currentDialog: IntentInputDialogOverlay? = null

        fun show(
            context: Context,
            appName: String,
            packageName: String,
            sessionManager: SessionManager,
            onIntentConfirmed: (List<SessionConstraint>) -> Unit,
            onDismissed: () -> Unit,
            allowDefaultRuleAutoApply: Boolean = true
        ) {
            dismiss()
            val dialog = IntentInputDialogOverlay(
                context, appName, packageName, sessionManager, onIntentConfirmed, onDismissed
            )
            dialog.show(allowDefaultRuleAutoApply)
            currentDialog = dialog
        }

        fun dismiss() {
            currentDialog?.dismissInternal()
            currentDialog = null
        }

        fun isShowing(): Boolean = currentDialog?.rootView != null
    }

    private enum class Mode { IDLE, RECORDING, PROCESSING, SHOWING_RULES }
    private enum class InputSource { NONE, VOICE, TEXT }

    private var windowManager: WindowManager? = null
    private var voiceInputManager: VoiceInputManager? = null
    private var rootView: View? = null
    private var mode = Mode.IDLE
    private var pendingConstraints: List<SessionConstraint>? = null
    private var pendingInputSource = InputSource.NONE
    private var hasAudioPermission = false
    private var isVoiceInputAvailable = false

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private val density = context.resources.displayMetrics.density

    // Theme colors
    private val isDarkMode: Boolean
        get() = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES

    private val primaryColor get() = if (isDarkMode) Color.parseColor("#90CAF9") else Color.parseColor("#2196F3")
    private val recordingColor get() = if (isDarkMode) Color.parseColor("#EF9A9A") else Color.parseColor("#F44336")
    private val processingColor get() = if (isDarkMode) Color.parseColor("#FFCC80") else Color.parseColor("#FF9800")
    private val successColor get() = if (isDarkMode) Color.parseColor("#A5D6A7") else Color.parseColor("#4CAF50")
    private val surfaceColor get() = if (isDarkMode) Color.parseColor("#2D2D2D") else Color.WHITE
    private val dimColor get() = Color.parseColor("#80000000")
    private val cardBorderColor get() = if (isDarkMode) Color.parseColor("#424242") else Color.parseColor("#E0E0E0")
    private val textColor get() = if (isDarkMode) Color.parseColor("#E0E0E0") else Color.parseColor("#333333")
    private val subtleTextColor get() = if (isDarkMode) Color.parseColor("#9E9E9E") else Color.parseColor("#666666")
    private val historyBgColor get() = if (isDarkMode) Color.parseColor("#383838") else Color.parseColor("#F5F5F5")

    // UI refs
    private var micButton: FrameLayout? = null
    private var micIcon: ImageView? = null
    private var micBg: View? = null
    private var statusText: TextView? = null
    private var presetContainer: LinearLayout? = null
    private var historyContainer: LinearLayout? = null
    private var confirmButton: LinearLayout? = null
    private var confirmText: TextView? = null
    private var retryVoiceButton: TextView? = null
    private var rulesPreviewText: TextView? = null
    private var textInput: EditText? = null
    private var presetRules: List<SessionConstraint> = emptyList()

    @SuppressLint("ClickableViewAccessibility")
    fun show(allowDefaultRuleAutoApply: Boolean = true) {
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        hasAudioPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        isVoiceInputAvailable = hasAudioPermission && hasUsableVoiceConfig()

        if (allowDefaultRuleAutoApply) {
            val defaultRule = sessionManager.getDefaultRule(packageName)
            if (defaultRule != null) {
                pendingConstraints = listOf(defaultRule)
                confirmAndTransition()
                return
            }
        }

        voiceInputManager = VoiceInputManager(context)
        observeVoiceInput()
        renderDialog()
    }

    private fun observeVoiceInput() {
        val manager = voiceInputManager ?: return

        scope.launch {
            manager.recordingState.collectLatest { state ->
                when (state) {
                    VoiceRecordingState.RECORDING -> {
                        mode = Mode.RECORDING
                        updateUI()
                    }
                    VoiceRecordingState.PROCESSING -> {
                        mode = Mode.PROCESSING
                        updateUI()
                    }
                    VoiceRecordingState.PARSED -> {
                        val parsed = manager.parsedIntent.value
                        if (parsed != null && parsed.constraints.isNotEmpty()) {
                            pendingConstraints = parsed.constraints
                            if (pendingInputSource == InputSource.TEXT) {
                                confirmAndTransition()
                                return@collectLatest
                            }
                            mode = Mode.SHOWING_RULES
                            updateUI()
                        } else {
                            ToastOverlay.show(context, "未能识别意图")
                            mode = Mode.IDLE
                            updateUI()
                        }
                    }
                    VoiceRecordingState.ERROR -> {
                        ToastOverlay.show(context, manager.error.value ?: "出错")
                        mode = Mode.IDLE
                        updateUI()
                    }
                    VoiceRecordingState.IDLE -> {
                        if (mode == Mode.RECORDING) {
                            mode = Mode.IDLE
                            updateUI()
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    private fun renderDialog() {
        val screenWidth = context.resources.displayMetrics.widthPixels
        val dialogWidth = (screenWidth * 0.85).toInt()

        // Dim background (full screen overlay)
        val dimBg = FrameLayout(context).apply {
            setBackgroundColor(dimColor)
            setOnClickListener {
                dismiss()
                onDismissed()
            }
        }

        // Card
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(24.dp(), 28.dp(), 24.dp(), 20.dp())
            layoutParams = FrameLayout.LayoutParams(
                dialogWidth,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
            }
            background = GradientDrawable().apply {
                setColor(surfaceColor)
                cornerRadius = 20.dp().toFloat()
                setStroke(1, cardBorderColor)
            }
            elevation = 8.dp().toFloat()
            setOnClickListener { }
        }

        // Title - app name
        val titleText = TextView(context).apply {
            text = appName
            textSize = 20f
            setTextColor(textColor)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 4.dp() }
        }
        card.addView(titleText)

        // Subtitle
        val subtitle = TextView(context).apply {
            text = if (hasAudioPermission) {
                "可以说话，也可以直接键盘输入"
            } else {
                "直接键盘输入声明意图"
            }
            textSize = 14f
            setTextColor(subtleTextColor)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 24.dp() }
        }
        card.addView(subtitle)

        if (hasAudioPermission) {
            // Mic button container
            val micSize = 72.dp()
            micButton = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(micSize, micSize).apply {
                    bottomMargin = 12.dp()
                }
            }

            micBg = View(context).apply {
                layoutParams = FrameLayout.LayoutParams(micSize, micSize)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(primaryColor)
                }
            }
            micButton?.addView(micBg)

            micIcon = ImageView(context).apply {
                setImageResource(android.R.drawable.ic_btn_speak_now)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                layoutParams = FrameLayout.LayoutParams(micSize, micSize).apply {
                    gravity = Gravity.CENTER
                }
                setColorFilter(Color.WHITE)
            }
            micButton?.addView(micIcon)

            micButton?.setOnClickListener { handleMicClick() }
            card.addView(micButton)
        }

        // Status text
        statusText = TextView(context).apply {
            text = if (hasAudioPermission) "点击开始录音" else "未开启麦克风权限，可直接键盘输入"
            textSize = 13f
            setTextColor(subtleTextColor)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 8.dp() }
        }
        card.addView(statusText)

        textInput = EditText(context).apply {
            hint = "输入意图后按回车直接生效"
            setPadding(16.dp(), 14.dp(), 16.dp(), 14.dp())
            background = GradientDrawable().apply {
                cornerRadius = 14.dp().toFloat()
                setColor(historyBgColor)
                setStroke(1, cardBorderColor)
            }
            textSize = 14f
            setTextColor(textColor)
            setHintTextColor(subtleTextColor)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_DONE
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 12.dp() }
            setOnEditorActionListener { _, actionId, event ->
                val isSubmit = actionId == EditorInfo.IME_ACTION_DONE ||
                    actionId == EditorInfo.IME_ACTION_GO ||
                    (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
                if (isSubmit) {
                    submitTextIntent()
                    true
                } else {
                    false
                }
            }
        }
        card.addView(textInput)

        // Rules preview (hidden until parsed)
        rulesPreviewText = TextView(context).apply {
            textSize = 14f
            setTextColor(successColor)
            gravity = Gravity.CENTER
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 4.dp()
                bottomMargin = 8.dp()
            }
        }
        card.addView(rulesPreviewText)

        // Confirm button (hidden until rules are parsed)
        confirmButton = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(20.dp(), 10.dp(), 20.dp(), 10.dp())
            visibility = View.GONE
            background = GradientDrawable().apply {
                setColor(successColor)
                cornerRadius = 12.dp().toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 16.dp() }
            setOnClickListener { confirmAndTransition() }
        }
        confirmText = TextView(context).apply {
            text = "确认意图"
            textSize = 15f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }
        confirmButton?.addView(confirmText)
        card.addView(confirmButton)

        retryVoiceButton = TextView(context).apply {
            text = "再说一次"
            textSize = 14f
            setTextColor(primaryColor)
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(16.dp(), 0, 16.dp(), 12.dp())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { restartVoiceInput() }
        }
        card.addView(retryVoiceButton)

        // Divider
        val divider = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).apply {
                topMargin = 8.dp()
                bottomMargin = 12.dp()
            }
            setBackgroundColor(cardBorderColor)
        }
        card.addView(divider)

        // Preset rules section title
        val presetTitle = TextView(context).apply {
            text = "预设意图"
            textSize = 13f
            setTextColor(subtleTextColor)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 8.dp() }
        }
        card.addView(presetTitle)

        // Preset rules list (scrollable)
        val presetScrollView = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            isVerticalScrollBarEnabled = false
        }

        presetContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        presetScrollView.addView(presetContainer)
        card.addView(presetScrollView)

        // Divider between preset and history
        val divider2 = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).apply {
                topMargin = 12.dp()
                bottomMargin = 12.dp()
            }
            setBackgroundColor(cardBorderColor)
        }
        card.addView(divider2)

        // History section title
        val historyTitle = TextView(context).apply {
            text = "历史意图"
            textSize = 13f
            setTextColor(subtleTextColor)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 8.dp() }
        }
        card.addView(historyTitle)

        // History list (scrollable)
        val scrollView = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                // Max height for the history list
            }
            isVerticalScrollBarEnabled = false
        }
        // Limit the scroll view height
        scrollView.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            // We'll set a max-height later via measure tricks, or just add a few items
        }

        historyContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        scrollView.addView(historyContainer)
        card.addView(scrollView)

        // Populate preset and history
        populatePresets()
        populateHistory()

        // Skip button at bottom
        val skipButton = TextView(context).apply {
            text = "跳过"
            textSize = 14f
            setTextColor(subtleTextColor)
            gravity = Gravity.CENTER
            setPadding(16.dp(), 12.dp(), 16.dp(), 4.dp())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8.dp() }
            setOnClickListener {
                dismiss()
                onDismissed()
            }
        }
        card.addView(skipButton)

        dimBg.addView(card)
        rootView = dimBg

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        )

        try {
            windowManager?.addView(rootView, params)
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to show dialog overlay", e)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun populatePresets() {
        presetContainer?.removeAllViews()

        presetRules = sessionManager.loadPresetRules(packageName)
            .sortedByDescending { it.isDefault }

        if (presetRules.isEmpty()) {
            val emptyText = TextView(context).apply {
                text = "暂无预设意图"
                textSize = 13f
                setTextColor(subtleTextColor)
                gravity = Gravity.CENTER
                setPadding(0, 12.dp(), 0, 12.dp())
            }
            presetContainer?.addView(emptyText)
            return
        }

        for (constraints in presetRules) {
            val row = buildPresetRow(constraints)
            presetContainer?.addView(row)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun buildPresetRow(constraints: SessionConstraint): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(14.dp(), 10.dp(), 14.dp(), 10.dp())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 6.dp() }
            background = GradientDrawable().apply {
                setColor(if (constraints.isDefault) primaryColor and 0x20FFFFFF else historyBgColor)
                cornerRadius = 10.dp().toFloat()
            }
            setOnClickListener {
                selectPresetIntent(constraints)
            }
        }

        if (constraints.isDefault) {
            val starIcon = TextView(context).apply {
                text = "⭐"
                textSize = 16f
                setPadding(0, 0, 8.dp(), 0)
            }
            row.addView(starIcon)
        }

        val textContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val constraintText = when (constraints.type) {
            ConstraintType.DENY -> {
                buildString {
                    append("禁止 ${constraints.description.take(10)}")
                    constraints.timeLimitMs?.let { ms ->
                        val min = ms / 60000.0
                        val minText = if (min % 1.0 == 0.0) min.toInt().toString() else min.toString()
                        val scopeStr = constraints.timeScope?.displayLabel ?: ""
                        append(" ${scopeStr}${minText}分")
                    }
                }
            }
            ConstraintType.TIME_CAP -> {
                val min = constraints.timeLimitMs?.let { it / 60000.0 } ?: 0.0
                val minText = if (min % 1.0 == 0.0) min.toInt().toString() else min.toString()
                val scopeStr = constraints.timeScope?.displayLabel ?: ""
                val desc = constraints.description.take(10)
                if (desc.isNotEmpty()) "限时 $desc ${scopeStr}${minText}分" else "限时 ${scopeStr}${minText}分"
            }
        }
        val descText = TextView(context).apply {
            text = constraintText
            textSize = 14f
            setTextColor(textColor)
            maxLines = 2
        }
        textContainer.addView(descText)
        
        row.addView(textContainer)

        return row
    }

    private fun selectPresetIntent(constraints: SessionConstraint) {
        pendingConstraints = listOf(constraints)
        confirmAndTransition()
    }

    private fun populateHistory() {
        historyContainer?.removeAllViews()

        // Load preset rules first to filter out duplicates
        val loadedPresetRules = sessionManager.loadPresetRules(packageName)
        val presetFingerprints = loadedPresetRules.map { sessionManager.getConstraintFingerprint(listOf(it)) }.toSet()

        val history = sessionManager.loadIntentHistory(packageName).filter { historyEntry ->
            val fingerprint = sessionManager.getConstraintFingerprint(historyEntry)
            fingerprint !in presetFingerprints
        }

        if (history.isEmpty()) {
            val emptyText = TextView(context).apply {
                text = "暂无历史意图"
                textSize = 13f
                setTextColor(subtleTextColor)
                gravity = Gravity.CENTER
                setPadding(0, 12.dp(), 0, 12.dp())
            }
            historyContainer?.addView(emptyText)
            return
        }

        for ((index, constraints) in history.withIndex()) {
            val row = buildHistoryRow(constraints, isLatest = index == 0)
            historyContainer?.addView(row)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun buildHistoryRow(constraints: List<SessionConstraint>, isLatest: Boolean): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14.dp(), 10.dp(), 14.dp(), 10.dp())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 6.dp() }
            background = GradientDrawable().apply {
                setColor(historyBgColor)
                cornerRadius = 10.dp().toFloat()
                if (isLatest) setStroke(1, primaryColor)
            }
            setOnClickListener {
                selectHistoryIntent(constraints)
            }
        }

        if (isLatest) {
            val tag = TextView(context).apply {
                text = "上次使用"
                textSize = 11f
                setTextColor(primaryColor)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 2.dp() }
            }
            row.addView(tag)
        }

        val constraintText = constraints.joinToString(" / ") { c ->
            when (c.type) {
                ConstraintType.DENY -> {
                    buildString {
                        append("禁止 ${c.description.take(10)}")
                        c.timeLimitMs?.let { ms ->
                            val min = ms / 60000.0
                            val minText = if (min % 1.0 == 0.0) min.toInt().toString() else min.toString()
                            val scopeStr = c.timeScope?.displayLabel ?: ""
                            append(" ${scopeStr}${minText}分")
                        }
                    }
                }
                ConstraintType.TIME_CAP -> {
                    val min = c.timeLimitMs?.let { it / 60000.0 } ?: 0.0
                    val minText = if (min % 1.0 == 0.0) min.toInt().toString() else min.toString()
                    val scopeStr = c.timeScope?.displayLabel ?: ""
                    val desc = c.description.take(10)
                    if (desc.isNotEmpty()) "限时 $desc ${scopeStr}${minText}分" else "限时 ${scopeStr}${minText}分"
                }
            }
        }
        val descText = TextView(context).apply {
            text = constraintText
            textSize = 14f
            setTextColor(textColor)
            maxLines = 2
        }
        row.addView(descText)

        return row
    }

    private fun selectHistoryIntent(constraints: List<SessionConstraint>) {
        pendingConstraints = constraints
        pendingInputSource = InputSource.NONE
        confirmAndTransition()
    }

    private fun handleMicClick() {
        if (!isVoiceInputAvailable) return

        when (mode) {
            Mode.IDLE -> {
                pendingInputSource = InputSource.VOICE
                voiceInputManager?.setCurrentApp(packageName, appName)
                voiceInputManager?.startRecording()
            }
            Mode.RECORDING -> {
                voiceInputManager?.stopRecording()
            }
            Mode.PROCESSING -> { /* ignore */ }
            Mode.SHOWING_RULES -> {
                restartVoiceInput()
            }
        }
    }

    private fun restartVoiceInput() {
        if (!isVoiceInputAvailable) return
        pendingConstraints = null
        pendingInputSource = InputSource.VOICE
        textInput?.setText("")
        voiceInputManager?.cancelRecording()
        mode = Mode.IDLE
        updateUI()
        voiceInputManager?.setCurrentApp(packageName, appName)
        voiceInputManager?.startRecording()
    }

    private fun submitTextIntent() {
        val text = textInput?.text?.toString()?.trim().orEmpty()
        if (text.isBlank()) return

        pendingConstraints = null
        pendingInputSource = InputSource.TEXT
        voiceInputManager?.setCurrentApp(packageName, appName)
        voiceInputManager?.parseTextInput(text, packageName, appName)
    }

    @SuppressLint("SetTextI18n")
    private fun updateUI() {
        when (mode) {
            Mode.IDLE -> {
                micBg?.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(primaryColor)
                }
                statusText?.text = when {
                    isVoiceInputAvailable -> "点击麦克风开始录音，或直接键盘输入"
                    !hasAudioPermission -> "未开启麦克风权限，可直接键盘输入"
                    else -> "未配置可用语音输入，可直接键盘输入"
                }
                statusText?.setTextColor(subtleTextColor)
                rulesPreviewText?.visibility = View.GONE
                confirmButton?.visibility = View.GONE
                retryVoiceButton?.visibility = View.GONE
                textInput?.isEnabled = true
            }
            Mode.RECORDING -> {
                micBg?.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(recordingColor)
                }
                statusText?.text = "正在录音... 再次点击停止"
                statusText?.setTextColor(recordingColor)
                rulesPreviewText?.visibility = View.GONE
                confirmButton?.visibility = View.GONE
                retryVoiceButton?.visibility = View.GONE
                textInput?.isEnabled = false
            }
            Mode.PROCESSING -> {
                micBg?.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(processingColor)
                }
                statusText?.text = "正在解析..."
                statusText?.setTextColor(processingColor)
                rulesPreviewText?.visibility = View.GONE
                confirmButton?.visibility = View.GONE
                retryVoiceButton?.visibility = View.GONE
                textInput?.isEnabled = false
            }
            Mode.SHOWING_RULES -> {
                micBg?.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(successColor)
                }
                statusText?.text = if (pendingInputSource == InputSource.VOICE) {
                    "意图已识别，请手动确认；不对的话可以再说一次"
                } else {
                    "意图已识别"
                }
                statusText?.setTextColor(successColor)

                val rulesText = pendingConstraints?.joinToString("\n") { c ->
                    when (c.type) {
                        ConstraintType.DENY -> "禁止: ${c.description}"
                        ConstraintType.TIME_CAP -> {
                            val min = c.timeLimitMs?.let { it / 60000.0 } ?: 0.0
                            val minText = if (min % 1.0 == 0.0) min.toInt().toString() else min.toString()
                            val desc = c.description
                            if (desc.isNotEmpty()) "限时: $desc ${minText}分钟" else "限时: ${minText}分钟"
                        }
                    }
                } ?: ""
                rulesPreviewText?.text = rulesText
                rulesPreviewText?.visibility = View.VISIBLE
                confirmButton?.visibility = if (pendingInputSource == InputSource.VOICE) View.VISIBLE else View.GONE
                retryVoiceButton?.visibility =
                    if (pendingInputSource == InputSource.VOICE && isVoiceInputAvailable) View.VISIBLE else View.GONE
                textInput?.isEnabled = true
            }
        }
        micButton?.isEnabled = isVoiceInputAvailable
        micButton?.alpha = if (isVoiceInputAvailable) 1f else 0.45f
    }

    private fun confirmAndTransition() {
        val constraints = pendingConstraints ?: return
        sessionManager.saveLastIntent(packageName, constraints)
        pendingInputSource = InputSource.NONE
        dismiss()
        onIntentConfirmed(constraints)
    }

    private fun hasUsableVoiceConfig(): Boolean {
        val settings = ApiConfig.getSttSettings()
        val providerSupported = when (settings.provider) {
            AiProvider.DASHSCOPE,
            AiProvider.OPENAI,
            AiProvider.GLM,
            AiProvider.CUSTOM -> true
            AiProvider.GEMINI,
            AiProvider.ANTHROPIC -> false
        }
        if (!providerSupported) return false
        if (settings.apiKey.isBlank() || settings.model.isBlank()) return false
        return settings.provider == AiProvider.DASHSCOPE || settings.baseUrl.isNotBlank()
    }

    private fun dismissInternal() {
        voiceInputManager?.cancelRecording()
        voiceInputManager?.release()
        voiceInputManager = null
        scope.cancel()
        rootView?.let { view ->
            try { windowManager?.removeView(view) } catch (e: Exception) { /* ignore */ }
        }
        rootView = null
    }

    private fun Int.dp() = (this * density).roundToInt()
}
