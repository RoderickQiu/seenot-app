package com.seenot.app.ui.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.seenot.app.R
import com.seenot.app.config.AppLocalePrefs
import com.seenot.app.data.model.ConstraintType
import com.seenot.app.utils.Logger
import kotlin.math.roundToInt

class JudgmentFeedbackConfirmOverlay(
    private val context: Context,
    private val constraintType: ConstraintType,
    private val onConfirm: () -> Unit
) {
    companion object {
        private const val TAG = "JudgmentFeedbackDialog"

        @Volatile
        private var currentDialog: JudgmentFeedbackConfirmOverlay? = null

        fun show(
            context: Context,
            constraintType: ConstraintType,
            onConfirm: () -> Unit
        ) {
            dismiss()
            val localizedContext = AppLocalePrefs.createLocalizedContext(context)
            val dialog = JudgmentFeedbackConfirmOverlay(
                context = localizedContext,
                constraintType = constraintType,
                onConfirm = onConfirm
            )
            dialog.show()
            currentDialog = dialog.takeIf { it.rootView != null }
        }

        fun dismiss() {
            currentDialog?.dismissInternal()
            currentDialog = null
        }

        fun isShowing(): Boolean = currentDialog?.rootView != null
    }

    private var windowManager: WindowManager? = null
    private var rootView: View? = null

    private val isDarkMode: Boolean
        get() = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private val surfaceColor get() = if (isDarkMode) Color.parseColor("#262626") else Color.WHITE
    private val borderColor get() = if (isDarkMode) Color.parseColor("#3A3A3A") else Color.parseColor("#E5E7EB")
    private val primaryTextColor get() = if (isDarkMode) Color.parseColor("#F3F4F6") else Color.parseColor("#111827")
    private val secondaryTextColor get() = if (isDarkMode) Color.parseColor("#9CA3AF") else Color.parseColor("#6B7280")
    private val neutralButtonColor get() = if (isDarkMode) Color.parseColor("#3F3F46") else Color.parseColor("#F3F4F6")
    private val confirmColor get() = if (isDarkMode) Color.parseColor("#D97706") else Color.parseColor("#F59E0B")

    @SuppressLint("SetTextI18n")
    fun show() {
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val screenWidth = context.resources.displayMetrics.widthPixels
        val screenHeight = context.resources.displayMetrics.heightPixels
        val dialogWidth = (screenWidth * dialogWidthFraction()).toInt()
        val dialogMaxHeight = (screenHeight * 0.9f).roundToInt()

        val dimBg = FrameLayout(context).apply {
            setBackgroundColor(Color.parseColor("#80000000"))
            setOnClickListener { }
        }

        val card = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                dialogWidth,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
            }
            background = GradientDrawable().apply {
                setColor(surfaceColor)
                cornerRadius = 20.dp().toFloat()
                setStroke(1, borderColor)
            }
            elevation = 10.dp().toFloat()
            setOnClickListener { }
        }

        val contentScroll = ScrollView(context).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp(), 24.dp(), 24.dp(), 20.dp())
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }

        content.addView(
            TextView(context).apply {
                text = context.getString(R.string.judgment_wrong_title)
                textSize = 20f
                setTextColor(primaryTextColor)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = 10.dp()
                }
            }
        )

        content.addView(
            TextView(context).apply {
                text = when (constraintType) {
                    ConstraintType.DENY -> context.getString(R.string.judgment_wrong_content_desc)
                    ConstraintType.TIME_CAP -> context.getString(R.string.judgment_wrong_content_timing)
                    ConstraintType.NO_MONITOR -> context.getString(R.string.hud_judgment_no_monitor)
                }
                textSize = 14f
                setTextColor(secondaryTextColor)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = 20.dp()
                }
            }
        )

        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        buttonRow.addView(
            buildButton(
                text = context.getString(R.string.cancel),
                backgroundColor = neutralButtonColor,
                textColor = primaryTextColor
            ) {
                dismiss()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(0, 48.dp(), 1f).apply {
                    marginEnd = 8.dp()
                }
            }
        )

        buttonRow.addView(
            buildButton(
                text = context.getString(R.string.confirm),
                backgroundColor = confirmColor,
                textColor = Color.WHITE
            ) {
                dismiss()
                onConfirm()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(0, 48.dp(), 1f).apply {
                    marginStart = 8.dp()
                }
            }
        )

        content.addView(buttonRow)
        contentScroll.addView(content)
        card.addView(contentScroll)
        contentScroll.post {
            val measuredHeight = contentScroll.getChildAt(0)?.measuredHeight ?: 0
            if (measuredHeight > dialogMaxHeight) {
                contentScroll.layoutParams = (contentScroll.layoutParams as FrameLayout.LayoutParams).apply {
                    height = dialogMaxHeight
                }
            }
        }
        dimBg.addView(card)
        rootView = dimBg

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        try {
            windowManager?.addView(rootView, params)
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to show judgment feedback dialog", e)
            dismiss()
        }
    }

    private fun buildButton(
        text: String,
        backgroundColor: Int,
        textColor: Int,
        onClick: () -> Unit
    ): Button {
        return Button(context).apply {
            this.text = text
            textSize = 15f
            setTextColor(textColor)
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(backgroundColor)
                cornerRadius = 14.dp().toFloat()
            }
            setOnClickListener { onClick() }
        }
    }

    private fun dismissInternal() {
        val view = rootView ?: return
        try {
            windowManager?.removeView(view)
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to dismiss judgment feedback dialog", e)
        } finally {
            rootView = null
        }
    }

    private fun dismiss() {
        dismissInternal()
        currentDialog = null
    }

    private fun dialogWidthFraction(): Float {
        val configuration = context.resources.configuration
        val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val isLargeFont = configuration.fontScale >= 1.2f
        return when {
            isLandscape && isLargeFont -> 0.96f
            isLandscape -> 0.92f
            isLargeFont -> 0.9f
            else -> 0.86f
        }
    }

    private fun Int.dp(): Int = (this * context.resources.displayMetrics.density).toInt()
}
