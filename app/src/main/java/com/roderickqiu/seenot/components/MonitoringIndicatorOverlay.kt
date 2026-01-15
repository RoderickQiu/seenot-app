package com.roderickqiu.seenot.components

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.widget.FrameLayout
import android.widget.ImageView
import android.util.Log
import androidx.core.graphics.toColorInt
import androidx.palette.graphics.Palette
import com.roderickqiu.seenot.R
import java.lang.ref.WeakReference
import kotlin.math.roundToInt

class MonitoringIndicatorOverlay(
    context: Context,
    private val appName: String,
    private val onClickCallback: () -> Unit
) {
    private val contextRef = WeakReference(context)

    private val context: Context?
        get() = contextRef.get()

    private var windowManager: WindowManager? = null
    private var indicatorView: View? = null
    private var indicatorParams: WindowManager.LayoutParams? = null

    private lateinit var backgroundColor: String
    private lateinit var textColor: String
    private lateinit var borderColor: String

    private val density: Float
        get() = context?.resources?.displayMetrics?.density ?: 1f

    private var lastX: Int = 0
    private var lastY: Int = 0
    private var isDragging: Boolean = false

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
            backgroundColor = "#E61E1E1E"
            textColor = "#FFFFFF"
            borderColor = "#66FFFFFF"
        } else {
            backgroundColor = "#E6FFFFFF"
            textColor = "#FF000000"
            borderColor = "#66000000"
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

    private fun savePosition(context: Context, x: Int, y: Int) {
        context.getSharedPreferences("monitoring_indicator_prefs", Context.MODE_PRIVATE)
            .edit()
            .putInt("last_x", x)
            .putInt("last_y", y)
            .apply()
    }

    private fun loadLastPosition(context: Context): Pair<Int, Int> {
        val prefs = context.getSharedPreferences("monitoring_indicator_prefs", Context.MODE_PRIVATE)
        val defaultX = 16
        val defaultY = 100
        return Pair(
            prefs.getInt("last_x", defaultX),
            prefs.getInt("last_y", defaultY)
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        val ctx = context ?: return

        if (!canDrawOverlays(ctx)) {
            return
        }

        if (!isAccessibilityServiceEnabled(ctx)) {
            return
        }

        val indicatorLayout = object : FrameLayout(ctx) {
            override fun performClick(): Boolean {
                super.performClick()
                return true
            }
        }.apply {
            setBackgroundColor("#00000000".toColorInt())
        }

        val buttonSize = 28.dp()
        
        val seenotIcon: Drawable? = try {
            val packageManager = ctx.packageManager
            val appInfo = packageManager.getApplicationInfo(ctx.packageName, 0)
            packageManager.getApplicationIcon(appInfo)
        } catch (e: Exception) {
            Log.e("MonitoringIndicatorOverlay", "Failed to get SeeNot icon", e)
            null
        }
        
        val iconBackgroundColor = extractDominantColor(seenotIcon) ?: Color.parseColor(backgroundColor)
        
        val button = ImageView(ctx).apply {
            setImageDrawable(seenotIcon)
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(buttonSize, buttonSize)
            background = GradientDrawable().apply {
                cornerRadius = (buttonSize / 2).toFloat()
                setColor(iconBackgroundColor)
                setStroke(1.dp(), Color.parseColor(borderColor))
            }
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        }

        indicatorLayout.addView(button)
        indicatorLayout.isClickable = true
        indicatorLayout.isFocusable = true

        indicatorParams = WindowManager.LayoutParams(
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

        indicatorLayout.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    Log.d("MonitoringIndicatorOverlay", "ACTION_DOWN received")
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
                        indicatorParams?.let { params ->
                            params.x = params.x + deltaX
                            params.y = params.y + deltaY
                            windowManager?.updateViewLayout(indicatorLayout, params)
                        }
                        lastX = event.rawX.toInt()
                        lastY = event.rawY.toInt()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    Log.d("MonitoringIndicatorOverlay", "ACTION_UP received, isDragging: $isDragging")
                    if (!isDragging) {
                        try {
                            onClickCallback()
                            Log.d("MonitoringIndicatorOverlay", "Callback invoked")
                        } catch (e: Exception) {
                            Log.e("MonitoringIndicatorOverlay", "Error invoking callback", e)
                        }
                    } else {
                        indicatorParams?.let { params ->
                            savePosition(ctx, params.x, params.y)
                        }
                    }
                    isDragging = false
                    true
                }
                else -> false
            }
        }

        try {
            // Remove existing view if any, then add to ensure it's on top
            indicatorView?.let { view ->
                try {
                    windowManager?.removeView(view)
                } catch (e: Exception) {
                    // View might not exist, ignore
                }
            }
            windowManager?.addView(indicatorLayout, indicatorParams)
            indicatorView = indicatorLayout
        } catch (e: Exception) {
            Log.e("MonitoringIndicatorOverlay", "Failed to show indicator", e)
        }
    }

    fun dismiss() {
        indicatorView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (e: Exception) {
            }
        }
        indicatorView = null
    }

    private fun Int.dp(): Int = (this * density).roundToInt()
    
    private fun extractDominantColor(drawable: Drawable?): Int? {
        if (drawable == null) return null
        return try {
            val bitmap = Bitmap.createBitmap(
                drawable.intrinsicWidth.coerceAtLeast(1),
                drawable.intrinsicHeight.coerceAtLeast(1),
                Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            
            val palette = Palette.from(bitmap).generate()
            palette.getDominantColor(Color.parseColor(backgroundColor))
        } catch (e: Exception) {
            Log.e("MonitoringIndicatorOverlay", "Failed to extract color from icon", e)
            null
        }
    }

    companion object {
        private var currentInstance: MonitoringIndicatorOverlay? = null

        fun show(context: Context, appName: String, onButtonClick: () -> Unit) {
            // Always create new instance to ensure callback is fresh
            currentInstance?.dismiss()
            val indicator = MonitoringIndicatorOverlay(context, appName, onButtonClick)
            indicator.show()
            currentInstance = indicator
        }

        fun dismiss() {
            currentInstance?.dismiss()
            currentInstance = null
        }
    }
}
