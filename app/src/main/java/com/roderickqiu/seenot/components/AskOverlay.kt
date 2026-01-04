package com.roderickqiu.seenot.components

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import com.roderickqiu.seenot.R
import com.roderickqiu.seenot.components.ToastOverlay
import com.roderickqiu.seenot.data.ActionType
import com.roderickqiu.seenot.data.AppDataStore
import com.roderickqiu.seenot.data.Rule
import com.roderickqiu.seenot.service.A11yService
import com.roderickqiu.seenot.utils.RuleFormatter
import java.lang.ref.WeakReference
import kotlin.math.min
import kotlin.math.roundToInt

class AskOverlay(
    context: Context,
    private val appName: String,
    onRulesUpdated: (Map<String, Boolean>) -> Unit,
    onDismiss: () -> Unit
) {
    // Memory leak protection with WeakReference
    private val contextRef = WeakReference(context)
    private val onRulesUpdatedRef = WeakReference(onRulesUpdated)
    private val onDismissRef = WeakReference(onDismiss)

    // Get context safely
    private val context: Context?
        get() = contextRef.get()

    // Get callbacks safely
    private val onRulesUpdated: ((Map<String, Boolean>) -> Unit)?
        get() = onRulesUpdatedRef.get()

    private val onDismiss: (() -> Unit)?
        get() = onDismissRef.get()

    private var windowManager: WindowManager? = null
    private var controlOverlayView: View? = null
    private var controlParams: WindowManager.LayoutParams? = null

    // Auto-dismiss functionality
    private var autoDismissHandler: Handler? = null
    private var autoDismissRunnable: Runnable? = null
    private val AUTO_DISMISS_DELAY_MS = 30000L // 30 seconds timeout

    // For dragging functionality
    private var lastX: Int = 0
    private var lastY: Int = 0
    private var isDragging: Boolean = false

    // Dynamic theme colors based on system theme
    private lateinit var accentColor: String
    private lateinit var accentDark: String
    private lateinit var surfaceTint: String
    private lateinit var containerColor: String
    private lateinit var subtleTextColor: String
    private lateinit var successColor: String
    private lateinit var destructiveColor: String

    private val density: Float
        get() = context?.resources?.displayMetrics?.density ?: 1f

    // Rule states (ruleId -> enabled)
    private var ruleStates = mutableMapOf<String, Boolean>()

    init {
        val ctx = contextRef.get()
        if (ctx != null) {
            windowManager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            initializeThemeColors(ctx)
        }
    }

    private fun initializeThemeColors(context: Context) {
        val isDarkMode = (context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        if (isDarkMode) {
            // Dark theme colors
            accentColor = "#FFD54F"        // Slightly brighter yellow for dark backgrounds
            accentDark = "#FF8F00"         // Darker orange
            surfaceTint = "#2D2D2D"        // Dark surface
            containerColor = "#3D3D3D"     // Slightly lighter container
            subtleTextColor = "#BDBDBD"    // Light gray text
            successColor = "#4CAF50"       // Keep green as is
            destructiveColor = "#EF5350"   // Lighter red for dark theme
        } else {
            // Light theme colors (original)
            accentColor = "#F6C439"
            accentDark = "#3A2E00"
            surfaceTint = "#FFF3C4"
            containerColor = "#FFF3C4"
            subtleTextColor = "#8E8266"
            successColor = "#4CAF50"
            destructiveColor = "#E57373"
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        val ctx = context ?: return
        val onDismissCallback = onDismiss ?: return

        if (!canDrawOverlays(ctx)) {
            ToastOverlay.show(ctx, ctx.getString(R.string.coordinate_picker_error), 5000L)
            onDismissCallback()
            return
        }

        // Check if accessibility service is enabled before showing the overlay
        if (!isAccessibilityServiceEnabled(ctx)) {
            ToastOverlay.show(ctx, ctx.getString(R.string.accessibility_required_for_coordinate_picker), 5000L)
            onDismissCallback()
            return
        }

        A11yService.getInstance()?.setRulesEnabled(false)

        // Start auto-dismiss timer
        startAutoDismissTimer()

        // Create control panel
        val controlLayout = object : FrameLayout(ctx) {
            override fun performClick(): Boolean {
                super.performClick()
                return true
            }
        }.apply {
            setBackgroundColor("#00000000".toColorInt()) // Transparent background
        }

        val bottomSheet = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = createCardBackground()
            elevation = 12f
            setPadding(16.dp(), 18.dp(), 16.dp(), 18.dp())
        }

        val sheetLayoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            setMargins(16.dp(), 16.dp(), 16.dp(), 16.dp())
        }
        val maxWidthPx = min((ctx.resources.displayMetrics.widthPixels * 0.85f).roundToInt(), 400.dp())
        sheetLayoutParams.width = maxWidthPx

        bottomSheet.layoutParams = sheetLayoutParams

        // Header
        val headerLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val accentBar = View(ctx).apply {
            background = GradientDrawable().apply {
                cornerRadius = 999f
                setColor(Color.parseColor(accentColor))
            }
        }
        val accentParams = LinearLayout.LayoutParams(8.dp(), 32.dp()).apply {
            rightMargin = 12.dp()
        }
        accentBar.layoutParams = accentParams

        val title = TextView(ctx).apply {
            text = ctx.getString(R.string.ask_overlay_title)
            textSize = 18f
            setTextColor(if (isDarkMode(ctx)) Color.WHITE else ContextCompat.getColor(ctx, android.R.color.black))
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        headerLayout.addView(accentBar)
        headerLayout.addView(title)
        bottomSheet.addView(headerLayout)

        val helperText = TextView(ctx).apply {
            text = ctx.getString(R.string.ask_overlay_subtitle, appName)
            textSize = 14f
            setTextColor(Color.parseColor(subtleTextColor))
            setPadding(0, 12.dp(), 0, 16.dp())
        }

        bottomSheet.addView(helperText)

        val divider = View(ctx).apply {
            setBackgroundColor("#1A000000".toColorInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                1
            )
        }

        bottomSheet.addView(divider)

        // Load rules for this app
        val appDataStore = AppDataStore(ctx)
        val monitoringApps = appDataStore.loadMonitoringApps()
        val targetApp = monitoringApps.find { it.name == appName }

        if (targetApp != null && targetApp.rules.isNotEmpty()) {
            // Rules container
            val rulesContainer = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 16.dp(), 0, 0)
            }

            // Initialize rule states
            targetApp.rules.forEach { rule ->
                ruleStates[rule.id] = A11yService.getInstance()?.let { service ->
                    service.getConstraintManager()?.isRuleEnabled(rule.id) ?: true
                } ?: true
            }

            // Create rule items
            targetApp.rules.forEach { rule ->
                val ruleItem = createRuleItem(ctx, rule)
                rulesContainer.addView(ruleItem)
            }

            bottomSheet.addView(rulesContainer)
        } else {
            // No rules message
            val noRulesText = TextView(ctx).apply {
                text = ctx.getString(R.string.ask_overlay_no_rules)
                textSize = 14f
                setTextColor(Color.parseColor(subtleTextColor))
                setPadding(0, 16.dp(), 0, 16.dp())
                gravity = android.view.Gravity.CENTER
            }
            bottomSheet.addView(noRulesText)
        }

        // Confirm button
        val confirmButton = Button(ctx).apply {
            text = ctx.getString(R.string.ask_overlay_confirm)
            setOnClickListener {
                val callback = onRulesUpdated
                if (callback != null) {
                    try {
                        callback(ruleStates.toMap())
                        Log.d("AskOverlay", "Rules updated successfully: $ruleStates")
                    } catch (e: Exception) {
                        Log.e("AskOverlay", "Failed to update rules", e)
                    }
                } else {
                    Log.w("AskOverlay", "onRulesUpdated callback is null")
                }
                // Always dismiss the overlay
                dismiss()
            }
        }.also { stylePrimaryButton(it) }

        confirmButton.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = 16.dp()
        }

        bottomSheet.addView(confirmButton)

        // Cancel button
        val cancelButton = Button(ctx).apply {
            text = ctx.getString(R.string.cancel)
            setOnClickListener {
                dismiss()
            }
        }.also { styleOutlineButton(it, destructiveColor) }

        cancelButton.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = 8.dp()
        }

        bottomSheet.addView(cancelButton)
        controlLayout.addView(bottomSheet)

        // Set initial touch listener for dragging control panel
        controlLayout.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isDragging = false
                    lastX = event.rawX.toInt()
                    lastY = event.rawY.toInt()
                }

                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX.toInt() - lastX
                    val deltaY = event.rawY.toInt() - lastY

                    controlParams?.x = controlParams?.x?.plus(deltaX) ?: 0
                    controlParams?.y = controlParams?.y?.plus(deltaY) ?: 0

                    windowManager?.updateViewLayout(controlOverlayView, controlParams)

                    lastX = event.rawX.toInt()
                    lastY = event.rawY.toInt()

                    isDragging = true
                }

                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        // Just a click, not a drag
                        v.performClick()
                    } else {
                        // Save position after dragging
                        context?.let { ctx ->
                            controlParams?.let { params ->
                                savePosition(ctx, params.x, params.y)
                            }
                        }
                    }
                    isDragging = false
                }
            }
            true
        }

        controlOverlayView = controlLayout

        controlParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val (savedX, savedY) = loadLastPosition(ctx)
            x = savedX
            y = savedY
        }

        try {
            windowManager?.addView(controlOverlayView, controlParams)
        } catch (e: Exception) {
            contextRef.get()?.let { ctx ->
                ToastOverlay.show(ctx, ctx.getString(R.string.coordinate_picker_error), 3000L)
            }
            A11yService.getInstance()?.setRulesEnabled(true)
            onDismissRef.get()?.invoke()
        }
    }

    fun dismiss() {
        // Cancel auto-dismiss timer
        cancelAutoDismissTimer()

        // Remove control panel
        controlOverlayView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (e: Exception) {
                // View might already be removed
            }
        }
        controlOverlayView = null

        A11yService.getInstance()?.setRulesEnabled(true)
        onDismiss?.invoke()
    }

    private fun createCardBackground(): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = 20.dp().toFloat()
            setColor(Color.parseColor(surfaceTint))
            setStroke(1.dp(), Color.parseColor("#11000000"))
        }
    }

    private fun styleActionButton(button: Button) {
        button.setTextColor(Color.parseColor(accentDark))
        button.background = GradientDrawable().apply {
            cornerRadius = 12.dp().toFloat()
            setColor(Color.parseColor(accentColor))
        }
        button.setPadding(16.dp(), 12.dp(), 16.dp(), 12.dp())
    }

    private fun stylePrimaryButton(button: Button) {
        button.setTextColor(Color.parseColor(accentDark))
        button.background = GradientDrawable().apply {
            cornerRadius = 16.dp().toFloat()
            setColor(Color.parseColor(accentColor))
            setStroke(2.dp(), adjustAlpha(Color.parseColor(accentDark), 0.25f))
        }
        button.setPadding(0, 12.dp(), 0, 12.dp())
    }

    private fun createRuleItem(context: Context, rule: Rule): View {
        val ruleContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                cornerRadius = 12.dp().toFloat()
                setColor(Color.parseColor(containerColor))
            }
            setPadding(16.dp(), 12.dp(), 16.dp(), 12.dp())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 8.dp()
            }
        }

        // Rule description
        val ruleText = TextView(context).apply {
            text = RuleFormatter.formatRule(context, rule)
            textSize = 14f
            setTextColor(if (isDarkMode(context)) Color.WHITE else ContextCompat.getColor(context, android.R.color.black))
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        // Toggle switch
        val toggleSwitch = android.widget.Switch(context).apply {
            isChecked = ruleStates[rule.id] ?: true
            setOnCheckedChangeListener { _, isChecked ->
                ruleStates[rule.id] = isChecked
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        ruleContainer.addView(ruleText)
        ruleContainer.addView(toggleSwitch)

        return ruleContainer
    }

    private fun styleOutlineButton(button: Button, strokeColor: String, textColor: String = strokeColor) {
        button.setTextColor(Color.parseColor(textColor))
        button.background = GradientDrawable().apply {
            cornerRadius = 12.dp().toFloat()
            setColor(Color.TRANSPARENT)
            setStroke(2.dp(), adjustAlpha(Color.parseColor(strokeColor), 0.4f))
        }
        button.setPadding(16.dp(), 12.dp(), 12.dp(), 12.dp())
    }

    private fun adjustAlpha(color: Int, factor: Float): Int {
        val alpha = (Color.alpha(color) * factor).roundToInt()
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }

    private fun adjustAlpha(colorString: String, factor: Float): Int {
        val color = Color.parseColor(colorString)
        return adjustAlpha(color, factor)
    }

    private fun Int.dp(): Int = (this * density).roundToInt()

    private fun canDrawOverlays(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }

    private fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val accessibilityService = "com.roderickqiu.seenot.service.A11yService"
        val settingValue = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        return settingValue != null && settingValue.contains(accessibilityService)
    }

    private fun isDarkMode(context: Context): Boolean {
        return (context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    // Position memory functionality
    private fun savePosition(context: Context, x: Int, y: Int) {
        context.getSharedPreferences("ask_overlay_prefs", Context.MODE_PRIVATE)
            .edit()
            .putInt("last_x", x)
            .putInt("last_y", y)
            .apply()
    }

    private fun loadLastPosition(context: Context): Pair<Int, Int> {
        val prefs = context.getSharedPreferences("ask_overlay_prefs", Context.MODE_PRIVATE)
        val defaultX = 50
        val defaultY = 200
        return Pair(
            prefs.getInt("last_x", defaultX),
            prefs.getInt("last_y", defaultY)
        )
    }

    // Auto-dismiss functionality
    private fun startAutoDismissTimer() {
        cancelAutoDismissTimer() // Cancel any existing timer

        autoDismissHandler = Handler(Looper.getMainLooper())
        autoDismissRunnable = Runnable {
            context?.let { ctx ->
                ToastOverlay.show(ctx, ctx.getString(R.string.ask_overlay_timeout), 3000L)
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
}
