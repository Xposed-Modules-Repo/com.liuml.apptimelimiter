package com.liuml.apptimelimiter.settings

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

object LauncherIconController {
    fun setHidden(
        context: Context,
        hidden: Boolean,
        refreshLauncher: Boolean = false,
    ) {
        val component = ComponentName(context, "${context.packageName}.LauncherAlias")
        context.packageManager.setComponentEnabledSetting(
            component,
            if (hidden) {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            },
            if (refreshLauncher) 0 else PackageManager.DONT_KILL_APP,
        )
    }

    const val RECOVERY_COMMAND =
        "adb shell pm enable com.liuml.apptimelimiter/.LauncherAlias\n" +
            "adb shell am start -n com.liuml.apptimelimiter/.MainActivity"
}
