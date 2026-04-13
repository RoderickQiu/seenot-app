package com.seenot.app.ui.overlay

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
import androidx.core.graphics.toColorInt
import com.seenot.app.config.AppLocalePrefs
import java.lang.ref.WeakReference
import kotlin.math.roundToInt

class ToastOverlay private constructor(
    context: Context,
    private val message: String,
    private val duration: Long = 3000L
) {
    private val contextRef = WeakReference(context)

    private val context: Context?
        get() = contextRef.get()

    private var windowManager: WindowManager? = null
    private var toastView: View? = null
    private var toastParams: WindowManager.LayoutParams? = null

    private var autoDismissHandler: Handler? = null
    private var autoDismissRunnable: Runnable? = null

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
            backgroundColor = "#CC1E1E1E"
            textColor = "#FFFFFF"
            borderColor = "#33FFFFFF"
        } else {
            backgroundColor = "#CCFFFFFF"
            textColor = "#FF000000"
            borderColor = "#33000000"
        }
    }

    private fun canDrawOverlays(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }

    private fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val accessibilityService = "com.seenot.app.service.SeenotAccessibilityService"
        val settingValue = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        return settingValue != null && settingValue.contains(accessibilityService)
    }

    private fun startAutoDismissTimer() {
        cancelAutoDismissTimer()

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
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { show() }
            return
        }

        val ctx = context ?: return

        if (!canDrawOverlays(ctx) || !isAccessibilityServiceEnabled(ctx)) {
            Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show()
            return
        }

        startAutoDismissTimer()

        val toastLayout = FrameLayout(ctx).apply {
            setBackgroundColor("#00000000".toColorInt())
        }

        val toastContainer = FrameLayout(ctx).apply {
            background = GradientDrawable().apply {
                cornerRadius = 16.dp().toFloat()
                setColor(Color.parseColor(backgroundColor))
                setStroke(1.dp(), Color.parseColor(borderColor))
            }
            elevation = 8f
            setPadding(16.dp(), 12.dp(), 16.dp(), 12.dp())
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
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }

        toastContainer.addView(toastText)
        toastLayout.addView(toastContainer)

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
        }

        try {
            windowManager?.addView(toastLayout, toastParams)
            toastView = toastLayout
        } catch (e: Exception) {
            Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show()
        }
    }

    fun dismiss() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { dismiss() }
            return
        }

        cancelAutoDismissTimer()

        toastView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (e: Exception) {
            }
        }
        toastView = null
        
        if (currentToast === this) {
            currentToast = null
        }
    }

    private fun Int.dp(): Int = (this * density).roundToInt()

    companion object {
        @SuppressLint("StaticFieldLeak")
        private var currentToast: ToastOverlay? = null

        fun show(context: Context, message: String, duration: Long = 3000L) {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                Handler(Looper.getMainLooper()).post {
                    show(context, message, duration)
                }
                return
            }

            currentToast?.dismiss()

            val localizedContext = AppLocalePrefs.createLocalizedContext(context)
            val toast = ToastOverlay(localizedContext, message, duration)
            currentToast = toast
            toast.show()
        }

        fun dismissAll() {
            currentToast?.dismiss()
            currentToast = null
        }
    }
}
