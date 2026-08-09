package com.liuml.apptimelimiter.nonroot

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.liuml.apptimelimiter.BuildConfig
import com.liuml.apptimelimiter.core.TimeQuotePolicy
import com.liuml.apptimelimiter.data.RuleRepository
import com.liuml.apptimelimiter.ui.SessionPlanPanel
import com.liuml.apptimelimiter.ui.SessionPlanPanelCopy
import com.liuml.apptimelimiter.ui.TargetUiColors
import com.liuml.apptimelimiter.ui.TargetUiPalette

class NonRootSessionPlanOverlay(
    private val service: AccessibilityService,
    private val diagnostic: (
        level: String,
        packageName: String,
        event: String,
        message: String,
    ) -> Unit = { _, _, _, _ -> },
) {
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private var attachedView: View? = null
    private var attachedPackageName = ""
    private var attachedKind = ""
    private var ignoreOwnWindowEventsUntilElapsedMillis = 0L

    val isShowing: Boolean get() = attachedView != null
    val shouldIgnoreOwnWindowEvents: Boolean
        get() = isShowing ||
            SystemClock.elapsedRealtime() <= ignoreOwnWindowEventsUntilElapsedMillis

    fun showPlan(
        packageName: String,
        english: Boolean,
        quoteSeed: String,
        maxAllowedMillis: Long?,
        onShown: () -> Unit,
        onSelected: (Long) -> Unit,
        onSkipped: () -> Unit,
        onExit: () -> Unit,
        onUnexpectedDetach: () -> Unit,
    ): Boolean {
        dismiss()
        val settings = RuleRepository(service).getGlobalSettings()
        val ui = OverlayUi(
            service,
            english,
            TargetUiPalette.resolve(service, settings.themeMode, settings.themeColor),
        )
        val root = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(ui.dp(16), ui.dp(18), ui.dp(16), ui.dp(18))
            setBackgroundColor(ui.colors.scrim)
            isClickable = true
        }
        val panel = SessionPlanPanel(
            context = service,
            colors = ui.colors,
            english = english,
            copy = SessionPlanPanelCopy(
                eyebrow = ui.text("时停 · 普通保护", "TIME STOP · STANDARD PROTECTION"),
                title = ui.text("制定本次使用计划", "Plan this session"),
                description = ui.text(
                    "仅计算前台时间；离开应用超过30秒后视为新一次使用。",
                    "Only foreground time counts. Leaving for more than 30 seconds starts a new session.",
                ),
                skipLabel = ui.text("本次不计划", "Skip this time"),
            ),
            quote = TimeQuotePolicy.select(
                enabled = settings.timeQuotesEnabled,
                builtInEnabled = settings.builtInTimeQuotesEnabled,
                customQuotes = settings.customTimeQuotes,
                english = english,
                seed = quoteSeed,
            ),
            includeDebugChoice = BuildConfig.DEBUG,
            maxAllowedMillis = maxAllowedMillis,
            onStart = {
                dismiss()
                onSelected(it)
            },
            onSkip = {
                dismiss()
                onSkipped()
            },
            onExit = {
                dismiss()
                onExit()
            },
        )
        root.addView(
            panel,
            LinearLayout.LayoutParams(
                (service.resources.displayMetrics.widthPixels - ui.dp(32)).coerceAtMost(ui.dp(430)),
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        return attach(
            packageName = packageName,
            kind = "SESSION_PLAN",
            view = root,
            params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.CENTER },
            onShown = onShown,
            onUnexpectedDetach = onUnexpectedDetach,
        )
    }

    fun showExpiryWarning(
        packageName: String,
        english: Boolean,
        onReplan: () -> Unit,
        onExit: () -> Unit,
    ): Boolean {
        dismiss()
        val settings = RuleRepository(service).getGlobalSettings()
        val ui = OverlayUi(
            service,
            english,
            TargetUiPalette.resolve(service, settings.themeMode, settings.themeColor),
        )
        val root = LinearLayout(service).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            setPadding(ui.dp(16), ui.dp(10), ui.dp(10), ui.dp(10))
            background = ui.rounded(ui.colors.primaryContainer, 18, ui.colors.outline)
        }
        root.addView(
            TextView(service).apply {
                text = ui.text("本次计划时间即将结束", "This session plan is about to end")
                setTextColor(ui.colors.onPrimaryContainer)
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        root.addView(
            ui.compactAction(ui.text("退出", "Exit"), filled = false) {
                dismiss()
                onExit()
            },
        )
        root.addView(
            ui.compactAction(ui.text("重新计划", "Replan"), filled = true) {
                dismiss()
                onReplan()
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                ui.dp(40),
            ).apply { marginStart = ui.dp(8) },
        )
        return attach(
            packageName = packageName,
            kind = "EXPIRY_WARNING",
            view = root,
            params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP
                x = ui.dp(12)
                y = ui.dp(12)
            },
            onShown = {},
        )
    }

    fun dismiss(reason: String = "requested") {
        val view = attachedView ?: return
        val packageName = attachedPackageName
        val kind = attachedKind
        attachedView = null
        attachedPackageName = ""
        attachedKind = ""
        ignoreOwnWindowEventsUntilElapsedMillis =
            SystemClock.elapsedRealtime() + OWN_WINDOW_EVENT_SETTLE_MILLIS
        runCatching { windowManager.removeViewImmediate(view) }
            .onSuccess {
                diagnostic("INFO", packageName, "NON_ROOT_OVERLAY_DISMISSED", "kind=$kind, reason=$reason")
            }
            .onFailure {
                diagnostic(
                    "WARN",
                    packageName,
                    "NON_ROOT_OVERLAY_REMOVE_FAILED",
                    "kind=$kind, reason=$reason, error=${describeThrowable(it)}",
                )
            }
    }

    private fun attach(
        packageName: String,
        kind: String,
        view: View,
        params: WindowManager.LayoutParams,
        onShown: () -> Unit,
        onUnexpectedDetach: () -> Unit = {},
    ): Boolean {
        dismiss("replace_before_attach")
        diagnostic("INFO", packageName, "NON_ROOT_OVERLAY_ADD_REQUESTED", windowSummary(kind, params))
        return try {
            var shownNotified = false
            view.addOnAttachStateChangeListener(
                object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(attached: View) = Unit

                    override fun onViewDetachedFromWindow(detached: View) {
                        if (attachedView !== detached) return
                        attachedView = null
                        attachedPackageName = ""
                        attachedKind = ""
                        ignoreOwnWindowEventsUntilElapsedMillis =
                            SystemClock.elapsedRealtime() + OWN_WINDOW_EVENT_SETTLE_MILLIS
                        diagnostic(
                            "WARN",
                            packageName,
                            "NON_ROOT_OVERLAY_DETACHED",
                            "kind=$kind, shownNotified=$shownNotified",
                        )
                        onUnexpectedDetach()
                    }
                },
            )
            windowManager.addView(view, params)
            attachedView = view
            attachedPackageName = packageName
            attachedKind = kind
            diagnostic("INFO", packageName, "NON_ROOT_OVERLAY_ADD_ACCEPTED", windowSummary(kind, params))
            view.post {
                if (attachedView === view) {
                    val attached = view.isAttachedToWindow
                    diagnostic(
                        if (attached) "INFO" else "WARN",
                        packageName,
                        "NON_ROOT_OVERLAY_ATTACH_STATE",
                        "kind=$kind, attached=$attached, shown=${view.isShown}, " +
                            "visibility=${view.visibility}, windowVisibility=${view.windowVisibility}, " +
                            "size=${view.width}x${view.height}",
                    )
                    if (!attached) {
                        attachedView = null
                        attachedPackageName = ""
                        attachedKind = ""
                        onUnexpectedDetach()
                        return@post
                    }
                    try {
                        shownNotified = true
                        onShown()
                    } catch (callbackError: Throwable) {
                        attachedView = null
                        attachedPackageName = ""
                        attachedKind = ""
                        runCatching { windowManager.removeViewImmediate(view) }
                        diagnostic(
                            "ERROR",
                            packageName,
                            "NON_ROOT_OVERLAY_CALLBACK_FAILED",
                            "kind=$kind, error=${describeThrowable(callbackError)}",
                        )
                        onUnexpectedDetach()
                    }
                }
            }
            true
        } catch (error: Throwable) {
            attachedView = null
            attachedPackageName = ""
            attachedKind = ""
            diagnostic(
                "ERROR",
                packageName,
                "NON_ROOT_OVERLAY_ADD_FAILED",
                "${windowSummary(kind, params)}, error=${describeThrowable(error)}",
            )
            false
        }
    }

    private fun windowSummary(
        kind: String,
        params: WindowManager.LayoutParams,
    ): String = "kind=$kind, type=${params.type}, flags=0x${params.flags.toString(16)}, " +
        "size=${params.width}x${params.height}, sdk=${Build.VERSION.SDK_INT}, " +
        "device=${Build.MANUFACTURER}/${Build.MODEL}"

    private fun describeThrowable(error: Throwable): String {
        val cause = error.cause
        return buildString {
            append(error.javaClass.name)
            error.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
            if (cause != null && cause !== error) {
                append("; cause=").append(cause.javaClass.name)
                cause.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
            }
        }
    }

    private class OverlayUi(
        private val service: AccessibilityService,
        private val english: Boolean,
        val colors: TargetUiColors,
    ) {
        fun dp(value: Int): Int =
            (value * service.resources.displayMetrics.density + 0.5f).toInt()

        fun text(chinese: String, englishText: String): String = if (english) englishText else chinese

        fun rounded(color: Int, radius: Int, stroke: Int = Color.TRANSPARENT) =
            GradientDrawable().apply {
                setColor(color)
                cornerRadius = dp(radius).toFloat()
                if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
            }

        fun compactAction(label: String, filled: Boolean, onClick: () -> Unit) =
            actionView(label, filled, 12f, onClick).apply {
                minWidth = dp(54)
                minHeight = dp(40)
            }

        private fun actionView(label: String, filled: Boolean, size: Float, onClick: () -> Unit) =
            TextView(service).apply {
                text = label
                gravity = Gravity.CENTER
                setTextColor(if (filled) colors.onPrimary else colors.onPrimaryContainer)
                textSize = size
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(12), dp(4), dp(12), dp(4))
                background = rounded(
                    if (filled) colors.primary else colors.primaryContainer,
                    15,
                    if (filled) Color.TRANSPARENT else colors.outline,
                )
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
            }

    }

    private companion object {
        const val OWN_WINDOW_EVENT_SETTLE_MILLIS = 500L
    }
}
