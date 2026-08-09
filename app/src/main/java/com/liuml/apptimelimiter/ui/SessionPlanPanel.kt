package com.liuml.apptimelimiter.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.liuml.apptimelimiter.core.SessionPlanDurationPolicy
import com.liuml.apptimelimiter.core.SessionPlanDurationStatus

data class SessionPlanPanelCopy(
    val eyebrow: String,
    val title: String,
    val description: String,
    val skipLabel: String,
)

/** Shared compact minute-input plan UI used by Hook dialogs and accessibility overlays. */
@SuppressLint("ViewConstructor")
class SessionPlanPanel(
    context: Context,
    val colors: TargetUiColors,
    private val english: Boolean,
    private val copy: SessionPlanPanelCopy,
    private val quote: String?,
    private val includeDebugChoice: Boolean,
    private val maxAllowedMillis: Long?,
    private val onStart: (Long) -> Unit,
    private val onSkip: () -> Unit,
    private val onExit: () -> Unit,
) : LinearLayout(context) {
    private val ui = PanelUi(context, english, colors)
    private val eyebrowView = ui.eyebrow(copy.eyebrow)
    private val titleView = ui.title(copy.title)
    private val descriptionView = ui.body(copy.description)
    private val bodyHost = LinearLayout(context).apply { orientation = VERTICAL }
    private val bodyScroll = ui.bodyScroll(bodyHost)
    private val bodyWrapLayoutParams = LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )
    private val bodyWeightedLayoutParams = LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        0,
        1f,
    )
    private val footer = LinearLayout(context).apply { orientation = HORIZONTAL }

    init {
        orientation = VERTICAL
        setPadding(ui.dp(22), ui.dp(18), ui.dp(22), ui.dp(16))
        background = ui.roundedBackground(colors.surface, 24f, 1, colors.outline)
        addView(eyebrowView, ui.matchWrap())
        addView(titleView, ui.matchWrap())
        addView(descriptionView, ui.matchWrap())
        addView(bodyScroll, bodyWrapLayoutParams)
        addView(ui.divider(), ui.matchHeight(1, topMargin = 12, bottomMargin = 10))
        addView(footer, ui.matchWrap())
        showContent()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (bodyScroll.layoutParams !== bodyWrapLayoutParams) {
            bodyScroll.layoutParams = bodyWrapLayoutParams
        }
        super.onMeasure(
            widthMeasureSpec,
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        val screenMaximum = (resources.displayMetrics.heightPixels * MAX_HEIGHT_RATIO).toInt()
        val parentMaximum = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.UNSPECIFIED -> screenMaximum
            else -> minOf(MeasureSpec.getSize(heightMeasureSpec), screenMaximum)
        }
        if (measuredHeight <= parentMaximum) return
        bodyScroll.layoutParams = bodyWeightedLayoutParams
        super.onMeasure(
            widthMeasureSpec,
            MeasureSpec.makeMeasureSpec(parentMaximum, MeasureSpec.EXACTLY),
        )
    }

    /** Returns true when Back was consumed; the outer non-cancelable container remains open. */
    fun handleBack(): Boolean = true

    private fun select(durationMillis: Long) {
        if (SessionPlanDurationPolicy.durationAllowed(durationMillis, maxAllowedMillis)) {
            onStart(durationMillis)
        }
    }

    private fun configureFooter() {
        footer.removeAllViews()
        footer.addView(
            ui.action(ui.text("退出应用", "Exit app"), filled = false, onClick = onExit),
            ui.weightedHeight(48, 0, 0),
        )
        footer.addView(
            ui.action(copy.skipLabel, filled = false, onClick = onSkip),
            ui.weightedHeight(48, 10, 0),
        )
    }

    private fun showContent() {
        eyebrowView.text = copy.eyebrow
        titleView.text = copy.title
        descriptionView.text = copy.description
        bodyHost.removeAllViews()
        quote?.takeIf(String::isNotBlank)?.let {
            bodyHost.addView(ui.quote(it), ui.matchWrap(bottomMargin = 10))
        }

        val quickGrid = LinearLayout(context).apply { orientation = VERTICAL }
        listOf(5, 10, 15, 30).chunked(2).forEach { rowMinutes ->
            val row = LinearLayout(context).apply { orientation = HORIZONTAL }
            rowMinutes.forEachIndexed { index, minutes ->
                val durationMillis = minutes * 60_000L
                row.addView(
                    ui.action(
                        ui.text("$minutes 分钟", "$minutes min"),
                        filled = false,
                        enabled = SessionPlanDurationPolicy.durationAllowed(
                            durationMillis,
                            maxAllowedMillis,
                        ),
                    ) { select(durationMillis) },
                    ui.weightedHeight(48, if (index == 0) 0 else 10, 10),
                )
            }
            quickGrid.addView(row, ui.matchWrap())
        }
        bodyHost.addView(quickGrid, ui.matchWrap())
        bodyHost.addView(
            ui.sectionLabel(ui.text("自定义分钟", "Custom minutes")),
            ui.matchWrap(topMargin = 2, bottomMargin = 6),
        )

        val customRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val input = ui.minuteInput()
        val customStart = ui.action(
            ui.text("开始", "Start"),
            filled = true,
            enabled = false,
        ) {
            SessionPlanDurationPolicy.evaluate(input.text.toString(), maxAllowedMillis)
                .takeIf { it.status == SessionPlanDurationStatus.VALID }
                ?.totalMinutes
                ?.let { select(it * 60_000L) }
        }
        customRow.addView(input, ui.weightedHeight(50, 0, 0))
        customRow.addView(customStart, ui.fixedHeight(96, 50, 10))
        bodyHost.addView(customRow, ui.matchWrap())
        val customHelper = ui.helper(ui.minuteInputMessage("", maxAllowedMillis))
        bodyHost.addView(customHelper, ui.matchWrap(topMargin = 5, bottomMargin = 4))

        fun refreshCustomInput() {
            val evaluation = SessionPlanDurationPolicy.evaluate(
                input.text.toString(),
                maxAllowedMillis,
            )
            val valid = evaluation.status == SessionPlanDurationStatus.VALID
            customStart.isEnabled = valid
            customStart.isClickable = valid
            customStart.isFocusable = valid
            customStart.alpha = if (valid) 1f else 0.38f
            customHelper.text = ui.minuteInputMessage(input.text.toString(), maxAllowedMillis)
            customHelper.setTextColor(
                if (
                    evaluation.status == SessionPlanDurationStatus.ZERO ||
                    evaluation.status == SessionPlanDurationStatus.NON_NUMERIC ||
                    evaluation.status == SessionPlanDurationStatus.OUT_OF_RANGE ||
                    evaluation.status == SessionPlanDurationStatus.EXCEEDS_MAX
                ) {
                    colors.primary
                } else {
                    colors.textSecondary
                },
            )
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) =
                Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) =
                refreshCustomInput()
            override fun afterTextChanged(s: Editable?) = Unit
        })
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId != EditorInfo.IME_ACTION_DONE) return@setOnEditorActionListener false
            if (customStart.isEnabled) customStart.performClick()
            true
        }
        if (includeDebugChoice) {
            bodyHost.addView(
                ui.compactAction(
                    ui.text("调试 · 10秒", "DEBUG · 10 sec"),
                    enabled = SessionPlanDurationPolicy.durationAllowed(
                        10_000L,
                        maxAllowedMillis,
                    ),
                ) { select(10_000L) },
                ui.centeredHeight(34, topMargin = 4),
            )
        }
        refreshCustomInput()
        configureFooter()
        bodyScroll.scrollTo(0, 0)
    }

    private class PanelUi(
        private val context: Context,
        private val english: Boolean,
        val colors: TargetUiColors,
    ) {
        private val density = context.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density + 0.5f).toInt()
        fun text(chinese: String, englishText: String): String = if (english) englishText else chinese

        fun bodyScroll(content: LinearLayout) = BoundedScrollView(
            context = context,
            maximumHeight = if (
                context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            ) {
                (context.resources.displayMetrics.heightPixels * 0.42f)
                    .toInt()
                    .coerceAtMost(dp(210))
            } else {
                (context.resources.displayMetrics.heightPixels * 0.45f)
                    .toInt()
                    .coerceAtMost(dp(300))
            },
        ).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            addView(
                content,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        fun eyebrow(value: String) = TextView(context).apply {
            text = value
            setTextColor(colors.primary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            letterSpacing = 0.08f
            minHeight = dp(28)
            gravity = Gravity.CENTER_VERTICAL
        }

        fun title(value: String) = TextView(context).apply {
            text = value
            setTextColor(colors.textPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.DEFAULT_BOLD
            setLineSpacing(0f, 1.05f)
        }

        fun body(value: String) = TextView(context).apply {
            text = value
            setTextColor(colors.textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(0, dp(6), 0, dp(12))
        }

        fun helper(value: String) = TextView(context).apply {
            text = value
            gravity = Gravity.CENTER
            setTextColor(colors.textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }

        fun sectionLabel(value: String) = TextView(context).apply {
            text = value
            setTextColor(colors.primary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.DEFAULT_BOLD
        }

        fun quote(value: String) = TextView(context).apply {
            text = "“$value”"
            setTextColor(colors.textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setLineSpacing(dp(2).toFloat(), 1f)
            maxLines = 2
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = roundedBackground(colors.surfaceContainer, 14f)
        }

        fun minuteInput() = EditText(context).apply {
            hint = text("例如 45", "e.g. 45")
            setTextColor(colors.textPrimary)
            setHintTextColor(colors.textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER_VERTICAL
            inputType = InputType.TYPE_CLASS_NUMBER
            imeOptions = EditorInfo.IME_ACTION_DONE
            filters = arrayOf(InputFilter.LengthFilter(4))
            isSingleLine = true
            setPadding(dp(14), 0, dp(14), 0)
            background = roundedBackground(colors.surfaceContainer, 16f, 1, colors.outline)
            contentDescription = text("自定义计划分钟数", "Custom plan minutes")
        }

        fun minuteInputMessage(raw: String, maxAllowedMillis: Long?): String {
            val evaluation = SessionPlanDurationPolicy.evaluate(raw, maxAllowedMillis)
            return when (evaluation.status) {
                SessionPlanDurationStatus.EMPTY ->
                    maxAllowedMillis?.let(::allowanceText)
                        ?: text("请输入1–1440分钟", "Enter 1–1440 minutes")
                SessionPlanDurationStatus.NON_NUMERIC ->
                    text("请输入整数分钟", "Enter whole minutes")
                SessionPlanDurationStatus.ZERO ->
                    text("请至少输入1分钟", "Enter at least 1 minute")
                SessionPlanDurationStatus.OUT_OF_RANGE ->
                    text("最多可输入1440分钟", "Maximum: 1440 minutes")
                SessionPlanDurationStatus.EXCEEDS_MAX ->
                    maxAllowedMillis?.let {
                        val minutes = SessionPlanDurationPolicy.maxSelectableMinutes(it)
                        text(
                            "超过当前剩余${minutes}分钟",
                            "Exceeds the remaining $minutes min",
                        )
                    } ?: text("当前输入不可用", "This value is unavailable")
                SessionPlanDurationStatus.VALID ->
                    maxAllowedMillis?.let(::allowanceText)
                        ?: text("仅计算前台使用时间", "Foreground time only")
            }
        }

        private fun allowanceText(maxAllowedMillis: Long): String {
            val minutes = SessionPlanDurationPolicy.maxSelectableMinutes(maxAllowedMillis)
            return if (minutes <= 0L) {
                text("当前可用额度不足1分钟", "Less than 1 minute remains")
            } else {
                text("本次最多可计划${minutes}分钟", "Up to $minutes min available")
            }
        }

        fun action(
            label: String,
            filled: Boolean,
            enabled: Boolean = true,
            onClick: () -> Unit,
        ) = TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(if (filled) colors.onPrimary else colors.onPrimaryContainer)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            setAutoSizeTextTypeUniformWithConfiguration(11, 14, 1, TypedValue.COMPLEX_UNIT_SP)
            setPadding(dp(12), dp(4), dp(12), dp(4))
            background = if (filled) {
                roundedBackground(colors.primary, 16f)
            } else {
                roundedBackground(colors.primaryContainer, 16f, 1, colors.outline)
            }
            isEnabled = enabled
            alpha = if (enabled) 1f else 0.38f
            isClickable = enabled
            isFocusable = enabled
            setOnClickListener { if (isEnabled) onClick() }
        }

        fun compactAction(label: String, enabled: Boolean, onClick: () -> Unit) =
            action(label, filled = false, enabled = enabled, onClick = onClick).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setPadding(dp(12), dp(2), dp(12), dp(2))
            }

        fun divider() = TextView(context).apply { setBackgroundColor(colors.outline) }

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

        fun matchWrap(
            topMargin: Int = 0,
            bottomMargin: Int = 0,
        ) = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            this.topMargin = dp(topMargin)
            this.bottomMargin = dp(bottomMargin)
        }

        fun matchHeight(
            height: Int,
            topMargin: Int = 0,
            bottomMargin: Int = 0,
        ) = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(height),
        ).apply {
            this.topMargin = dp(topMargin)
            this.bottomMargin = dp(bottomMargin)
        }

        fun weightedHeight(height: Int, startMargin: Int, bottomMargin: Int) =
            LinearLayout.LayoutParams(0, dp(height), 1f).apply {
                marginStart = dp(startMargin)
                this.bottomMargin = dp(bottomMargin)
            }

        fun fixedHeight(width: Int, height: Int, startMargin: Int) =
            LinearLayout.LayoutParams(dp(width), dp(height)).apply {
                marginStart = dp(startMargin)
            }

        fun centeredHeight(height: Int, topMargin: Int = 0) = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            dp(height),
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            this.topMargin = dp(topMargin)
        }
    }

    private class BoundedScrollView(
        context: Context,
        private val maximumHeight: Int,
    ) : ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val requested = MeasureSpec.getSize(heightMeasureSpec)
            val capped = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
                maximumHeight
            } else {
                minOf(requested, maximumHeight)
            }
            super.onMeasure(
                widthMeasureSpec,
                MeasureSpec.makeMeasureSpec(capped, MeasureSpec.AT_MOST),
            )
        }
    }

    private companion object {
        const val MAX_HEIGHT_RATIO = 0.88f
    }
}
