package com.liuml.apptimelimiter.settings

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

object LauncherIconController {
    fun setHidden(context: Context, hidden: Boolean) {
        val component = ComponentName(context, "${context.packageName}.LauncherAlias")
        context.packageManager.setComponentEnabledSetting(
            component,
            if (hidden) {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            },
            PackageManager.DONT_KILL_APP,
        )
    }

    const val RECOVERY_COMMAND =
        "adb shell am start -a android.intent.action.VIEW -d apptimelimiter://settings"
}
