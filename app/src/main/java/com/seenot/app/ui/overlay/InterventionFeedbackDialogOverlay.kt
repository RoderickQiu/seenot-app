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
import android.widget.ScrollView
import android.widget.TextView
import com.seenot.app.R
import com.seenot.app.config.AppLocalePrefs
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
            titleText: String? = null,
            subtitleText: String? = null,
            primaryButtonText: String? = null,
            secondaryButtonText: String? = null,
            onFalsePositive: () -> Unit,
            onPrimaryAction: () -> Unit,
            onSecondaryAction: (() -> Unit)? = null
        ) {
            dismiss()
            val localizedContext = AppLocalePrefs.createLocalizedContext(context)
            val resolvedTitle = titleText ?: localizedContext.getString(R.string.intervention_dialog_title)
            val resolvedSubtitle = subtitleText ?: localizedContext.getString(R.string.intervention_dialog_subtitle)
            val resolvedPrimary = primaryButtonText ?: localizedContext.getString(R.string.intervention_dialog_exit)
            val dialog = InterventionFeedbackDialogOverlay(
                context = localizedContext,
                appName = appName,
                constraintDescription = constraintDescription,
                titleText = resolvedTitle,
                subtitleText = resolvedSubtitle,
                primaryButtonText = resolvedPrimary,
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
    private var layoutChangeListener: View.OnLayoutChangeListener? = null

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

        val dimBg = FrameLayout(context).apply {
            setBackgroundColor(Color.parseColor("#80000000"))
            // Consume outside taps; keep the decision explicit.
            setOnClickListener { }
        }

        val card = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
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
        content.addView(appLabel)

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
        content.addView(title)

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
        content.addView(subtitle)

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
        content.addView(detail)

        val falsePositiveButton = buildButton(
            text = context.getString(R.string.judgment_false_positive),
            backgroundColor = falsePositiveColor,
            textColor = primaryTextColor
        ) {
            dismiss()
            onFalsePositive()
        }
        content.addView(falsePositiveButton)

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
            content.addView(secondaryButton)
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
        content.addView(primaryButton)

        contentScroll.addView(content)
        card.addView(contentScroll)
        OverlayDialogSizer.apply(context, dimBg, card, contentScroll, 0.86f, 0.9f)
        layoutChangeListener = OverlayDialogSizer.registerLayoutChangeCallback(
            context,
            dimBg,
            card,
            contentScroll,
            portraitWidthFraction = 0.86f,
            largeFontPortraitWidthFraction = 0.9f
        )

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
            OverlayDialogSizer.unregisterLayoutChangeCallback(rootView, layoutChangeListener)
            windowManager?.removeView(view)
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to dismiss intervention dialog overlay", e)
        } finally {
            layoutChangeListener = null
            rootView = null
        }
    }

    private fun dismiss() {
        dismissInternal()
        currentDialog = null
    }

    private fun Int.dp(): Int = (this * context.resources.displayMetrics.density).toInt()
}
