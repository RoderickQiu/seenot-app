package com.roderickqiu.seenot.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
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

    init {
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (!canDrawOverlays()) {
            Toast.makeText(
                context,
                context.getString(R.string.overlay_permission_required),
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
            setBackgroundColor(ContextCompat.getColor(context, android.R.color.white))
            elevation = 8f
        }

        val layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            setMargins(16, 16, 16, 16)
        }

        bottomSheet.layoutParams = layoutParams

        // Add title
        val title = TextView(context).apply {
            text = context.getString(R.string.coordinate_picker_instruction)
            textSize = 16f
            setTextColor(ContextCompat.getColor(context, android.R.color.black))
            setPadding(16, 16, 16, 8)
        }

        bottomSheet.addView(title)

        // Add coordinate text
        val coordinateText = TextView(context).apply {
            text = context.getString(R.string.coordinate_picker_no_selection)
            textSize = 14f
            setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
            setPadding(16, 8, 16, 16)
        }

        bottomSheet.addView(coordinateText)

        // Add buttons
        val buttonLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val selectButton = Button(context).apply {
            text = context.getString(R.string.coordinate_picker_select_mode)
        }

        val testButton = Button(context).apply {
            text = context.getString(R.string.coordinate_picker_test)
            isEnabled = false
        }

        val saveButton = Button(context).apply {
            text = context.getString(R.string.save)
        }

        val cancelButton = Button(context).apply {
            text = context.getString(R.string.cancel)
            setOnClickListener {
                dismiss()
            }
        }

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
            enableSelectionOnFullScreenOverlay(coordinateText, testButton, saveButton, selectButton)
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

        val buttonParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        buttonParams.setMargins(8, 8, 8, 16)

        selectButton.layoutParams = buttonParams
        testButton.layoutParams = buttonParams
        saveButton.layoutParams = buttonParams
        cancelButton.layoutParams = buttonParams

        buttonLayout.addView(selectButton)
        buttonLayout.addView(testButton)
        buttonLayout.addView(saveButton)
        buttonLayout.addView(cancelButton)

        bottomSheet.addView(buttonLayout)
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
        selectButton: Button
    ) {
        testButton.isEnabled = false
        // Make full screen overlay touchable and semi-transparent
        fullScreenParams?.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        fullScreenOverlayView?.setBackgroundColor("#80000000".toColorInt()) // 50% transparent black

        // Variables to store the selected coordinates
        var selectedX = 0
        var selectedY = 0

        // Change select button to exit selection mode button
        selectButton.text = context.getString(R.string.coordinate_picker_exit_mode)
        selectButton.setOnClickListener {
            // Reset full screen overlay to non-touchable and transparent
            resetFullScreenOverlay()
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
                    selectButton
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
        }

        // Set touch listener for coordinate selection
        fullScreenOverlayView?.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    selectedX = event.rawX.toInt()
                    selectedY = event.rawY.toInt()
                    testButton.isEnabled = true
                    coordinateText.text =
                        context.getString(R.string.coordinate_picker_selected, selectedX, selectedY)

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