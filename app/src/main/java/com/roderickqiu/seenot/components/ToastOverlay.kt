package com.roderickqiu.seenot.components

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import com.roderickqiu.seenot.R
import java.lang.ref.WeakReference
import kotlin.math.roundToInt

class ToastOverlay private constructor(
    context: Context,
    private val message: String,
    private val duration: Long = 3000L // 3 seconds default
) {
    // Memory leak protection with WeakReference
    private val contextRef = WeakReference(context)

    // Get context safely
    private val context: Context?
        get() = contextRef.get()

    private var windowManager: WindowManager? = null
    private var toastView: View? = null
    private var toastParams: WindowManager.LayoutParams? = null

    // Auto-dismiss functionality
    private var autoDismissHandler: Handler? = null
    private var autoDismissRunnable: Runnable? = null

    // Theme colors based on system theme
    private lateinit var backgroundColor: String
    private lateinit var textColor: String
    private lateinit var borderColor: String

    private val density: Float
        get() = context?.resources?.displayMetrics?.density ?: 1f

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
            backgroundColor = "#CC1E1E1E" // Dark semi-transparent background
            textColor = "#FFFFFF" // White text
            borderColor = "#33FFFFFF" // Light border
        } else {
            backgroundColor = "#CCFFFFFF" // Light semi-transparent background
            textColor = "#FF000000" // Black text
            borderColor = "#33000000" // Dark border
        }
    }

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
        context.getSharedPreferences("toast_overlay_prefs", Context.MODE_PRIVATE)
            .edit()
            .putInt("last_x", x)
            .putInt("last_y", y)
            .apply()
    }

    private fun loadLastPosition(context: Context): Pair<Int, Int> {
        val prefs = context.getSharedPreferences("toast_overlay_prefs", Context.MODE_PRIVATE)
        val defaultX = 50
        val defaultY = 100
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
            dismiss()
        }
        autoDismissHandler?.postDelayed(autoDismissRunnable!!, duration)
    }

    private fun cancelAutoDismissTimer() {
        autoDismissRunnable?.let { autoDismissHandler?.removeCallbacks(it) }
        autoDismissRunnable = null
        autoDismissHandler = null
    }

    fun show() {
        val ctx = context ?: return

        // Check if overlay permission and accessibility service are enabled
        if (!canDrawOverlays(ctx) || !isAccessibilityServiceEnabled(ctx)) {
            // Fallback to traditional toast
            Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show()
            return
        }

        // Start auto-dismiss timer
        startAutoDismissTimer()

        // Create toast overlay
        val toastLayout = FrameLayout(ctx).apply {
            setBackgroundColor("#00000000".toColorInt()) // Transparent background
        }

        val toastContainer = FrameLayout(ctx).apply {
            background = GradientDrawable().apply {
                cornerRadius = 16.dp().toFloat()
                setColor(Color.parseColor(backgroundColor))
                setStroke(1.dp(), Color.parseColor(borderColor))
            }
            elevation = 8f
            setPadding(16.dp(), 12.dp(), 16.dp(), 12.dp())
            // Set maximum width to 80% of screen width
            val maxWidth = (ctx.resources.displayMetrics.widthPixels * 0.8f).toInt()
            layoutParams = FrameLayout.LayoutParams(
                maxWidth,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val toastText = TextView(ctx).apply {
            text = message
            textSize = 14f
            setTextColor(Color.parseColor(textColor))
            // Remove maxLines and ellipsize to allow unlimited text display
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }

        toastContainer.addView(toastText)
        toastLayout.addView(toastContainer)

        // Set layout params
        toastParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            // For top-centered toast, we don't need position offsets
            // val (savedX, savedY) = loadLastPosition(ctx)
            // x = savedX
            // y = savedY
        }

        try {
            windowManager?.addView(toastLayout, toastParams)
            toastView = toastLayout
        } catch (e: Exception) {
            // Fallback to traditional toast
            Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show()
        }
    }

    fun dismiss() {
        // Cancel auto-dismiss timer
        cancelAutoDismissTimer()

        // Remove toast overlay
        toastView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (e: Exception) {
                // View might already be removed
            }
        }
        toastView = null
    }

    private fun Int.dp(): Int = (this * density).roundToInt()

    companion object {
        fun show(context: Context, message: String, duration: Long = 3000L) {
            val toast = ToastOverlay(context, message, duration)
            toast.show()
        }
    }
}
