package com.seenot.app.ui.overlay

import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ScrollView
import kotlin.math.roundToInt

internal object OverlayDialogSizer {
    fun apply(
        context: Context,
        card: FrameLayout,
        contentScroll: ScrollView,
        widthFraction: Float
    ) {
        val metrics = context.resources.displayMetrics
        val dialogWidth = (metrics.widthPixels * widthFraction).toInt()
        val dialogMaxHeight = (metrics.heightPixels * 0.9f).roundToInt()

        card.layoutParams = (card.layoutParams as? FrameLayout.LayoutParams)?.apply {
            width = dialogWidth
            height = FrameLayout.LayoutParams.WRAP_CONTENT
            gravity = Gravity.CENTER
        } ?: FrameLayout.LayoutParams(dialogWidth, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        }

        contentScroll.layoutParams = (contentScroll.layoutParams as? FrameLayout.LayoutParams)?.apply {
            width = FrameLayout.LayoutParams.MATCH_PARENT
            height = FrameLayout.LayoutParams.WRAP_CONTENT
        } ?: FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )

        card.requestLayout()
        contentScroll.requestLayout()

        contentScroll.post {
            val measuredHeight = contentScroll.getChildAt(0)?.measuredHeight ?: 0
            val targetHeight = if (measuredHeight > dialogMaxHeight) {
                dialogMaxHeight
            } else {
                FrameLayout.LayoutParams.WRAP_CONTENT
            }
            contentScroll.layoutParams = (contentScroll.layoutParams as FrameLayout.LayoutParams).apply {
                height = targetHeight
            }
            contentScroll.requestLayout()
        }
    }

    fun registerConfigurationCallback(
        context: Context,
        card: FrameLayout,
        contentScroll: ScrollView,
        widthFraction: () -> Float
    ): ComponentCallbacks {
        val callback = object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) {
                apply(context, card, contentScroll, widthFraction())
            }

            @Deprecated("Deprecated in ComponentCallbacks")
            override fun onLowMemory() = Unit
        }
        context.registerComponentCallbacks(callback)
        return callback
    }

    fun unregisterConfigurationCallback(context: Context, callback: ComponentCallbacks?) {
        if (callback == null) return
        context.unregisterComponentCallbacks(callback)
    }
}
