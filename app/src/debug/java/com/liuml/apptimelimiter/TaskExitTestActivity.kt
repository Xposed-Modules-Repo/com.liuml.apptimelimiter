package com.liuml.apptimelimiter

import android.app.Activity
import android.os.Bundle
import android.os.ResultReceiver
import com.liuml.apptimelimiter.ipc.TargetTaskExitBridge

/** Debug-only fixture; never packaged into the release APK. */
class TaskExitTestActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        @Suppress("DEPRECATION")
        val receiver = intent.getParcelableExtra<ResultReceiver>("reply") ?: return
        val bridge = TargetTaskExitBridge(this,
            intent.getIntExtra("manager_uid", android.os.Process.myUid())) {
            intent.getBooleanExtra("current", true)
        }
        val embedded = if (intent.getBooleanExtra("embedded", false)) {
            val panel = com.liuml.apptimelimiter.ui.WindowRestrictionPanel(this,
                com.liuml.apptimelimiter.ui.TargetUiPalette.resolve(this), false, {}, {}, {})
            panel.update("使用已暂停", "测试原窗口内遮罩", true, true, false)
            com.liuml.apptimelimiter.ui.WindowRestrictionPanel.attach(this, panel)
        } else false
        receiver.send(1, Bundle().apply {
            putBinder("bridge", bridge)
            putInt("task", taskId)
            putInt("pid", android.os.Process.myPid())
            putBoolean("embedded", embedded)
        })
    }
}

class TaskExitUnderlyingTestActivity : Activity()
