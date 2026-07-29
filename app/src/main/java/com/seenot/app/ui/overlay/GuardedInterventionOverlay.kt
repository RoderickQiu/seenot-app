package com.seenot.app.ui.overlay

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.seenot.app.R
import com.seenot.app.config.AppLocalePrefs
import com.seenot.app.utils.Logger
import kotlin.math.ceil

/** Full-screen pauses used by Guarded mode. */
object GuardedInterventionOverlay {
    private const val TAG = "GuardedIntervention"
    private const val PROGRESS_MAX = 1_000
    private const val PROGRESS_FRAME_MS = 32L
    private const val ELAPSED_REFRESH_MS = 1_000L

    private var root: View? = null
    private var windowManager: WindowManager? = null
    private val handler = Handler(Looper.getMainLooper())

    fun dismiss(@Suppress("UNUSED_PARAMETER") context: Context) {
        handler.removeCallbacksAndMessages(null)
        root?.let { view -> runCatching { windowManager?.removeView(view) } }
        root = null
        windowManager = null
    }

    fun isShowing(): Boolean = root != null

    fun showBreath(
        context: Context,
        durationMs: Long = 4_000L,
        onLeave: () -> Unit = {}
    ) {
        val localizedContext = AppLocalePrefs.createLocalizedContext(context)
        val card = buildCard(localizedContext)
        card.addView(buildTitle(localizedContext, localizedContext.getString(R.string.guarded_pause_title)))
        card.addView(buildBody(localizedContext, localizedContext.getString(R.string.guarded_pause_message)))
        card.addView(buildSecondaryAction(localizedContext, localizedContext.getString(R.string.guarded_leave_app)) {
            dismiss(localizedContext)
            onLeave()
        })
        show(localizedContext, card)
        handler.postDelayed({ dismiss(localizedContext) }, durationMs)
    }

    fun showHold(
        context: Context,
        holdMs: Long,
        consumedMs: Long,
        onComplete: () -> Unit,
        onLeave: () -> Unit = {}
    ) {
        val localizedContext = AppLocalePrefs.createLocalizedContext(context)
        val card = buildCard(localizedContext)
        val holdSeconds = ceil(holdMs / 1_000.0).toInt()
        val shownAt = SystemClock.elapsedRealtime()
        val title = buildTitle(localizedContext, formatElapsedTitle(localizedContext, consumedMs))
        card.addView(title)
        card.addView(buildBody(localizedContext, localizedContext.getString(R.string.guarded_hold_message, holdSeconds)))
        card.addView(buildPrimaryAction(localizedContext, localizedContext.getString(R.string.guarded_leave_app)) {
            dismiss(localizedContext)
            onLeave()
        })

        val progress = ProgressBar(localizedContext, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = PROGRESS_MAX
            progress = 0
            progressDrawable = buildProgressDrawable(localizedContext)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                4.dp(localizedContext)
            ).apply { topMargin = 10.dp(localizedContext) }
        }
        val label = TextView(localizedContext).apply {
            text = localizedContext.getString(R.string.guarded_hold_action, holdSeconds)
            textSize = 16f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        val holdButton = LinearLayout(localizedContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(18.dp(localizedContext), 15.dp(localizedContext), 18.dp(localizedContext), 13.dp(localizedContext))
            background = roundedDrawable(secondaryButtonColor(localizedContext), 14.dp(localizedContext).toFloat())
            isClickable = true
            isFocusable = true
            contentDescription = localizedContext.getString(R.string.guarded_hold_action, holdSeconds)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 10.dp(localizedContext) }
            addView(label)
            addView(progress)
        }

        var startedAt = 0L
        var holding = false
        lateinit var updateProgress: Runnable
        fun resetHold() {
            holding = false
            handler.removeCallbacks(updateProgress)
            progress.progress = 0
            label.text = localizedContext.getString(R.string.guarded_hold_action, holdSeconds)
        }
        updateProgress = Runnable {
            if (!holding || root == null) return@Runnable
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            progress.progress = ((elapsed.toFloat() / holdMs) * PROGRESS_MAX).toInt().coerceIn(0, PROGRESS_MAX)
            val secondsLeft = ceil((holdMs - elapsed).coerceAtLeast(0L) / 1_000.0).toInt()
            label.text = localizedContext.getString(R.string.guarded_hold_progress, secondsLeft)
            if (elapsed >= holdMs) {
                holding = false
                dismiss(localizedContext)
                onComplete()
            } else {
                handler.postDelayed(updateProgress, PROGRESS_FRAME_MS)
            }
        }
        holdButton.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startedAt = SystemClock.elapsedRealtime()
                    holding = true
                    handler.post(updateProgress)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (holding) resetHold()
                    true
                }
                else -> true
            }
        }
        card.addView(holdButton)
        show(localizedContext, card)

        lateinit var refreshElapsed: Runnable
        refreshElapsed = Runnable {
            if (root == null) return@Runnable
            val elapsedWhileShown = SystemClock.elapsedRealtime() - shownAt
            title.text = formatElapsedTitle(localizedContext, consumedMs + elapsedWhileShown)
            handler.postDelayed(refreshElapsed, ELAPSED_REFRESH_MS)
        }
        handler.postDelayed(refreshElapsed, ELAPSED_REFRESH_MS)
    }

    private fun formatElapsedTitle(context: Context, consumedMs: Long): String {
        val safeConsumedMs = consumedMs.coerceAtLeast(0L)
        if (safeConsumedMs < 1_000L) return context.getString(R.string.guarded_hold_title_just_started)
        if (safeConsumedMs < 60_000L) {
            return context.getString(R.string.guarded_hold_title_seconds, safeConsumedMs / 1_000L)
        }
        return context.getString(R.string.guarded_hold_title_minutes, safeConsumedMs / 60_000L)
    }

    private fun show(context: Context, card: View) {
        dismiss(context)
        val container = FrameLayout(context).apply {
            setBackgroundColor(Color.parseColor("#99000000"))
            isClickable = true
            addView(card)
        }
        val manager = context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        if (runCatching { manager.addView(container, params) }.isFailure) {
            Logger.e(TAG, "Failed to show guarded intervention overlay")
            return
        }
        windowManager = manager
        root = container
    }

    private fun buildCard(context: Context) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(24.dp(context), 24.dp(context), 24.dp(context), 18.dp(context))
        background = roundedDrawable(surfaceColor(context), 20.dp(context).toFloat()).apply {
            setStroke(1.dp(context), borderColor(context))
        }
        elevation = 10.dp(context).toFloat()
        isClickable = true
        layoutParams = FrameLayout.LayoutParams(
            (context.resources.displayMetrics.widthPixels * 0.86f).toInt(),
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER }
    }

    private fun buildTitle(context: Context, value: String) = TextView(context).apply {
        text = value
        textSize = 22f
        setTextColor(primaryTextColor(context))
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 10.dp(context) }
    }

    private fun buildBody(context: Context, value: String) = TextView(context).apply {
        text = value
        textSize = 15f
        setTextColor(secondaryTextColor(context))
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(-1, -2)
    }

    private fun buildSecondaryAction(context: Context, value: String, action: () -> Unit) = TextView(context).apply {
        text = value
        textSize = 15f
        setTextColor(primaryTextColor(context))
        gravity = Gravity.CENTER
        setPadding(18.dp(context), 13.dp(context), 18.dp(context), 13.dp(context))
        background = roundedDrawable(secondaryButtonColor(context), 14.dp(context).toFloat())
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = 10.dp(context) }
    }

    private fun buildPrimaryAction(context: Context, value: String, action: () -> Unit) = TextView(context).apply {
        text = value
        textSize = 16f
        setTextColor(Color.WHITE)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(18.dp(context), 15.dp(context), 18.dp(context), 15.dp(context))
        background = roundedDrawable(primaryButtonColor(context), 14.dp(context).toFloat())
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = 22.dp(context) }
    }

    private fun buildProgressDrawable(context: Context): LayerDrawable {
        val track = roundedDrawable(Color.parseColor("#4DFFFFFF"), 2.dp(context).toFloat())
        val fill = ClipDrawable(
            roundedDrawable(Color.WHITE, 2.dp(context).toFloat()),
            Gravity.START,
            ClipDrawable.HORIZONTAL
        )
        return LayerDrawable(arrayOf(track, fill)).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.progress)
        }
    }

    private fun roundedDrawable(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    private fun isDark(context: Context) =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private fun surfaceColor(context: Context) = if (isDark(context)) Color.parseColor("#262626") else Color.WHITE
    private fun borderColor(context: Context) = if (isDark(context)) Color.parseColor("#3A3A3A") else Color.parseColor("#E5E7EB")
    private fun primaryTextColor(context: Context) = if (isDark(context)) Color.parseColor("#F3F4F6") else Color.parseColor("#111827")
    private fun secondaryTextColor(context: Context) = if (isDark(context)) Color.parseColor("#D1D5DB") else Color.parseColor("#4B5563")
    private fun primaryButtonColor(context: Context) = if (isDark(context)) Color.parseColor("#3B82F6") else Color.parseColor("#2563EB")
    private fun secondaryButtonColor(context: Context) = if (isDark(context)) Color.parseColor("#3F3F46") else Color.parseColor("#F3F4F6")
    private fun Int.dp(context: Context) = (this * context.resources.displayMetrics.density).toInt()
}
