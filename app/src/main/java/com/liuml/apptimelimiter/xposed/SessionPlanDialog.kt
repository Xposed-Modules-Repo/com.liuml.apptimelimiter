package com.liuml.apptimelimiter.xposed

import android.app.Activity
import android.app.Dialog
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.Window
import com.liuml.apptimelimiter.data.AppThemeColor
import com.liuml.apptimelimiter.data.AppThemeMode
import com.liuml.apptimelimiter.ui.SessionPlanPanel
import com.liuml.apptimelimiter.ui.SessionPlanPanelCopy
import com.liuml.apptimelimiter.ui.TargetUiPalette

internal enum class SessionPlanDialogMode {
    INITIAL,
    REPLAN,
}

internal class SessionPlanDialog private constructor(
    private val dialog: Dialog,
) {
    val isShowing: Boolean get() = dialog.isShowing

    fun dismiss() {
        runCatching { dialog.dismiss() }
    }

    companion object {
        fun show(
            activity: Activity,
            mode: SessionPlanDialogMode,
            english: Boolean,
            themeMode: AppThemeMode,
            themeColor: AppThemeColor,
            quote: String?,
            includeDebugChoice: Boolean,
            maxAllowedMillis: Long?,
            onStart: (Long) -> Unit,
            onWithoutPlan: () -> Unit,
            onExit: () -> Unit,
        ): SessionPlanDialog? {
            if (activity.isFinishing || activity.isDestroyed) return null
            var holder: SessionPlanDialog? = null
            val panel = SessionPlanPanel(
                context = activity,
                colors = TargetUiPalette.resolve(activity, themeMode, themeColor),
                english = english,
                copy = SessionPlanPanelCopy(
                    eyebrow = if (english) "TIME STOP · THIS SESSION" else "时停 · 本次使用",
                    title = if (english) {
                        if (mode == SessionPlanDialogMode.INITIAL) "Plan this session" else "Replan this session"
                    } else {
                        if (mode == SessionPlanDialogMode.INITIAL) "制定本次使用计划" else "重新制定本次计划"
                    },
                    description = if (english) {
                        "Only foreground time counts. The plan pauses in the background or with the screen off."
                    } else {
                        "只计算前台使用时间，切到后台或息屏后暂停。"
                    },
                    skipLabel = if (english) {
                        if (mode == SessionPlanDialogMode.INITIAL) "Skip this time" else "Cancel plan"
                    } else {
                        if (mode == SessionPlanDialogMode.INITIAL) "本次不计划" else "取消本次计划"
                    },
                ),
                quote = quote,
                includeDebugChoice = includeDebugChoice,
                maxAllowedMillis = maxAllowedMillis,
                onStart = {
                    holder?.dismiss()
                    onStart(it)
                },
                onSkip = {
                    holder?.dismiss()
                    onWithoutPlan()
                },
                onExit = {
                    holder?.dismiss()
                    onExit()
                },
            )
            val dialog = Dialog(activity).apply {
                requestWindowFeature(Window.FEATURE_NO_TITLE)
                setContentView(panel)
                setCancelable(false)
                setCanceledOnTouchOutside(false)
                setOnKeyListener { _, keyCode, event ->
                    if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                        panel.handleBack()
                    } else {
                        false
                    }
                }
            }
            holder = SessionPlanDialog(dialog)
            return runCatching {
                dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
                dialog.show()
                dialog.window?.apply {
                    setBackgroundDrawableResource(android.R.color.transparent)
                    val displayWidth = activity.resources.displayMetrics.widthPixels
                    val density = activity.resources.displayMetrics.density
                    fun dp(value: Int): Int = (value * density + 0.5f).toInt()
                    setLayout(
                        (displayWidth - dp(28)).coerceAtMost(dp(430)),
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                    setDimAmount(0.38f)
                }
                holder
            }.getOrNull()
        }
    }
}
