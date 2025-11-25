package com.roderickqiu.seenot.components

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import com.roderickqiu.seenot.MainActivity
import kotlin.math.min
import kotlin.math.roundToInt
import com.roderickqiu.seenot.R
import com.roderickqiu.seenot.service.A11yService

class CoordPickOverlay(
    private val context: Context,
    private val onCoordinateSelected: (String) -> Unit,
    private val onDismiss: () -> Unit
) {
    private var windowManager: WindowManager? = null
    private var controlOverlayView: View? = null
    private var fullScreenOverlayView: View? = null
    private var controlParams: WindowManager.LayoutParams? = null
    private var fullScreenParams: WindowManager.LayoutParams? = null

    // For dragging functionality
    private var lastX: Int = 0
    private var lastY: Int = 0
    private var isDragging: Boolean = false
    private val density = context.resources.displayMetrics.density
    private val accentColor = Color.parseColor("#F6C439")
    private val accentDark = Color.parseColor("#3A2E00")
    private val surfaceTint = Color.parseColor("#FFFCF3")
    private val containerColor = Color.parseColor("#FFF3C4")
    private val subtleTextColor = Color.parseColor("#8E8266")
    private val successColor = Color.parseColor("#4CAF50")
    private val destructiveColor = Color.parseColor("#E57373")

    init {
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (!canDrawOverlays()) {
            Toast.makeText(
                context,
                context.getString(R.string.coordinate_picker_error),
                Toast.LENGTH_LONG
            ).show()
            onDismiss()
            return
        }

        // Check if accessibility service is enabled before showing the overlay
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(
                context,
                context.getString(R.string.accessibility_required_for_coordinate_picker),
                Toast.LENGTH_LONG
            ).show()
            onDismiss()
            return
        }

        A11yService.getInstance()?.setRulesEnabled(false)

        // Create full screen overlay first (behind control panel)
        createFullScreenOverlay()

        // Create control panel
        val controlLayout = object : FrameLayout(context) {
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
        val maxWidthPx = min((context.resources.displayMetrics.widthPixels * 0.78f).roundToInt(), 320.dp())
        sheetLayoutParams.width = maxWidthPx

        bottomSheet.layoutParams = sheetLayoutParams

        // Header
        val headerLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val accentBar = View(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = 999f
                setColor(accentColor)
            }
        }
        val accentParams = LinearLayout.LayoutParams(8.dp(), 32.dp()).apply {
            rightMargin = 12.dp()
        }
        accentBar.layoutParams = accentParams

        val title = TextView(context).apply {
            text = context.getString(R.string.coordinate_picker_panel_title)
            textSize = 18f
            setTextColor(ContextCompat.getColor(context, android.R.color.black))
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        val statusBadge = TextView(context).apply {
            textSize = 12f
            setPadding(12.dp(), 6.dp(), 12.dp(), 6.dp())
            updateBadge(
                R.string.coordinate_picker_status_idle,
                accentColor,
                accentDark
            )
        }

        headerLayout.addView(accentBar)
        headerLayout.addView(title)
        headerLayout.addView(statusBadge)
        bottomSheet.addView(headerLayout)

        val helperText = TextView(context).apply {
            text = context.getString(R.string.coordinate_picker_panel_hint)
            textSize = 14f
            setTextColor(subtleTextColor)
            setPadding(0, 12.dp(), 0, 8.dp())
        }

        bottomSheet.addView(helperText)

        val divider = View(context).apply {
            setBackgroundColor("#1A000000".toColorInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                1
            )
        }

        bottomSheet.addView(divider)

        val coordinateContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 16.dp().toFloat()
                setColor(containerColor)
            }
            setPadding(16.dp(), 12.dp(), 16.dp(), 12.dp())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 16.dp()
            }
        }

        val coordinateLabel = TextView(context).apply {
            text = context.getString(R.string.coordinate_picker_select_instruction)
            textSize = 13f
            setTextColor(subtleTextColor)
        }

        // Add coordinate text
        val coordinateText = TextView(context).apply {
            text = context.getString(R.string.coordinate_picker_no_selection)
            textSize = 14f
            setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
            setPadding(0, 8.dp(), 0, 0)
        }

        coordinateContainer.addView(coordinateLabel)
        coordinateContainer.addView(coordinateText)
        bottomSheet.addView(coordinateContainer)

        // Add buttons
        val selectButton = Button(context).apply {
            text = context.getString(R.string.coordinate_picker_select_mode)
        }.also { stylePrimaryButton(it) }

        val testButton = Button(context).apply {
            text = context.getString(R.string.coordinate_picker_test)
        }.also { styleOutlineButton(it, accentColor, accentDark) }

        val saveButton = Button(context).apply {
            text = context.getString(R.string.save)
        }.also { styleOutlineButton(it, accentColor, accentDark) }

        val cancelButton = Button(context).apply {
            text = context.getString(R.string.cancel)
            setOnClickListener {
                dismiss()
            }
        }.also { styleOutlineButton(it, destructiveColor) }

        testButton.updateEnabledState(false)
        saveButton.updateEnabledState(false)

        // Set up select button functionality
        selectButton.setOnClickListener {
            // Check if accessibility service is enabled
            if (!isAccessibilityServiceEnabled()) {
                Toast.makeText(
                    context,
                    context.getString(R.string.accessibility_required_for_coordinate_picker),
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }

            // Enable selection mode on full screen overlay
            enableSelectionOnFullScreenOverlay(
                coordinateText,
                testButton,
                saveButton,
                selectButton,
                statusBadge
            )
        }

        // Set up test button functionality
        testButton.setOnClickListener {
            Toast.makeText(
                context,
                context.getString(R.string.coordinate_picker_no_selection_warning),
                Toast.LENGTH_SHORT
            ).show()
        }

        // Set up save button functionality
        saveButton.setOnClickListener {
            Toast.makeText(
                context,
                context.getString(R.string.coordinate_picker_no_selection_warning),
                Toast.LENGTH_SHORT
            ).show()
        }

        val primaryButtonRow = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        selectButton.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = 16.dp()
        }

        primaryButtonRow.addView(selectButton)
        bottomSheet.addView(primaryButtonRow)

        val secondaryButtonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val testButtonParams = LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f
        ).apply {
            topMargin = 12.dp()
            marginEnd = 8.dp()
        }

        val saveButtonParams = LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f
        ).apply {
            topMargin = 12.dp()
            marginEnd = 8.dp()
        }

        val cancelButtonParams = LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f
        ).apply {
            topMargin = 12.dp()
        }

        testButton.layoutParams = testButtonParams
        saveButton.layoutParams = saveButtonParams
        cancelButton.layoutParams = cancelButtonParams

        secondaryButtonRow.addView(testButton)
        secondaryButtonRow.addView(saveButton)
        secondaryButtonRow.addView(cancelButton)

        bottomSheet.addView(secondaryButtonRow)
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
            x = 100
            y = 100
        }

        try {
            windowManager?.addView(controlOverlayView, controlParams)
        } catch (e: Exception) {
            Toast.makeText(
                context,
                context.getString(R.string.coordinate_picker_error),
                Toast.LENGTH_SHORT
            ).show()
            A11yService.getInstance()?.setRulesEnabled(true)
            onDismiss()
        }
    }

    private fun createFullScreenOverlay() {
        // Create full screen transparent overlay (behind control panel)
        val fullScreenLayout = object : FrameLayout(context) {
            override fun performClick(): Boolean {
                super.performClick()
                return true
            }
        }.apply {
            setBackgroundColor("#00000000".toColorInt()) // Fully transparent initially
        }

        fullScreenOverlayView = fullScreenLayout

        fullScreenParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, // Initially not touchable
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        try {
            windowManager?.addView(fullScreenOverlayView, fullScreenParams)
        } catch (e: Exception) {
            Toast.makeText(
                context,
                context.getString(R.string.coordinate_picker_error),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun enableSelectionOnFullScreenOverlay(
        coordinateText: TextView,
        testButton: Button,
        saveButton: Button,
        selectButton: Button,
        statusBadge: TextView
    ) {
        testButton.updateEnabledState(false)
        saveButton.updateEnabledState(false)
        // Make full screen overlay touchable and semi-transparent
        fullScreenParams?.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        fullScreenOverlayView?.setBackgroundColor("#80000000".toColorInt()) // 50% transparent black
        statusBadge.updateBadge(
            R.string.coordinate_picker_select_instruction,
            accentColor,
            accentDark
        )
        coordinateText.text = context.getString(R.string.coordinate_picker_select_instruction)

        // Variables to store the selected coordinates
        var selectedX = 0
        var selectedY = 0

        // Change select button to exit selection mode button
        selectButton.text = context.getString(R.string.coordinate_picker_exit_mode)
        selectButton.setOnClickListener {
            // Reset full screen overlay to non-touchable and transparent
            resetFullScreenOverlay()
            val hasSelection = selectedX != 0 || selectedY != 0
            if (hasSelection) {
                statusBadge.updateBadge(
                    R.string.coordinate_picker_status_selected,
                    successColor,
                    Color.WHITE
                )
                testButton.updateEnabledState(true)
                saveButton.updateEnabledState(true)
            } else {
                statusBadge.updateBadge(
                    R.string.coordinate_picker_status_idle,
                    accentColor,
                    accentDark
                )
                testButton.updateEnabledState(false)
                saveButton.updateEnabledState(false)
                coordinateText.text = context.getString(R.string.coordinate_picker_no_selection)
            }
            // Change button back to select mode
            selectButton.text = context.getString(R.string.coordinate_picker_select_mode)
            selectButton.setOnClickListener {
                // Check if accessibility service is enabled
                if (!isAccessibilityServiceEnabled()) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.accessibility_required_for_coordinate_picker),
                        Toast.LENGTH_LONG
                    ).show()
                    return@setOnClickListener
                }

                // Re-enable selection mode
                enableSelectionOnFullScreenOverlay(
                    coordinateText,
                    testButton,
                    saveButton,
                    selectButton,
                    statusBadge
                )
            }
        }

        // Update test button functionality
        testButton.setOnClickListener {
            if (selectedX == 0 && selectedY == 0) {
                // No coordinate selected yet
                Toast.makeText(
                    context,
                    context.getString(R.string.coordinate_picker_no_selection_warning),
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            // Check if we are currently in the SeeNot app
            val am =
                context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val foregroundApp =
                am.runningAppProcesses?.find { it.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND }?.processName

            if (foregroundApp != null && foregroundApp.startsWith("com.roderickqiu.seenot")) {
                // We are inside SeeNot app, just show a toast
                Toast.makeText(
                    context,
                    context.getString(
                        R.string.coordinate_picker_test_click_inside_app,
                        selectedX,
                        selectedY
                    ),
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                // We are outside SeeNot app, perform the actual click
                val a11yService = com.roderickqiu.seenot.service.A11yService.getInstance()
                if (a11yService != null) {
                    a11yService.performGlobalClick(selectedX, selectedY)
                } else {
                    Toast.makeText(
                        context,
                        context.getString(R.string.accessibility_required_for_coordinate_picker),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        // Update save button functionality
        saveButton.setOnClickListener {
            if (selectedX == 0 && selectedY == 0) {
                // No coordinate selected yet
                Toast.makeText(
                    context,
                    context.getString(R.string.coordinate_picker_no_selection_warning),
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            val coordinate = "$selectedX,$selectedY"
            onCoordinateSelected(coordinate)
            // Dismiss the overlay when saving
            dismiss()
            launchSeeNotHome()
        }

        // Set touch listener for coordinate selection
        fullScreenOverlayView?.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    selectedX = event.rawX.toInt()
                    selectedY = event.rawY.toInt()
                    testButton.updateEnabledState(true)
                    saveButton.updateEnabledState(true)
                    coordinateText.text =
                        context.getString(R.string.coordinate_picker_selected, selectedX, selectedY)
                    statusBadge.updateBadge(
                        R.string.coordinate_picker_status_selected,
                        successColor,
                        Color.WHITE
                    )

                    v.performClick()
                }
            }
            true
        }

        // Update the layout params to make it touchable
        windowManager?.updateViewLayout(fullScreenOverlayView, fullScreenParams)
    }

    private fun resetFullScreenOverlay() {
        // Reset full screen overlay to transparent and non-touchable
        fullScreenOverlayView?.setBackgroundColor("#00000000".toColorInt()) // Fully transparent
        fullScreenParams?.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE // Not touchable again

        // Remove touch listener
        fullScreenOverlayView?.setOnTouchListener(null)

        // Update the layout params
        windowManager?.updateViewLayout(fullScreenOverlayView, fullScreenParams)
    }

    fun dismiss() {
        // Remove control panel
        controlOverlayView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (e: Exception) {
                // View might already be removed
            }
        }
        controlOverlayView = null

        // Remove full screen overlay if exists
        fullScreenOverlayView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (e: Exception) {
                // View might already be removed
            }
        }
        fullScreenOverlayView = null

        A11yService.getInstance()?.setRulesEnabled(true)
        onDismiss()
    }

    private fun launchSeeNotHome() {
        try {
            val intent = Intent(context, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
            context.startActivity(intent)
        } catch (ignored: Exception) {
            // If we cannot return automatically, we silently ignore to avoid crashing
        }
    }

    private fun createCardBackground(): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = 20.dp().toFloat()
            setColor(surfaceTint)
            setStroke(1.dp(), "#11000000".toColorInt())
        }
    }

    private fun createBadgeBackground(color: Int, alphaFactor: Float = 0.2f): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = 999f
            setColor(adjustAlpha(color, alphaFactor))
        }
    }

    private fun stylePrimaryButton(button: Button) {
        button.setTextColor(accentDark)
        button.background = GradientDrawable().apply {
            cornerRadius = 16.dp().toFloat()
            setColor(accentColor)
            setStroke(2.dp(), adjustAlpha(accentDark, 0.25f))
        }
        button.setPadding(0, 12.dp(), 0, 12.dp())
    }

    private fun styleOutlineButton(button: Button, strokeColor: Int, textColor: Int = strokeColor) {
        button.setTextColor(textColor)
        button.background = GradientDrawable().apply {
            cornerRadius = 16.dp().toFloat()
            setColor(Color.TRANSPARENT)
            setStroke(2.dp(), adjustAlpha(strokeColor, 0.4f))
        }
        button.setPadding(0, 12.dp(), 0, 12.dp())
    }

    private fun adjustAlpha(color: Int, factor: Float): Int {
        val alpha = (Color.alpha(color) * factor).roundToInt()
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }

    private fun Button.updateEnabledState(isEnabledNow: Boolean) {
        isEnabled = isEnabledNow
        alpha = if (isEnabledNow) 1f else 0.45f
    }

    private fun TextView.updateBadge(
        textRes: Int,
        color: Int,
        textColorOverride: Int? = null
    ) {
        text = context.getString(textRes)
        setTextColor(textColorOverride ?: color)
        background = createBadgeBackground(color)
    }

    private fun Int.dp(): Int = (this * density).roundToInt()

    private fun canDrawOverlays(): Boolean {
        return Settings.canDrawOverlays(context)
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val accessibilityService = "com.roderickqiu.seenot.service.A11yService"
        val settingValue = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        return settingValue != null && settingValue.contains(accessibilityService)
    }
}