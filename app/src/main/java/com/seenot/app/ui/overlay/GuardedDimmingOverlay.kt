package com.seenot.app.ui.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.View
import android.view.WindowManager

/** Persistent, non-interactive dim layer shown over the controlled app during Guarded use. */
object GuardedDimmingOverlay {
    private var root: View? = null
    private var windowManager: WindowManager? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var currentFraction = 0f

    fun update(context: Context, dimFraction: Float) {
        val fraction = dimFraction.coerceIn(0f, 0.4f)
        if (fraction <= 0f) {
            dismiss()
            return
        }

        root ?: View(context.applicationContext).also { newRoot ->
            newRoot.setBackgroundColor(Color.BLACK)
            val manager = context.applicationContext
                .getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply { alpha = fraction }
            if (runCatching { manager.addView(newRoot, params) }.isFailure) return
            windowManager = manager
            layoutParams = params
            root = newRoot
        }

        if (fraction != currentFraction) {
            layoutParams?.let { params ->
                params.alpha = fraction
                root?.let { view -> runCatching { windowManager?.updateViewLayout(view, params) } }
            }
            currentFraction = fraction
        }
    }

    fun dismiss() {
        root?.let { view -> runCatching { windowManager?.removeView(view) } }
        root = null
        windowManager = null
        layoutParams = null
        currentFraction = 0f
    }
}
