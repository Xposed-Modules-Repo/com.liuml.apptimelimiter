package com.liuml.apptimelimiter.xposed

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

internal enum class SessionPlanDialogMode {
    INITIAL,
    REPLAN,
}

/** A target-process dialog that does not depend on module resources, themes, or overlay permission. */
internal class SessionPlanDialog private constructor(
    private val dialog: Dialog,
) {
    val isShowing: Boolean get() = dialog.isShowing

    fun dismiss() {
        runCatching { dialog.dismiss() }
    }

    companion object {
        private const val CARD_COLOR = 0xFF090909.toInt()
        private const val SURFACE_COLOR = 0xFF191919.toInt()
        private const val BORDER_COLOR = 0x3DFFFFFF

        fun show(
            activity: Activity,
            mode: SessionPlanDialogMode,
            english: Boolean,
            includeDebugChoice: Boolean,
            onStart: (Long) -> Unit,
            onWithoutPlan: () -> Unit,
        ): SessionPlanDialog? {
            if (activity.isFinishing || activity.isDestroyed) return null
            val ui = DialogUi(activity, english)
            val content = ui.card()
            content.addView(ui.eyebrow(ui.text("时停 · 本次使用", "TIME STOP · THIS SESSION")))
            content.addView(ui.title(ui.text(
                if (mode == SessionPlanDialogMode.INITIAL) "制定本次使用计划" else "重新制定本次计划",
                if (mode == SessionPlanDialogMode.INITIAL) "Plan this session" else "Replan this session",
            )))
            content.addView(ui.body(
                ui.text(
                    "只计算前台使用时间。切到后台或息屏后暂停，回来继续。",
                    "Only foreground time counts. The plan pauses in the background or with the screen off.",
                ),
                bottomPadding = 18,
            ))

            var holder: SessionPlanDialog? = null
            fun select(durationMillis: Long) {
                holder?.dismiss()
                onStart(durationMillis)
            }

            val quickGrid = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            listOf(5, 10, 15, 30).chunked(2).forEach { rowMinutes ->
                val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
                rowMinutes.forEachIndexed { index, minutes ->
                    row.addView(
                        ui.action(
                            label = ui.text("$minutes 分钟", "$minutes min"),
                            filled = false,
                        ) { select(minutes * 60_000L) },
                        ui.weightedHeight(48, startMargin = if (index == 0) 0 else 10, bottomMargin = 10),
                    )
                }
                quickGrid.addView(row, ui.matchWrap())
            }
            content.addView(quickGrid, ui.matchWrap())

            if (includeDebugChoice) {
                content.addView(
                    ui.action(ui.text("10 秒测试", "10-second test"), filled = false) {
                        select(10_000L)
                    },
                    ui.matchHeight(46, bottomMargin = 18),
                )
            } else {
                content.addView(ui.divider(), ui.matchHeight(1, bottomMargin = 18))
            }

            content.addView(ui.sectionLabel(ui.text("自定义分钟", "CUSTOM MINUTES")), ui.matchWrap(bottomMargin = 8))
            val customInput = EditText(activity).apply {
                hint = ui.text("输入 1–1440", "Enter 1–1440")
                inputType = InputType.TYPE_CLASS_NUMBER
                imeOptions = EditorInfo.IME_ACTION_GO
                setSingleLine(true)
                setTextColor(Color.WHITE)
                setHintTextColor(0x73FFFFFF)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                gravity = Gravity.CENTER_VERTICAL
                background = ui.roundedBackground(SURFACE_COLOR, 14f, 1, BORDER_COLOR)
                setPadding(ui.dp(16), 0, ui.dp(16), 0)
            }
            content.addView(customInput, ui.matchHeight(52, bottomMargin = 10))

            fun submitCustom() {
                val rawValue = customInput.text?.toString()?.trim().orEmpty()
                val minutes = rawValue.toLongOrNull()
                if (minutes == null || minutes !in 1L..1440L) {
                    customInput.error = ui.text("请输入 1–1440 分钟", "Enter 1–1440 minutes")
                    customInput.requestFocus()
                    return
                }
                customInput.error = null
                select(minutes * 60_000L)
            }
            customInput.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                    submitCustom()
                    true
                } else {
                    false
                }
            }
            content.addView(
                ui.action(ui.text("开始自定义计划", "Start custom plan"), filled = true) {
                    submitCustom()
                },
                ui.matchHeight(52, bottomMargin = 8),
            )
            content.addView(
                ui.textAction(
                    ui.text(
                        if (mode == SessionPlanDialogMode.INITIAL) "本次不计划" else "取消本次计划",
                        if (mode == SessionPlanDialogMode.INITIAL) "No plan this time" else "Cancel this plan",
                    ),
                ) {
                    holder?.dismiss()
                    onWithoutPlan()
                },
                ui.matchHeight(48),
            )

            val scroll = ui.scroll(content)
            customInput.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    scroll.postDelayed(
                        { scroll.fullScroll(android.view.View.FOCUS_DOWN) },
                        180L,
                    )
                }
            }
            val dialog = createDialog(activity, scroll)
            holder = SessionPlanDialog(dialog)
            return showDialog(activity, dialog, holder, ui)
        }

        private fun createDialog(activity: Activity, content: ScrollView): Dialog =
            Dialog(activity).apply {
                requestWindowFeature(Window.FEATURE_NO_TITLE)
                setContentView(content)
                setCancelable(false)
                setCanceledOnTouchOutside(false)
            }

        @Suppress("DEPRECATION")
        private fun showDialog(
            activity: Activity,
            dialog: Dialog,
            holder: SessionPlanDialog?,
            ui: DialogUi,
        ): SessionPlanDialog? = runCatching {
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            dialog.show()
            dialog.window?.apply {
                setBackgroundDrawableResource(android.R.color.transparent)
                val displayWidth = activity.resources.displayMetrics.widthPixels
                setLayout((displayWidth - ui.dp(28)).coerceAtMost(ui.dp(430)), ViewGroup.LayoutParams.WRAP_CONTENT)
                setDimAmount(0.42f)
                setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }
            holder
        }.getOrNull()

        private class DialogUi(
            private val activity: Activity,
            private val english: Boolean,
        ) {
            private val density = activity.resources.displayMetrics.density
            fun dp(value: Int): Int = (value * density + 0.5f).toInt()
            fun text(chinese: String, englishText: String): String = if (english) englishText else chinese

            fun card() = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(22), dp(20), dp(22), dp(18))
                background = roundedBackground(CARD_COLOR, 22f, 1, 0x24FFFFFF)
            }

            fun scroll(content: LinearLayout) = BoundedScrollView(
                context = activity,
                maximumHeight = (activity.resources.displayMetrics.heightPixels * 0.86f).toInt(),
            ).apply {
                isFillViewport = false
                isVerticalScrollBarEnabled = false
                clipToPadding = false
                addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }

            fun eyebrow(value: String) = TextView(activity).apply {
                text = value
                setTextColor(0x8FFFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                letterSpacing = 0.08f
                setPadding(0, 0, 0, dp(8))
            }

            fun title(value: String) = TextView(activity).apply {
                text = value
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                setLineSpacing(0f, 1.05f)
            }

            fun body(value: String, bottomPadding: Int) = TextView(activity).apply {
                text = value
                setTextColor(0xC7FFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                setLineSpacing(dp(2).toFloat(), 1f)
                setPadding(0, dp(7), 0, dp(bottomPadding))
            }

            fun sectionLabel(value: String) = TextView(activity).apply {
                text = value
                setTextColor(0x8FFFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                letterSpacing = 0.06f
            }

            fun action(
                label: String,
                filled: Boolean,
                onClick: () -> Unit,
            ) = TextView(activity).apply {
                text = label
                gravity = Gravity.CENTER
                setTextColor(if (filled) Color.BLACK else Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                maxLines = 1
                setAutoSizeTextTypeUniformWithConfiguration(
                    11,
                    14,
                    1,
                    TypedValue.COMPLEX_UNIT_SP,
                )
                setPadding(dp(12), dp(4), dp(12), dp(4))
                background = if (filled) {
                    roundedBackground(Color.WHITE, 14f)
                } else {
                    roundedBackground(SURFACE_COLOR, 14f, 1, BORDER_COLOR)
                }
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
            }

            fun textAction(label: String, onClick: () -> Unit) = TextView(activity).apply {
                text = label
                gravity = Gravity.CENTER
                setTextColor(0xA8FFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
            }

            fun divider() = TextView(activity).apply { setBackgroundColor(0x24FFFFFF) }

            fun roundedBackground(
                color: Int,
                radiusDp: Float,
                strokeWidthDp: Int = 0,
                strokeColor: Int = Color.TRANSPARENT,
            ) = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(color)
                cornerRadius = radiusDp * density
                if (strokeWidthDp > 0) setStroke(dp(strokeWidthDp), strokeColor)
            }

            fun matchWrap(bottomMargin: Int = 0) = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { this.bottomMargin = dp(bottomMargin) }

            fun matchHeight(height: Int, bottomMargin: Int = 0) = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(height),
            ).apply { this.bottomMargin = dp(bottomMargin) }

            fun weightedHeight(height: Int, startMargin: Int, bottomMargin: Int) =
                LinearLayout.LayoutParams(0, dp(height), 1f).apply {
                    marginStart = dp(startMargin)
                    this.bottomMargin = dp(bottomMargin)
                }
        }

        private class BoundedScrollView(
            context: Context,
            private val maximumHeight: Int,
        ) : ScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val requestedSize = MeasureSpec.getSize(heightMeasureSpec)
                val cappedSize = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
                    maximumHeight
                } else {
                    minOf(requestedSize, maximumHeight)
                }
                super.onMeasure(
                    widthMeasureSpec,
                    MeasureSpec.makeMeasureSpec(cappedSize, MeasureSpec.AT_MOST),
                )
            }
        }
    }
}
