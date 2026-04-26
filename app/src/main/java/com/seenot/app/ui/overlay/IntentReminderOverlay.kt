package com.seenot.app.ui.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.seenot.app.R
import com.seenot.app.config.AppLocalePrefs
import com.seenot.app.utils.Logger

class IntentReminderOverlay(
    private val context: Context,
    private val appName: String,
    private val onSetIntentNow: () -> Unit,
    private val onLater: () -> Unit
) {
    companion object {
        private const val TAG = "IntentReminderOverlay"

        @Volatile
        private var currentDialog: IntentReminderOverlay? = null

        fun show(
            context: Context,
            appName: String,
            onSetIntentNow: () -> Unit,
            onLater: () -> Unit
        ) {
            dismiss()
            val localizedContext = AppLocalePrefs.createLocalizedContext(context)
            val dialog = IntentReminderOverlay(
                context = localizedContext,
                appName = appName,
                onSetIntentNow = onSetIntentNow,
                onLater = onLater
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
    private val primaryButtonColor get() = if (isDarkMode) Color.parseColor("#60A5FA") else Color.parseColor("#2563EB")
    private val secondaryButtonColor get() = if (isDarkMode) Color.parseColor("#3F3F46") else Color.parseColor("#F3F4F6")

    @SuppressLint("SetTextI18n")
    fun show() {
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val screenWidth = context.resources.displayMetrics.widthPixels
        val dialogWidth = (screenWidth * 0.86f).toInt()

        val dimBg = FrameLayout(context).apply {
            setBackgroundColor(Color.parseColor("#80000000"))
            setOnClickListener {
                dismiss()
                onLater()
            }
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
            ).apply { bottomMargin = 8.dp() }
        }
        card.addView(appLabel)

        val title = TextView(context).apply {
            text = context.getString(R.string.intent_reminder_title)
            textSize = 22f
            setTextColor(primaryTextColor)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 10.dp() }
        }
        card.addView(title)

        val subtitle = TextView(context).apply {
            text = context.getString(R.string.intent_reminder_subtitle)
            textSize = 15f
            setTextColor(primaryTextColor)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 20.dp() }
        }
        card.addView(subtitle)

        val setNowButton = buildButton(
            text = context.getString(R.string.intent_reminder_set_now),
            backgroundColor = primaryButtonColor,
            textColor = Color.WHITE
        ) {
            dismiss()
            onSetIntentNow()
        }
        card.addView(setNowButton)

        val laterButton = buildButton(
            text = context.getString(R.string.intent_reminder_later),
            backgroundColor = secondaryButtonColor,
            textColor = primaryTextColor
        ) {
            dismiss()
            onLater()
        }.apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = 10.dp()
        }
        card.addView(laterButton)

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
            Logger.e(TAG, "Failed to show reminder overlay", e)
            dismissInternal()
        }
    }

    private fun buildButton(
        text: String,
        backgroundColor: Int,
        textColor: Int,
        onClick: () -> Unit
    ): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = 15f
            setTextColor(textColor)
            typeface = Typeface.DEFAULT_BOLD
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setPadding(18.dp(), 14.dp(), 18.dp(), 14.dp())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            background = GradientDrawable().apply {
                setColor(backgroundColor)
                cornerRadius = 14.dp().toFloat()
            }
            setOnClickListener { onClick() }
        }
    }

    private fun dismiss() {
        dismissInternal()
        currentDialog = null
    }

    private fun dismissInternal() {
        rootView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (_: Exception) {
            }
        }
        rootView = null
    }

    private fun Int.dp(): Int = (this * context.resources.displayMetrics.density).toInt()
}
