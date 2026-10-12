package com.liuml.apptimelimiter.ui

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** A view inside the existing target window, never a Dialog or a new Activity/task. */
class WindowRestrictionPanel(
    context: Context,
    colors: TargetUiColors,
    english: Boolean,
    onExit: () -> Unit,
    onPin: () -> Unit,
    onExtend: () -> Unit,
) : FrameLayout(context) {
    private val titleView = TextView(context)
    private val messageView = TextView(context)
    private val statusView = TextView(context)
    private val exitButton = Button(context)
    private val pinButton = Button(context)
    private val extendButton = Button(context)
    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()

    init {
        isClickable = true
        isFocusableInTouchMode = true
        setBackgroundColor(colors.background)
        setOnKeyListener { _, key, _ -> key == KeyEvent.KEYCODE_BACK }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val scroll = ScrollView(context).apply { isFillViewport = true; addView(content) }
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        titleView.apply { textSize = 20f; typeface = Typeface.DEFAULT_BOLD; setTextColor(colors.textPrimary) }
        messageView.apply { textSize = 14f; setTextColor(colors.textSecondary); setPadding(0, dp(12), 0, dp(12)) }
        statusView.apply { textSize = 14f; setTextColor(colors.textPrimary); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        content.addView(titleView)
        content.addView(messageView)
        content.addView(statusView)
        fun button(view: Button, label: String, action: () -> Unit) {
            view.text = label
            view.isAllCaps = false
            view.minHeight = dp(48)
            view.setTextColor(colors.primary)
            view.setOnClickListener { action() }
            content.addView(view, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        button(exitButton, if (english) "Close this app window" else "关闭此应用窗口", onExit)
        button(pinButton, if (english) "Unlock with PIN" else "PIN 临时放行", onPin)
        button(extendButton, if (english) "Extend this session" else "延时使用", onExtend)
    }

    fun update(title: String, message: String, pinAllowed: Boolean, extensionAllowed: Boolean, busy: Boolean) {
        titleView.text = title
        messageView.text = message
        pinButton.visibility = if (pinAllowed) View.VISIBLE else View.GONE
        extendButton.visibility = if (extensionAllowed) View.VISIBLE else View.GONE
        listOf(exitButton, pinButton, extendButton).forEach { it.isEnabled = !busy }
    }

    fun showStatus(message: String) { statusView.text = message }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        if (event.keyCode == KeyEvent.KEYCODE_BACK) true else super.dispatchKeyEvent(event)
    fun detach() { (parent as? ViewGroup)?.removeView(this) }

    companion object {
        fun attach(activity: android.app.Activity, panel: WindowRestrictionPanel): Boolean {
            val decor = activity.window.decorView as? ViewGroup ?: return false
            if (activity.isFinishing || activity.isDestroyed) return false
            decor.addView(panel, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            panel.requestFocus()
            return panel.parent === decor
        }
    }
}
