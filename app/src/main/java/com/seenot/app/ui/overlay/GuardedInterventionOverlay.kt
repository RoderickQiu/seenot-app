package com.seenot.app.ui.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

/** Buttonless breath and effort-priced hold overlays for Guarded mode. */
object GuardedInterventionOverlay {
    private var root: View? = null
    private var wm: WindowManager? = null
    private val handler = Handler(Looper.getMainLooper())

    fun dismiss(context: Context) {
        root?.let { runCatching { wm?.removeView(it) } }
        root = null; wm = null
    }

    fun showBreath(context: Context, durationMs: Long = 4_000L) {
        show(context, null, null)
        handler.postDelayed({ dismiss(context) }, durationMs)
    }

    fun showHold(context: Context, holdMs: Long, onComplete: () -> Unit) {
        val label = TextView(context).apply {
            text = "Press and hold to continue"
            textSize = 20f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(37, 99, 235))
        }
        var downAt = 0L
        label.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downAt = System.currentTimeMillis(); true }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (event.actionMasked == MotionEvent.ACTION_UP && System.currentTimeMillis() - downAt >= holdMs) {
                        dismiss(context); onComplete()
                    }
                    true
                }
                else -> true
            }
        }
        show(context, label, null)
    }

    private fun show(context: Context, content: View?, ignored: (() -> Unit)?) {
        dismiss(context)
        val container = FrameLayout(context).apply {
            setBackgroundColor(0xB3000000.toInt())
        }
        content?.let { container.addView(it, FrameLayout.LayoutParams(-1, 72).apply { gravity = Gravity.CENTER; leftMargin = 48; rightMargin = 48 }) }
        wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        root = container
        runCatching { wm?.addView(container, WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT)) }
    }
}
