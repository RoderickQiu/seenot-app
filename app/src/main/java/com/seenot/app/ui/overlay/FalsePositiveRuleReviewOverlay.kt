package com.seenot.app.ui.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.seenot.app.data.model.AppHintScopeType
import com.seenot.app.R
import com.seenot.app.config.AppLocalePrefs
import com.seenot.app.domain.FalsePositiveFeedbackResult
import com.seenot.app.domain.FalsePositiveRulePreviewResult
import com.seenot.app.utils.Logger

class FalsePositiveRuleReviewOverlay(
    private val context: Context,
    private val titleText: String,
    private val subtitleText: String,
    private val onGenerate: ((FalsePositiveRulePreviewResult) -> Unit) -> Unit,
    private val onSave: (String, AppHintScopeType, (FalsePositiveFeedbackResult) -> Unit) -> Unit
) {
    companion object {
        private const val TAG = "FalsePositiveRuleOverlay"

        @Volatile
        private var currentDialog: FalsePositiveRuleReviewOverlay? = null

        fun show(
            context: Context,
            titleText: String,
            subtitleText: String,
            onGenerate: ((FalsePositiveRulePreviewResult) -> Unit) -> Unit,
            onSave: (String, AppHintScopeType, (FalsePositiveFeedbackResult) -> Unit) -> Unit
        ) {
            dismiss()
            val localizedContext = AppLocalePrefs.createLocalizedContext(context)
            val dialog = FalsePositiveRuleReviewOverlay(
                context = localizedContext,
                titleText = titleText,
                subtitleText = subtitleText,
                onGenerate = onGenerate,
                onSave = onSave
            )
            dialog.show()
            currentDialog = dialog.takeIf { it.rootView != null }
        }

        fun dismiss() {
            currentDialog?.dismissInternal()
            currentDialog = null
        }
    }

    private var windowManager: WindowManager? = null
    private var rootView: View? = null
    private var draftInput: EditText? = null
    private var statusText: TextView? = null
    private var progressBar: ProgressBar? = null
    private var regenerateButton: Button? = null
    private var saveButton: Button? = null
    private var latestScopeType: AppHintScopeType = AppHintScopeType.INTENT_SPECIFIC
    private var isGenerating = false
    private var isSaving = false

    private val isDarkMode: Boolean
        get() = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private val surfaceColor get() = if (isDarkMode) Color.parseColor("#262626") else Color.WHITE
    private val borderColor get() = if (isDarkMode) Color.parseColor("#3A3A3A") else Color.parseColor("#E5E7EB")
    private val primaryTextColor get() = if (isDarkMode) Color.parseColor("#F3F4F6") else Color.parseColor("#111827")
    private val secondaryTextColor get() = if (isDarkMode) Color.parseColor("#9CA3AF") else Color.parseColor("#6B7280")
    private val neutralButtonColor get() = if (isDarkMode) Color.parseColor("#3F3F46") else Color.parseColor("#F3F4F6")
    private val accentColor get() = if (isDarkMode) Color.parseColor("#D97706") else Color.parseColor("#F59E0B")
    private val accentSoftColor get() = if (isDarkMode) Color.parseColor("#4B5563") else Color.parseColor("#FFF7ED")

    @SuppressLint("SetTextI18n")
    fun show() {
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val screenWidth = context.resources.displayMetrics.widthPixels
        val dialogWidth = (screenWidth * 0.9f).toInt()

        val dimBg = FrameLayout(context).apply {
            setBackgroundColor(Color.parseColor("#80000000"))
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

        card.addView(
            TextView(context).apply {
                text = titleText
                textSize = 20f
                setTextColor(primaryTextColor)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = 8.dp()
                }
            }
        )

        card.addView(
            TextView(context).apply {
                text = subtitleText
                textSize = 14f
                setTextColor(secondaryTextColor)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = 16.dp()
                }
            }
        )

        statusText = TextView(context).apply {
            text = context.getString(R.string.fp_generating_draft)
            textSize = 13f
            setTextColor(secondaryTextColor)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 12.dp()
            }
        }
        card.addView(statusText)

        progressBar = ProgressBar(context).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(
                24.dp(),
                24.dp()
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = 16.dp()
            }
        }
        card.addView(progressBar)

        draftInput = EditText(context).apply {
            hint = context.getString(R.string.fp_hint_after_generation)
            setPadding(16.dp(), 14.dp(), 16.dp(), 14.dp())
            background = GradientDrawable().apply {
                cornerRadius = 14.dp().toFloat()
                setColor(accentSoftColor)
                setStroke(1, borderColor)
            }
            textSize = 14f
            setTextColor(primaryTextColor)
            setHintTextColor(secondaryTextColor)
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            gravity = Gravity.TOP or Gravity.START
            minLines = 4
            maxLines = 6
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 16.dp()
            }
        }
        card.addView(draftInput)

        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val cancelButton = buildButton(
            text = context.getString(R.string.fp_cancel),
            backgroundColor = neutralButtonColor,
            textColor = primaryTextColor
        ) {
            dismiss()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, 48.dp(), 1f).apply {
                marginEnd = 6.dp()
            }
        }
        buttonRow.addView(cancelButton)

        regenerateButton = buildButton(
            text = context.getString(R.string.fp_regenerate),
            backgroundColor = neutralButtonColor,
            textColor = primaryTextColor
        ) {
            triggerGeneration()
        }.apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(0, 48.dp(), 1f).apply {
                marginStart = 6.dp()
                marginEnd = 6.dp()
            }
        }
        buttonRow.addView(regenerateButton)

        saveButton = buildButton(
            text = context.getString(R.string.fp_save),
            backgroundColor = accentColor,
            textColor = Color.WHITE
        ) {
            val draft = draftInput?.text?.toString()?.trim().orEmpty()
            if (draft.isBlank() || isSaving) return@buildButton
            isSaving = true
            updateButtons()
            statusText?.text = context.getString(R.string.fp_saving)
            onSave(draft, latestScopeType) { result ->
                isSaving = false
                if (result.success) {
                    ToastOverlay.show(context, result.userMessage)
                    dismiss()
                } else {
                    statusText?.text = result.userMessage
                    updateButtons()
                }
            }
        }.apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(0, 48.dp(), 1f).apply {
                marginStart = 6.dp()
            }
        }
        buttonRow.addView(saveButton)

        card.addView(buttonRow)
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
            triggerGeneration()
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to show false-positive rule review overlay", e)
            dismiss()
        }
    }

    private fun triggerGeneration() {
        if (isGenerating || isSaving) return
        isGenerating = true
        statusText?.text = context.getString(R.string.fp_generating_draft)
        progressBar?.visibility = View.VISIBLE
        draftInput?.visibility = View.GONE
        regenerateButton?.visibility = View.GONE
        saveButton?.visibility = View.GONE
        updateButtons()
        onGenerate { result ->
            isGenerating = false
            progressBar?.visibility = View.GONE
            draftInput?.visibility = View.VISIBLE
            regenerateButton?.visibility = View.VISIBLE
            saveButton?.visibility = View.VISIBLE
            latestScopeType = result.generatedScopeType
            draftInput?.setText(result.generatedRule.orEmpty())
            statusText?.text = context.getString(R.string.false_positive_status_format, result.userMessage, result.generatedScopeLabel)
            updateButtons()
        }
    }

    private fun updateButtons() {
        regenerateButton?.isEnabled = !isGenerating && !isSaving
        saveButton?.isEnabled = !isGenerating && !isSaving && !draftInput?.text.isNullOrBlank()
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
            setSingleLine()
            ellipsize = android.text.TextUtils.TruncateAt.END
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
            Logger.w(TAG, "Failed to dismiss false-positive rule review overlay", e)
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
