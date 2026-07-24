package com.liuml.apptimelimiter.xposed

import android.app.Activity
import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import kotlin.math.max

internal enum class WarningBannerKind {
    TIME_LIMIT,
    SCHEDULE,
    SESSION_PLAN,
}

internal class TopWarningBanner private constructor(
    val kind: WarningBannerKind,
    private val root: FrameLayout,
    private val titleView: TextView,
    private val messageView: TextView,
    private val progressView: ProgressBar,
    private val maxProgressMillis: Long,
) {
    val isAttached: Boolean
        get() = root.isAttachedToWindow

    fun update(title: String, message: String, remainingMillis: Long) {
        titleView.text = title
        messageView.text = message
        progressView.progress = remainingMillis.coerceIn(0L, maxProgressMillis).toInt()
        root.contentDescription = "$title，$message"
    }

    fun remove() {
        (root.parent as? ViewGroup)?.removeView(root)
    }

    companion object {
        fun attach(
            activity: Activity,
            kind: WarningBannerKind,
            title: String,
            message: String,
            remainingMillis: Long,
            maxProgressMillis: Long,
            fullScreen: Boolean = false,
            actionLabel: String? = null,
            onAction: (() -> Unit)? = null,
        ): TopWarningBanner {
            val decor = activity.window.decorView as ViewGroup
            val density = activity.resources.displayMetrics.density
            fun dp(value: Int): Int = (value * density + 0.5f).toInt()

            val root = FrameLayout(activity).apply {
                setPadding(
                    dp(if (fullScreen) FULL_SCREEN_PADDING_DP else 16),
                    dp(if (fullScreen) FULL_SCREEN_PADDING_DP else 12),
                    dp(if (fullScreen) FULL_SCREEN_PADDING_DP else 12),
                    dp(if (fullScreen) FULL_SCREEN_PADDING_DP else 8),
                )
                background = roundedBackground(
                    color = BANNER_BACKGROUND,
                    radius = dp(if (fullScreen) 0 else 20).toFloat(),
                    strokeWidth = if (fullScreen) 0 else dp(1),
                    strokeColor = if (fullScreen) Color.TRANSPARENT else BANNER_BORDER,
                )
                elevation = if (fullScreen) 0f else dp(12).toFloat()
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                isClickable = fullScreen
                isFocusable = fullScreen
            }

            val contentColumn = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = if (fullScreen) Gravity.CENTER_HORIZONTAL else Gravity.NO_GRAVITY
                isClickable = false
                isFocusable = false
            }
            val contentRow = LinearLayout(activity).apply {
                orientation = if (fullScreen) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
                gravity = if (fullScreen) Gravity.CENTER_HORIZONTAL else Gravity.CENTER_VERTICAL
                isClickable = false
                isFocusable = false
            }
            val textColumn = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = if (fullScreen) Gravity.CENTER_HORIZONTAL else Gravity.NO_GRAVITY
                isClickable = false
                isFocusable = false
            }
            val titleView = TextView(activity).apply {
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (fullScreen) 28f else 15f)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                maxLines = if (fullScreen) 2 else 1
                gravity = if (fullScreen) Gravity.CENTER else Gravity.NO_GRAVITY
            }
            val messageView = TextView(activity).apply {
                setTextColor(BANNER_SECONDARY_TEXT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (fullScreen) 16f else 12.5f)
                maxLines = if (fullScreen) 3 else 2
                gravity = if (fullScreen) Gravity.CENTER else Gravity.NO_GRAVITY
                setPadding(0, dp(if (fullScreen) 8 else 2), 0, 0)
            }
            textColumn.addView(
                titleView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            textColumn.addView(
                messageView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            contentRow.addView(
                textColumn,
                if (fullScreen) {
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                } else {
                    LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f,
                    )
                },
            )

            if (!fullScreen && actionLabel != null && onAction != null) {
                contentRow.addView(
                    actionTextView(activity, actionLabel, prominent = true, dp = ::dp).apply {
                        setOnClickListener { onAction() }
                    },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { marginStart = dp(12) },
                )
            }

            val progressView = ProgressBar(
                activity,
                null,
                android.R.attr.progressBarStyleHorizontal,
            ).apply {
                isIndeterminate = false
                max = maxProgressMillis.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
                progressTintList = ColorStateList.valueOf(Color.WHITE)
                progressBackgroundTintList = ColorStateList.valueOf(PROGRESS_BACKGROUND)
                isClickable = false
                isFocusable = false
            }
            contentColumn.addView(
                contentRow,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            contentColumn.addView(
                progressView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(if (fullScreen) 5 else 3),
                ).apply { topMargin = dp(if (fullScreen) 24 else 8) },
            )
            root.addView(
                contentColumn,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    if (fullScreen) Gravity.CENTER else Gravity.TOP,
                ),
            )

            if (fullScreen && actionLabel != null && onAction != null) {
                val actionHitTarget = FrameLayout(activity).apply {
                    isClickable = true
                    isFocusable = true
                    contentDescription = actionLabel
                    setOnClickListener { onAction() }
                }
                actionHitTarget.addView(
                    actionTextView(activity, actionLabel, prominent = false, dp = ::dp),
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        dp(FULL_SCREEN_ACTION_VISUAL_HEIGHT_DP),
                        Gravity.CENTER,
                    ),
                )
                root.addView(
                    actionHitTarget,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        dp(FULL_SCREEN_ACTION_TOUCH_DP),
                        Gravity.TOP or Gravity.END,
                    ),
                )
            }

            val horizontalMargin = dp(
                if (activity.resources.configuration.orientation ==
                    Configuration.ORIENTATION_LANDSCAPE
                ) {
                    LANDSCAPE_MARGIN_DP
                } else {
                    PORTRAIT_MARGIN_DP
                },
            )
            val isLandscape =
                activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val windowWidth = windowWidth(activity)
            val maxLandscapeWidth = dp(MAX_LANDSCAPE_WIDTH_DP)
            val layoutParams = if (fullScreen) {
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            } else {
                FrameLayout.LayoutParams(
                    bannerWidth(isLandscape, windowWidth, horizontalMargin, maxLandscapeWidth),
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.CENTER_HORIZONTAL,
                ).apply {
                    topMargin = dp(TOP_MARGIN_DP)
                    leftMargin = horizontalMargin
                    rightMargin = horizontalMargin
                }
            }
            root.setOnApplyWindowInsetsListener { view, insets ->
                if (fullScreen) {
                    applyFullScreenInsets(view, insets, dp(FULL_SCREEN_PADDING_DP))
                } else {
                    applySafeInsets(
                        view,
                        insets,
                        dp(TOP_MARGIN_DP),
                        horizontalMargin,
                        isLandscape,
                        windowWidth,
                        maxLandscapeWidth,
                    )
                }
                insets
            }
            decor.addView(root, layoutParams)
            root.requestApplyInsets()
            return TopWarningBanner(
                kind,
                root,
                titleView,
                messageView,
                progressView,
                maxProgressMillis,
            ).also { it.update(title, message, remainingMillis) }
        }

        private fun actionTextView(
            activity: Activity,
            label: String,
            prominent: Boolean,
            dp: (Int) -> Int,
        ) = TextView(activity).apply {
            text = label
            setTextColor(if (prominent) Color.BLACK else Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (prominent) 13f else 12f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            minWidth = dp(if (prominent) 0 else 56)
            setPadding(dp(if (prominent) 13 else 10), 0, dp(if (prominent) 13 else 10), 0)
            background = roundedBackground(
                color = if (prominent) Color.WHITE else FULL_SCREEN_ACTION_BACKGROUND,
                radius = dp(if (prominent) 14 else 18).toFloat(),
                strokeWidth = if (prominent) 0 else dp(1),
                strokeColor = if (prominent) Color.TRANSPARENT else FULL_SCREEN_ACTION_BORDER,
            )
            isClickable = false
            isFocusable = false
        }

        private fun roundedBackground(
            color: Int,
            radius: Float,
            strokeWidth: Int = 0,
            strokeColor: Int = Color.TRANSPARENT,
        ) = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = radius
            if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
        }

        private fun bannerWidth(
            isLandscape: Boolean,
            windowWidth: Int,
            margin: Int,
            maxLandscapeWidth: Int,
        ): Int = if (!isLandscape) {
            ViewGroup.LayoutParams.MATCH_PARENT
        } else {
            (windowWidth - margin * 2).coerceAtMost(maxLandscapeWidth).coerceAtLeast(1)
        }

        private fun windowWidth(activity: Activity): Int =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                activity.windowManager.currentWindowMetrics.bounds.width()
            } else {
                @Suppress("DEPRECATION")
                activity.resources.displayMetrics.widthPixels
            }

        @Suppress("DEPRECATION")
        private fun applyFullScreenInsets(view: View, insets: WindowInsets, basePadding: Int) {
            val systemInsets = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
                ).let { SafeInsets(it.left, it.top, it.right, it.bottom) }
            } else {
                SafeInsets(
                    insets.systemWindowInsetLeft,
                    insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,
                    insets.systemWindowInsetBottom,
                )
            }
            view.setPadding(
                basePadding + systemInsets.left,
                basePadding + systemInsets.top,
                basePadding + systemInsets.right,
                basePadding + systemInsets.bottom,
            )
        }

        @Suppress("DEPRECATION")
        private fun applySafeInsets(
            view: View,
            insets: WindowInsets,
            baseTopMargin: Int,
            baseHorizontalMargin: Int,
            isLandscape: Boolean,
            windowWidth: Int,
            maxLandscapeWidth: Int,
        ) {
            val systemInsets = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
                ).let { SafeInsets(it.left, it.top, it.right) }
            } else {
                SafeInsets(
                    insets.systemWindowInsetLeft,
                    insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,
                )
            }
            val cutoutInsets = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                displayCutoutInsets(insets)
            } else {
                SafeInsets.NONE
            }
            val safeLeft = max(systemInsets.left, cutoutInsets.left)
            val safeTop = max(systemInsets.top, cutoutInsets.top)
            val safeRight = max(systemInsets.right, cutoutInsets.right)
            val params = view.layoutParams as? FrameLayout.LayoutParams ?: return
            val newLeft = baseHorizontalMargin + safeLeft
            val newRight = baseHorizontalMargin + safeRight
            val newWidth = if (isLandscape) {
                (windowWidth - newLeft - newRight)
                    .coerceAtMost(maxLandscapeWidth)
                    .coerceAtLeast(1)
            } else {
                ViewGroup.LayoutParams.MATCH_PARENT
            }
            params.width = newWidth
            params.leftMargin = newLeft
            params.topMargin = baseTopMargin + safeTop
            params.rightMargin = newRight
            view.layoutParams = params
        }

        @SuppressLint("NewApi")
        private fun displayCutoutInsets(insets: WindowInsets): SafeInsets {
            val cutout = insets.displayCutout ?: return SafeInsets.NONE
            return SafeInsets(cutout.safeInsetLeft, cutout.safeInsetTop, cutout.safeInsetRight)
        }

        private data class SafeInsets(
            val left: Int,
            val top: Int,
            val right: Int,
            val bottom: Int = 0,
        ) {
            companion object {
                val NONE = SafeInsets(0, 0, 0)
            }
        }

        private const val PORTRAIT_MARGIN_DP = 12
        private const val LANDSCAPE_MARGIN_DP = 16
        private const val TOP_MARGIN_DP = 10
        private const val MAX_LANDSCAPE_WIDTH_DP = 520
        private const val FULL_SCREEN_PADDING_DP = 20
        private const val FULL_SCREEN_ACTION_VISUAL_HEIGHT_DP = 36
        private const val FULL_SCREEN_ACTION_TOUCH_DP = 48
        private const val BANNER_BACKGROUND = 0xF20A0A0A.toInt()
        private const val BANNER_BORDER = 0x3DFFFFFF
        private const val BANNER_SECONDARY_TEXT = 0xC7FFFFFF.toInt()
        private const val PROGRESS_BACKGROUND = 0x33FFFFFF
        private const val FULL_SCREEN_ACTION_BACKGROUND = 0x66000000
        private const val FULL_SCREEN_ACTION_BORDER = 0x66FFFFFF
    }
}
