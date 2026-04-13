package com.seenot.app.ui.overlay

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.seenot.app.R
import com.seenot.app.ai.voice.ParsedIntentState
import com.seenot.app.ai.voice.VoiceInputManager
import com.seenot.app.ai.voice.VoiceRecordingState
import com.seenot.app.domain.SessionConstraint
import com.seenot.app.domain.SessionManager
import com.seenot.app.ui.overlay.ToastOverlay
import java.lang.ref.WeakReference
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * VoiceInputOverlay - Complete voice input flow with STT and LLM
 * Features:
 * - Voice recording → STT → Intent Parser → Create Session
 * - Text input → Intent Parser → Create Session
 * - Continue last intent
 * - Draggable
 */
class VoiceInputOverlay(
    private val context: Context,
    private val appName: String,
    private val packageName: String,
    private val sessionManager: SessionManager,
    private val onDismissCallback: () -> Unit,
    private val onIntentConfirmedCallback: (List<SessionConstraint>) -> Unit
) {
    private val contextRef = WeakReference(context)
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var params: WindowManager.LayoutParams? = null

    // Voice input manager
    private var voiceInputManager: VoiceInputManager? = null

    // Auto-dismiss
    private var autoDismissHandler: Handler? = null
    private var autoDismissRunnable: Runnable? = null
    private val AUTO_DISMISS_DELAY_MS = 60000L

    // For dragging
    private var lastX: Int = 0
    private var lastY: Int = 0
    private var isDragging: Boolean = false

    // UI elements
    private var voiceBtn: Button? = null
    private var textInputContainer: LinearLayout? = null
    private var textInput: EditText? = null
    private var textSubmitBtn: Button? = null
    private var statusText: TextView? = null

    // Theme colors
    private var accentColor = "#FF9800"
    private var surfaceTint = "#FFFBFE"
    private var subtleTextColor = "#625B71"
    private var destructiveColor = "#B3261E"
    private var recordingColor = "#F44336"
    private var successColor = "#4CAF50"

    private val density: Float
        get() = context.resources.displayMetrics.density

    init {
        val ctx = contextRef.get()
        if (ctx != null) {
            windowManager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            initializeThemeColors(ctx)
            // Initialize VoiceInputManager
            voiceInputManager = VoiceInputManager(ctx)
        }
    }

    private fun initializeThemeColors(context: Context) {
        val isDarkMode = (context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        if (isDarkMode) {
            accentColor = "#FFB74D"
            surfaceTint = "#1E1E1E"
            subtleTextColor = "#B0B0B0"
            destructiveColor = "#F44336"
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        val ctx = contextRef.get() ?: run {
            try { onDismissCallback() } catch (e: Exception) {}
            return
        }

        if (!canDrawOverlays(ctx)) {
            ToastOverlay.show(ctx, ctx.getString(R.string.overlay_need_permission))
            try { onDismissCallback() } catch (e: Exception) {}
            return
        }

        // Check microphone permission
        val hasAudioPermission = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        android.util.Log.d("VoiceInputOverlay", "Audio permission: $hasAudioPermission")

        // Start building the UI - we'll check permission and show appropriate content
        startAutoDismissTimer()

        // Check last intent
        val hasLastIntent = try {
            sessionManager.hasLastIntent(packageName)
        } catch (e: Exception) {
            false
        }

        // Create root layout
        val rootLayout = object : FrameLayout(ctx) {
            override fun performClick(): Boolean {
                super.performClick()
                return true
            }
        }.apply {
            setBackgroundColor(Color.TRANSPARENT)
        }

        // Create card
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = createCardBackground()
            setPadding(24.dp(), 20.dp(), 24.dp(), 20.dp())
        }

        val cardParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            setMargins(16.dp(), 16.dp(), 16.dp(), 16.dp())
        }

        val maxWidthPx = min((ctx.resources.displayMetrics.widthPixels * 0.85f).roundToInt(), 360.dp())
        cardParams.width = maxWidthPx
        card.layoutParams = cardParams

        // Title
        val title = TextView(ctx).apply {
            text = if (!hasAudioPermission) {
                ctx.getString(R.string.permission_required)
            } else {
                ctx.getString(R.string.voice_input_title)
            }
            textSize = 20f
            setTextColor(if (isDarkMode(ctx)) Color.WHITE else Color.BLACK)
            setPadding(0, 0, 0, 8.dp())
        }
        card.addView(title)

        // Permission warning or subtitle
        if (!hasAudioPermission) {
            val permissionWarning = TextView(ctx).apply {
                text = ctx.getString(R.string.mic_permission_required_message)
                textSize = 14f
                setTextColor(Color.parseColor(destructiveColor))
                setPadding(0, 0, 0, 20.dp())
            }
            card.addView(permissionWarning)

            // Open settings button
            val settingsBtn = Button(ctx).apply {
                text = ctx.getString(R.string.open_settings)
                setOnClickListener {
                    try {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = android.net.Uri.fromParts("package", ctx.packageName, null)
                        }
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        ctx.startActivity(intent)
                    } catch (e: Exception) {
                        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        ctx.startActivity(intent)
                    }
                    dismiss()
                }
            }
            stylePrimaryButton(settingsBtn)
            settingsBtn.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 8.dp()
            }
            card.addView(settingsBtn)

            // Cancel button
            val cancelBtn = Button(ctx).apply {
                text = ctx.getString(R.string.cancel)
                setOnClickListener {
                    dismiss()
                }
            }
            styleTextButton(cancelBtn)
            cancelBtn.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            card.addView(cancelBtn)

            rootLayout.addView(card)

            // Add to window and return early
            overlayView = rootLayout

            params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 50
                y = 200
            }

            try {
                windowManager?.addView(rootLayout, params)
            } catch (e: Exception) {
                try { onDismissCallback() } catch (e2: Exception) {}
            }
            return
        }

        // Subtitle (only if permission granted)
        val subtitle = TextView(ctx).apply {
            text = ctx.getString(R.string.voice_input_subtitle, appName)
            textSize = 14f
            setTextColor(Color.parseColor(subtleTextColor))
            setPadding(0, 0, 0, 20.dp())
        }
        card.addView(subtitle)

        // Status text (for showing processing state)
        statusText = TextView(ctx).apply {
            text = ""
            textSize = 12f
            setTextColor(Color.parseColor(accentColor))
            setPadding(0, 0, 0, 12.dp())
            visibility = View.GONE
        }
        card.addView(statusText)

        // Continue last intent button
        if (hasLastIntent) {
            val lastIntentBtn = Button(ctx).apply {
                text = ctx.getString(R.string.continue_last_intent)
                setOnClickListener {
                    val lastConstraints = sessionManager.loadLastIntent(packageName)
                    if (lastConstraints != null) {
                        onIntentConfirmedCallback(lastConstraints)
                    }
                    dismiss()
                }
            }
            styleOutlineButton(lastIntentBtn)
            lastIntentBtn.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 12.dp()
            }
            card.addView(lastIntentBtn)

            // "Or" text
            val orText = TextView(ctx).apply {
                text = ctx.getString(R.string.common_or)
                textSize = 14f
                setTextColor(Color.parseColor(subtleTextColor))
                gravity = android.view.Gravity.CENTER
                setPadding(0, 4.dp(), 0, 12.dp())
            }
            card.addView(orText)
        }

        // Voice button
        voiceBtn = Button(ctx).apply {
            text = ctx.getString(R.string.start_recording)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                android.util.Log.e("VoiceInputOverlay", ">>> Voice button CLICKED <<<")
                android.util.Log.e("VoiceInputOverlay", "voiceInputManager: $voiceInputManager")
                if (voiceInputManager == null) {
                    android.util.Log.e("VoiceInputOverlay", "voiceInputManager is NULL!")
                    return@setOnClickListener
                }
                val state = voiceInputManager?.recordingState?.value
                android.util.Log.e("VoiceInputOverlay", "Current state: $state")

                // Only handle IDLE and RECORDING states for button click
                when (state) {
                    VoiceRecordingState.RECORDING -> {
                        // Stop recording
                        android.util.Log.d("VoiceInputOverlay", "Stopping recording")
                        voiceInputManager?.stopRecording()
                    }
                    VoiceRecordingState.IDLE -> {
                        // Start recording
                        android.util.Log.d("VoiceInputOverlay", "Starting recording")
                        try {
                            // Set current app context for voice input
                            voiceInputManager?.setCurrentApp(packageName, appName)
                            val started = voiceInputManager?.startRecording()
                            android.util.Log.d("VoiceInputOverlay", "Start result: $started")
                            if (started == false) {
                                statusText?.text = ctx.getString(R.string.voice_recording_start_failed)
                                statusText?.visibility = View.VISIBLE
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("VoiceInputOverlay", "Exception in startRecording", e)
                            statusText?.text = ctx.getString(R.string.voice_err_recording_failed_with_msg, e.message ?: "")
                            statusText?.visibility = View.VISIBLE
                        }
                    }
                    VoiceRecordingState.ERROR -> {
                        // Reset and start recording again
                        android.util.Log.d("VoiceInputOverlay", "Resetting and starting recording")
                        try {
                            // Set current app context for voice input
                            voiceInputManager?.setCurrentApp(packageName, appName)
                            val started = voiceInputManager?.startRecording()
                            android.util.Log.d("VoiceInputOverlay", "Start result: $started")
                        } catch (e: Exception) {
                            android.util.Log.e("VoiceInputOverlay", "Exception in startRecording", e)
                        }
                    }
                    VoiceRecordingState.PROCESSING, VoiceRecordingState.TRANSCRIBED -> {
                        // Do nothing - wait for processing to complete
                        android.util.Log.d("VoiceInputOverlay", "Processing in progress, ignoring click")
                        statusText?.text = ctx.getString(R.string.voice_processing)
                        statusText?.visibility = View.VISIBLE
                    }
                    else -> {
                        // Ignore other states
                    }
                }
            }
        }
        stylePrimaryButton(voiceBtn!!)
        voiceBtn!!.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = 8.dp()
        }
        card.addView(voiceBtn)

        // Text input container
        textInputContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }

        val textBtn = Button(ctx).apply {
            text = ctx.getString(R.string.use_text_input)
            setOnClickListener {
                showTextInput(ctx, textInputContainer!!)
            }
        }
        styleOutlineButton(textBtn)
        textBtn.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = 8.dp()
        }
        textInputContainer!!.addView(textBtn)
        card.addView(textInputContainer)

        // Skip button
        val skipBtn = Button(ctx).apply {
            text = ctx.getString(R.string.skip)
            setOnClickListener {
                dismiss()
            }
        }
        styleTextButton(skipBtn)
        skipBtn.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        card.addView(skipBtn)

        rootLayout.addView(card)

        // Touch listener for dragging
        rootLayout.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isDragging = false
                    lastX = event.rawX.toInt()
                    lastY = event.rawY.toInt()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX.toInt() - lastX
                    val deltaY = event.rawY.toInt() - lastY

                    if (kotlin.math.abs(deltaX) > 10 || kotlin.math.abs(deltaY) > 10) {
                        isDragging = true
                        params?.let { p ->
                            p.x = p.x + deltaX
                            p.y = p.y + deltaY
                            windowManager?.updateViewLayout(rootLayout, p)
                        }
                        lastX = event.rawX.toInt()
                        lastY = event.rawY.toInt()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    isDragging = false
                    true
                }
                else -> false
            }
        }

        overlayView = rootLayout

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50
            y = 200
        }

        try {
            windowManager?.addView(rootLayout, params)
            // Start observing state changes
            observeVoiceInputState()
        } catch (e: Exception) {
            try { onDismissCallback() } catch (e2: Exception) {}
        }
    }

    /**
     * Observe VoiceInputManager state changes and update UI
     */
    private fun observeVoiceInputState() {
        val ctx = contextRef.get() ?: return
        val manager = voiceInputManager ?: return

        android.util.Log.d("VoiceInputOverlay", "Starting state observer, voiceBtn: $voiceBtn")

        // Use a handler to poll state since we can't collect flows from WindowManager overlay
        val handler = Handler(Looper.getMainLooper())
        var pollCount = 0
        val pollRunnable = object : Runnable {
            override fun run() {
                pollCount++
                val state = manager.recordingState.value

                android.util.Log.d("VoiceInputOverlay", "Poll $pollCount: state=$state, btn=$voiceBtn")

                // Get fresh reference to button
                val btn = voiceBtn

                if (btn == null) {
                    android.util.Log.w("VoiceInputOverlay", "voiceBtn is null, retrying...")
                    handler.postDelayed(this, 500)
                    return
                }

                when (state) {
                    VoiceRecordingState.RECORDING -> {
                        btn.text = ctx.getString(R.string.stop_recording)
                        styleRecordingButton(btn)
                        // Show real-time recognized text during recording
                        val recognizedText = manager.recognizedText.value
                        if (!recognizedText.isNullOrBlank()) {
                            statusText?.text = ctx.getString(R.string.voice_recognizing_text, recognizedText)
                        } else {
                            statusText?.text = ctx.getString(R.string.voice_recording)
                        }
                        statusText?.visibility = View.VISIBLE
                    }
                    VoiceRecordingState.PROCESSING -> {
                        btn.text = ctx.getString(R.string.processing)
                        btn.isEnabled = false
                        statusText?.text = ctx.getString(R.string.voice_recognizing)
                        statusText?.visibility = View.VISIBLE
                    }
                    VoiceRecordingState.TRANSCRIBED -> {
                        val text = manager.recognizedText.value
                        statusText?.text = ctx.getString(R.string.voice_recognizing_text, text)
                    }
                    VoiceRecordingState.PARSED -> {
                        // Success! Get parsed constraints and create session
                        val parsed = manager.parsedIntent.value
                        if (parsed != null && parsed.constraints.isNotEmpty()) {
                            statusText?.text = ctx.getString(R.string.voice_parse_success)
                            statusText?.setTextColor(Color.parseColor(successColor))

                            // Save last intent
                            sessionManager.saveLastIntent(packageName, parsed.constraints)

                            // Create session
                            onIntentConfirmedCallback(parsed.constraints)
                            dismiss()
                            return
                        }
                    }
                    VoiceRecordingState.ERROR -> {
                        val error = manager.error.value
                        statusText?.text = error ?: ctx.getString(R.string.common_error)
                        statusText?.setTextColor(Color.parseColor(destructiveColor))
                        voiceBtn?.text = ctx.getString(R.string.start_recording)
                        stylePrimaryButton(voiceBtn!!)
                        voiceBtn?.isEnabled = true
                    }
                    VoiceRecordingState.IDLE -> {
                        voiceBtn?.text = ctx.getString(R.string.start_recording)
                        stylePrimaryButton(voiceBtn!!)
                        voiceBtn?.isEnabled = true
                        statusText?.visibility = View.GONE
                    }
                }

                // Continue polling - always poll while the overlay is visible
                // Only stop when explicitly dismissed or on PARSED (which calls dismiss())
                if (state != VoiceRecordingState.PARSED) {
                    android.util.Log.d("VoiceInputOverlay", "Scheduling next poll, current state: $state")
                    handler.postDelayed(this, 500)
                } else {
                    android.util.Log.d("VoiceInputOverlay", "Polling stopped - PARSED state")
                }
            }
        }
        handler.post(pollRunnable)
    }

    private fun showTextInput(ctx: Context, container: LinearLayout) {
        container.removeAllViews()

        // EditText
        textInput = EditText(ctx).apply {
            hint = ctx.getString(R.string.input_intent_hint)
            setPadding(16.dp(), 12.dp(), 16.dp(), 12.dp())
            setBackgroundResource(android.R.drawable.edit_text)
            isFocusable = true
            isFocusableInTouchMode = true
            requestFocus()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 8.dp()
            }
        }
        container.addView(textInput)

        // Submit button
        textSubmitBtn = Button(ctx).apply {
            text = ctx.getString(R.string.confirm)
            setOnClickListener {
                val text = textInput?.text?.toString()
                if (!text.isNullOrBlank()) {
                    // Parse the text input
                    voiceInputManager?.parseTextInput(text, packageName, appName)
                    // Show processing
                    statusText?.text = ctx.getString(R.string.voice_parsing)
                    statusText?.visibility = View.VISIBLE
                    textSubmitBtn?.isEnabled = false

                    // Start observing for result
                    observeTextInputResult()
                }
            }
        }
        stylePrimaryButton(textSubmitBtn!!)
        textSubmitBtn!!.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        container.addView(textSubmitBtn)

        // Cancel button
        val cancelBtn = Button(ctx).apply {
            text = ctx.getString(R.string.cancel)
            setOnClickListener {
                dismiss()
            }
        }
        styleTextButton(cancelBtn)
        cancelBtn.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        container.addView(cancelBtn)
    }

    private fun observeTextInputResult() {
        val ctx = contextRef.get() ?: return
        val manager = voiceInputManager ?: return

        val handler = Handler(Looper.getMainLooper())
        val pollRunnable = object : Runnable {
            override fun run() {
                val state = manager.recordingState.value

                when (state) {
                    VoiceRecordingState.PARSED -> {
                        val parsed = manager.parsedIntent.value
                        if (parsed != null && parsed.constraints.isNotEmpty()) {
                            statusText?.text = ctx.getString(R.string.voice_parse_success)

                            // Save last intent
                            sessionManager.saveLastIntent(packageName, parsed.constraints)

                            // Create session
                            onIntentConfirmedCallback(parsed.constraints)
                            dismiss()
                            return
                        }
                    }
                    VoiceRecordingState.ERROR -> {
                        val error = manager.error.value
                        statusText?.text = error ?: ctx.getString(R.string.voice_err_parse_failed_simple)
                        statusText?.setTextColor(Color.parseColor(destructiveColor))
                        textSubmitBtn?.isEnabled = true
                    }
                    else -> {}
                }

                if (state != VoiceRecordingState.PARSED && state != VoiceRecordingState.ERROR) {
                    handler.postDelayed(this, 500)
                }
            }
        }
        handler.post(pollRunnable)
    }

    private fun isDarkMode(ctx: Context): Boolean {
        return (ctx.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    private fun createCardBackground(): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = 28.dp().toFloat()
            setColor(Color.parseColor(surfaceTint))
            setStroke(1.dp(), Color.parseColor("#12000000"))
        }
    }

    private fun stylePrimaryButton(button: Button) {
        button.setTextColor(Color.WHITE)
        button.textSize = 14f
        // Use StateListDrawable for click feedback
        val states = StateListDrawable().apply {
            // Pressed state
            addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply {
                cornerRadius = 20.dp().toFloat()
                setColor(Color.parseColor("#E65100")) // Darker orange
            })
            // Normal state
            addState(intArrayOf(), GradientDrawable().apply {
                cornerRadius = 20.dp().toFloat()
                setColor(Color.parseColor(accentColor))
            })
        }
        button.background = states
        button.setPadding(24.dp(), 14.dp(), 24.dp(), 14.dp())
    }

    private fun styleRecordingButton(button: Button) {
        button.setTextColor(Color.WHITE)
        button.textSize = 14f
        val states = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply {
                cornerRadius = 20.dp().toFloat()
                setColor(Color.parseColor("#D32F2F")) // Darker red
            })
            addState(intArrayOf(), GradientDrawable().apply {
                cornerRadius = 20.dp().toFloat()
                setColor(Color.parseColor(recordingColor))
            })
        }
        button.background = states
        button.setPadding(24.dp(), 14.dp(), 24.dp(), 14.dp())
    }

    private fun styleOutlineButton(button: Button) {
        button.setTextColor(Color.parseColor(accentColor))
        button.textSize = 14f
        button.background = GradientDrawable().apply {
            cornerRadius = 20.dp().toFloat()
            setColor(Color.TRANSPARENT)
            setStroke(1.dp(), Color.parseColor(accentColor))
        }
        button.setPadding(24.dp(), 14.dp(), 24.dp(), 14.dp())
    }

    private fun styleTextButton(button: Button) {
        button.setTextColor(Color.parseColor(subtleTextColor))
        button.textSize = 14f
        button.background = null
    }

    // Guard to prevent re-entrant dismiss calls
    private var isDismissing = false

    fun dismiss() {
        if (isDismissing) return
        isDismissing = true

        // Stop recording if active
        voiceInputManager?.cancelRecording()

        cancelAutoDismissTimer()

        overlayView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (e: Exception) {
                // View might already be removed
            }
        }
        overlayView = null

        // Release voice input manager
        voiceInputManager?.release()
        voiceInputManager = null

        try {
            onDismissCallback()
        } catch (e: Exception) {
            android.util.Log.e("VoiceInputOverlay", "Error in dismiss callback", e)
        }

        isDismissing = false
    }

    private fun Int.dp(): Int = (this * density).roundToInt()

    private fun canDrawOverlays(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }

    private fun startAutoDismissTimer() {
        cancelAutoDismissTimer()

        autoDismissHandler = Handler(Looper.getMainLooper())
        autoDismissRunnable = Runnable {
            contextRef.get()?.let { ctx ->
                ToastOverlay.show(ctx, ctx.getString(R.string.voice_err_timeout))
            }
            dismiss()
        }
        autoDismissHandler?.postDelayed(autoDismissRunnable!!, AUTO_DISMISS_DELAY_MS)
    }

    private fun cancelAutoDismissTimer() {
        autoDismissRunnable?.let { autoDismissHandler?.removeCallbacks(it) }
        autoDismissRunnable = null
        autoDismissHandler = null
    }

    /**
     * Get overlay position and size for masking in screenshots
     */
    fun getOverlayBounds(): android.graphics.Rect? {
        val view = overlayView ?: return null
        val layoutParams = params ?: return null

        val width = if (view.width > 0) view.width else layoutParams.width
        val height = if (view.height > 0) view.height else layoutParams.height

        val screenWidth = context.resources.displayMetrics.widthPixels
        val screenHeight = context.resources.displayMetrics.heightPixels

        // Calculate actual left position based on gravity
        val gravity = layoutParams.gravity and android.view.Gravity.HORIZONTAL_GRAVITY_MASK
        val left = when (gravity) {
            android.view.Gravity.END -> screenWidth - layoutParams.x - width
            android.view.Gravity.CENTER_HORIZONTAL -> (screenWidth - width) / 2 + layoutParams.x
            else -> layoutParams.x // START or default
        }

        if (width <= 0 || height <= 0) {
            // Estimate based on typical voice input overlay size
            val estimatedWidth = (280 * density).toInt()
            val estimatedHeight = (200 * density).toInt()
            return android.graphics.Rect(
                left,
                layoutParams.y,
                left + estimatedWidth,
                layoutParams.y + estimatedHeight
            )
        }

        return android.graphics.Rect(
            left,
            layoutParams.y,
            left + width,
            layoutParams.y + height
        )
    }

    companion object {
        private var currentOverlay: VoiceInputOverlay? = null

        fun show(
            context: Context,
            appName: String,
            packageName: String,
            sessionManager: SessionManager,
            onDismiss: () -> Unit,
            onIntentConfirmed: (List<SessionConstraint>) -> Unit
        ) {
            dismiss()

            val overlay = VoiceInputOverlay(
                context = context,
                appName = appName,
                packageName = packageName,
                sessionManager = sessionManager,
                onDismissCallback = onDismiss,
                onIntentConfirmedCallback = onIntentConfirmed
            )
            overlay.show()
            currentOverlay = overlay
        }

        fun dismiss() {
            currentOverlay?.dismiss()
            currentOverlay = null
        }

        fun isShowing(): Boolean {
            return currentOverlay?.overlayView != null
        }

        /**
         * Get current voice input overlay bounds for masking in screenshots
         */
        fun getCurrentOverlayBounds(): android.graphics.Rect? {
            return currentOverlay?.getOverlayBounds()
        }
    }
}
