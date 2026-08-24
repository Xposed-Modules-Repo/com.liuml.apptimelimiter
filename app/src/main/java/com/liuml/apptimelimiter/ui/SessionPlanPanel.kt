package com.liuml.apptimelimiter.ui

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.ScrollView
import android.widget.TextView
import com.liuml.apptimelimiter.core.SessionPlanDurationPolicy

data class SessionPlanPanelCopy(
    val eyebrow: String,
    val title: String,
    val description: String,
    val skipLabel: String,
)

/** Shared compact minute-slider plan UI used by Hook dialogs and accessibility overlays. */
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
    private val customActionHost = LinearLayout(context).apply { orientation = VERTICAL }
    private val footer = LinearLayout(context).apply { orientation = HORIZONTAL }

    init {
        orientation = VERTICAL
        setPadding(ui.dp(22), ui.dp(18), ui.dp(22), ui.dp(16))
        background = ui.roundedBackground(colors.surface, 24f, 1, colors.outline)
        addView(eyebrowView, ui.matchWrap())
        addView(titleView, ui.matchWrap())
        addView(descriptionView, ui.matchWrap())
        addView(bodyScroll, bodyWrapLayoutParams)
        addView(customActionHost, ui.matchWrap(topMargin = 6))
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
        customActionHost.removeAllViews()
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
            ui.sectionLabel(ui.text("自定义时长", "Custom duration")),
            ui.matchWrap(topMargin = 2, bottomMargin = 6),
        )

        val maximumAvailableMinutes =
            SessionPlanDurationPolicy.maxSelectableMinutes(maxAllowedMillis)
        var selectedCustomMinutes =
            SessionPlanDurationPolicy.defaultSliderMinutes(maxAllowedMillis)
        val customValue = ui.customDurationValue(selectedCustomMinutes)
        bodyHost.addView(customValue, ui.matchWrap(bottomMargin = 2))

        val slider = ui.minuteSlider(
            selectedMinutes = selectedCustomMinutes,
        )
        val customStart = ui.action(
            ui.text("开始计划", "Start plan"),
            filled = true,
            enabled = SessionPlanDurationPolicy.minutesAllowed(
                selectedCustomMinutes,
                maxAllowedMillis,
            ),
        ) {
            if (
                SessionPlanDurationPolicy.minutesAllowed(
                    selectedCustomMinutes,
                    maxAllowedMillis,
                )
            ) {
                select(selectedCustomMinutes * 60_000L)
            }
        }
        val helper = ui.helper("")
        fun updateCustomSelection() {
            val allowed = SessionPlanDurationPolicy.minutesAllowed(
                selectedCustomMinutes,
                maxAllowedMillis,
            )
            customValue.text = ui.customDurationText(selectedCustomMinutes)
            customValue.setTextColor(if (allowed) colors.primary else ui.warningColor())
            helper.text = ui.sliderHelper(
                maximumAvailableMinutes = maximumAvailableMinutes,
                allowed = allowed,
            )
            helper.setTextColor(if (allowed) colors.textSecondary else ui.warningColor())
            ui.setActionEnabled(customStart, allowed)
            slider.contentDescription = if (allowed) {
                ui.customDurationText(selectedCustomMinutes)
            } else {
                helper.text
            }
        }
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                selectedCustomMinutes = SessionPlanDurationPolicy.normalizeSliderMinutes(progress)
                updateCustomSelection()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        bodyHost.addView(slider, ui.matchWrap())
        val endpoints = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                ui.sliderEndpoint("${SessionPlanDurationPolicy.MIN_TOTAL_MINUTES}"),
                ui.weightedWrap(),
            )
            addView(
                ui.sliderEndpoint("${SessionPlanDurationPolicy.MAX_TOTAL_MINUTES}", Gravity.END),
                ui.weightedWrap(),
            )
        }
        bodyHost.addView(endpoints, ui.matchWrap(bottomMargin = 2))
        bodyHost.addView(helper, ui.matchWrap(topMargin = 3, bottomMargin = 7))
        customActionHost.addView(customStart, ui.matchHeight(48))
        updateCustomSelection()
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

        fun customDurationValue(selectedMinutes: Int) =
            TextView(context).apply {
                text = customDurationText(selectedMinutes)
                gravity = Gravity.CENTER
                setTextColor(colors.primary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                typeface = Typeface.DEFAULT_BOLD
                minHeight = dp(32)
            }

        fun customDurationText(minutes: Int): String =
            text("计划使用 $minutes 分钟", "Plan for $minutes min")

        fun minuteSlider(selectedMinutes: Int) = SeekBar(context).apply {
            min = SessionPlanDurationPolicy.MIN_TOTAL_MINUTES
            max = SessionPlanDurationPolicy.MAX_TOTAL_MINUTES
            progress = SessionPlanDurationPolicy.normalizeSliderMinutes(selectedMinutes)
            splitTrack = false
            progressTintList = ColorStateList.valueOf(colors.primary)
            progressBackgroundTintList = ColorStateList.valueOf(colors.outline)
            thumbTintList = ColorStateList.valueOf(colors.primary)
            contentDescription = customDurationText(progress)
        }

        fun sliderEndpoint(value: String, textGravity: Int = Gravity.START) = TextView(context).apply {
            text = value
            gravity = textGravity
            setTextColor(colors.textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
        }

        fun sliderHelper(
            maximumAvailableMinutes: Int,
            allowed: Boolean,
        ): String = when {
            !allowed && maximumAvailableMinutes < SessionPlanDurationPolicy.MIN_TOTAL_MINUTES -> {
                text(
                    "剩余时间不足，当前可用额度不足1分钟",
                    "Not enough time remains; less than 1 minute is available",
                )
            }
            !allowed -> {
                text(
                    "剩余时间不足，当前最多可计划 $maximumAvailableMinutes 分钟",
                    "Not enough time remains; up to $maximumAvailableMinutes min is available",
                )
            }
            maximumAvailableMinutes < SessionPlanDurationPolicy.MAX_TOTAL_MINUTES -> {
                text(
                    "当前最多可计划 $maximumAvailableMinutes 分钟",
                    "Up to $maximumAvailableMinutes min available",
                )
            }
            else -> {
                text("仅计算前台使用时间", "Foreground time only")
            }
        }

        fun warningColor(): Int = if (colors.isDark) 0xFFFFB4AB.toInt() else 0xFFB3261E.toInt()

        fun setActionEnabled(view: TextView, enabled: Boolean) {
            view.isEnabled = enabled
            view.alpha = if (enabled) 1f else 0.38f
            view.isClickable = enabled
            view.isFocusable = enabled
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

        fun weightedWrap() = LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f,
        )

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
