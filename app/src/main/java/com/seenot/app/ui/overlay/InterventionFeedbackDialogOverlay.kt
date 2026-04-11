package com.seenot.app.ui.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.seenot.app.utils.Logger

/**
 * Lightweight intervention confirmation dialog shown before forced actions.
 */
class InterventionFeedbackDialogOverlay(
    private val context: Context,
    private val appName: String,
    private val constraintDescription: String,
    private val titleText: String,
    private val subtitleText: String,
    private val primaryButtonText: String,
    private val secondaryButtonText: String?,
    private val onFalsePositive: () -> Unit,
    private val onPrimaryAction: () -> Unit,
    private val onSecondaryAction: (() -> Unit)? = null
) {
    companion object {
        private const val TAG = "InterventionDialog"
        private const val EXIT_ACTION_DELAY_MS = 180L

        @Volatile
        private var currentDialog: InterventionFeedbackDialogOverlay? = null

        fun show(
            context: Context,
            appName: String,
            constraintDescription: String,
            titleText: String = "先停一下？",
            subtitleText: String = "看起来你正在偏离刚才的目标",
            primaryButtonText: String = "退出",
            secondaryButtonText: String? = null,
            onFalsePositive: () -> Unit,
            onPrimaryAction: () -> Unit,
            onSecondaryAction: (() -> Unit)? = null
        ) {
            dismiss()
            val dialog = InterventionFeedbackDialogOverlay(
                context = context,
                appName = appName,
                constraintDescription = constraintDescription,
                titleText = titleText,
                subtitleText = subtitleText,
                primaryButtonText = primaryButtonText,
                secondaryButtonText = secondaryButtonText,
                onFalsePositive = onFalsePositive,
                onPrimaryAction = onPrimaryAction,
                onSecondaryAction = onSecondaryAction
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
    private val falsePositiveColor get() = if (isDarkMode) Color.parseColor("#4B5563") else Color.parseColor("#F3F4F6")
    private val exitColor get() = Color.parseColor("#EF4444")

    @SuppressLint("SetTextI18n")
    fun show() {
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val screenWidth = context.resources.displayMetrics.widthPixels
        val dialogWidth = (screenWidth * 0.86f).toInt()

        val dimBg = FrameLayout(context).apply {
            setBackgroundColor(Color.parseColor("#80000000"))
            // Consume outside taps; keep the decision explicit.
            setOnClickListener { }
        }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp(), 24.dp(), 24.dp(), 20.dp())
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

        val appLabel = TextView(context).apply {
            text = appName
            textSize = 14f
            setTextColor(secondaryTextColor)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 8.dp()
            }
        }
        card.addView(appLabel)

        val title = TextView(context).apply {
            text = titleText
            textSize = 22f
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
        card.addView(title)

        val subtitle = TextView(context).apply {
            text = subtitleText
            textSize = 15f
            setTextColor(primaryTextColor)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 10.dp()
            }
        }
        card.addView(subtitle)

        val detail = TextView(context).apply {
            text = constraintDescription
            textSize = 13f
            setTextColor(secondaryTextColor)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 20.dp()
            }
        }
        card.addView(detail)

        val falsePositiveButton = buildButton(
            text = "误报",
            backgroundColor = falsePositiveColor,
            textColor = primaryTextColor
        ) {
            dismiss()
            onFalsePositive()
        }
        card.addView(falsePositiveButton)

        if (!secondaryButtonText.isNullOrBlank()) {
            val secondaryButton = buildButton(
                text = secondaryButtonText,
                backgroundColor = falsePositiveColor,
                textColor = primaryTextColor
            ) {
                dismiss()
                onSecondaryAction?.invoke()
            }.apply {
                (layoutParams as LinearLayout.LayoutParams).topMargin = 10.dp()
            }
            card.addView(secondaryButton)
        }

        val primaryButton = buildButton(
            text = primaryButtonText,
            backgroundColor = exitColor,
            textColor = Color.WHITE
        ) {
            dismiss()
            Handler(Looper.getMainLooper()).postDelayed({
                onPrimaryAction()
            }, EXIT_ACTION_DELAY_MS)
        }.apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = 10.dp()
        }
        card.addView(primaryButton)

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
            Logger.e(TAG, "Failed to show intervention dialog overlay", e)
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
            textSize = 16f
            setTextColor(textColor)
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(backgroundColor)
                cornerRadius = 14.dp().toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                48.dp()
            )
            setOnClickListener { onClick() }
        }
    }

    private fun dismissInternal() {
        val view = rootView ?: return
        try {
            windowManager?.removeView(view)
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to dismiss intervention dialog overlay", e)
        } finally {
            rootView = null
        }
    }

    private fun dismiss() {
        dismissInternal()
        currentDialog = null
    }

    private fun Int.dp(): Int = (this * context.resources.displayMetrics.density).toInt()
}
