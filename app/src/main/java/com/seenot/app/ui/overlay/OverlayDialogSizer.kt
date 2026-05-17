package com.seenot.app.ui.overlay

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ScrollView
import kotlin.math.roundToInt

internal object OverlayDialogSizer {
    private const val LANDSCAPE_WIDTH_FRACTION = 0.64f
    private const val LANDSCAPE_LARGE_FONT_WIDTH_FRACTION = 0.72f
    private const val LANDSCAPE_MAX_WIDTH_DP = 560

    fun apply(
        context: Context,
        container: View?,
        card: FrameLayout,
        contentScroll: ScrollView,
        portraitWidthFraction: Float,
        largeFontPortraitWidthFraction: Float
    ) {
        val metrics = context.resources.displayMetrics
        val availableWidth = container?.width?.takeIf { it > 0 } ?: metrics.widthPixels
        val availableHeight = container?.height?.takeIf { it > 0 } ?: metrics.heightPixels
        val isLandscape = availableWidth > availableHeight
        val isLargeFont = context.resources.configuration.fontScale >= 1.2f
        val widthFraction = when {
            isLandscape && isLargeFont -> LANDSCAPE_LARGE_FONT_WIDTH_FRACTION
            isLandscape -> LANDSCAPE_WIDTH_FRACTION
            isLargeFont -> largeFontPortraitWidthFraction
            else -> portraitWidthFraction
        }
        val calculatedWidth = (availableWidth * widthFraction).toInt()
        val dialogWidth = if (isLandscape) {
            minOf(calculatedWidth, LANDSCAPE_MAX_WIDTH_DP.dp(context))
        } else {
            calculatedWidth
        }
        val dialogMaxHeight = (availableHeight * 0.9f).roundToInt()

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

    fun registerLayoutChangeCallback(
        context: Context,
        container: View,
        card: FrameLayout,
        contentScroll: ScrollView,
        portraitWidthFraction: Float,
        largeFontPortraitWidthFraction: Float
    ): View.OnLayoutChangeListener {
        val listener = View.OnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val width = right - left
            val height = bottom - top
            val oldWidth = oldRight - oldLeft
            val oldHeight = oldBottom - oldTop
            if (width != oldWidth || height != oldHeight) {
                apply(
                    context,
                    container,
                    card,
                    contentScroll,
                    portraitWidthFraction,
                    largeFontPortraitWidthFraction
                )
            }
        }
        container.addOnLayoutChangeListener(listener)
        return listener
    }

    fun unregisterLayoutChangeCallback(container: View?, listener: View.OnLayoutChangeListener?) {
        if (container == null || listener == null) return
        container.removeOnLayoutChangeListener(listener)
    }

    private fun Int.dp(context: Context): Int {
        return (this * context.resources.displayMetrics.density).roundToInt()
    }
}
