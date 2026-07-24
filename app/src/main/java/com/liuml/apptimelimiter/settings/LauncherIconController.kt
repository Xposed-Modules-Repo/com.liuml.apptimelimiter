package com.liuml.apptimelimiter.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

object LauncherIconController {
    data class ChangeResult(
        val hidden: Boolean,
        val launcherResolvable: Boolean,
        val recoveryEntryResolvable: Boolean,
    )

    fun isHidden(context: Context): Boolean =
        !isComponentEnabled(context.packageManager, launcherComponent(context))

    fun ensureRecoveryEntry(context: Context): Boolean {
        val packageManager = context.packageManager
        val recovery = recoveryComponent(context)
        if (!isComponentEnabled(packageManager, recovery)) {
            packageManager.setComponentEnabledSetting(
                recovery,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
        return isComponentEnabled(packageManager, recovery) &&
            packageManager.queryIntentActivities(recoveryIntent(context), 0)
                .any { it.activityInfo?.name == recovery.className }
    }

    fun setHidden(context: Context, hidden: Boolean): ChangeResult {
        if (hidden && !ensureRecoveryEntry(context)) {
            error("LSPosed/系统设置恢复入口不可用，已拒绝隐藏")
        }
        val packageManager = context.packageManager
        val launcher = launcherComponent(context)
        packageManager.setComponentEnabledSetting(
            launcher,
            if (hidden) {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            },
            PackageManager.DONT_KILL_APP,
        )
        val actualHidden = !isComponentEnabled(packageManager, launcher)
        check(actualHidden == hidden) { "桌面入口组件状态未生效" }
        return ChangeResult(
            hidden = actualHidden,
            launcherResolvable = isLauncherResolvable(context),
            recoveryEntryResolvable = ensureRecoveryEntry(context),
        )
    }

    fun isLauncherResolvable(context: Context): Boolean {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(context.packageName)
        val launcherName = launcherComponent(context).className
        return context.packageManager.queryIntentActivities(intent, 0)
            .any { it.activityInfo?.name == launcherName }
    }

    private fun recoveryIntent(context: Context): Intent =
        Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_DEFAULT)
            .addCategory(MODULE_SETTINGS_CATEGORY)
            .setPackage(context.packageName)

    private fun isComponentEnabled(
        packageManager: PackageManager,
        component: ComponentName,
    ): Boolean = when (packageManager.getComponentEnabledSetting(component)) {
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED,
        -> false
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
        else -> packageManager.getActivityInfo(component, 0).enabled
    }

    private fun launcherComponent(context: Context) =
        ComponentName(context, "${context.packageName}.LauncherAlias")

    private fun recoveryComponent(context: Context) =
        ComponentName(context, "${context.packageName}.MainActivity")

    const val MODULE_SETTINGS_CATEGORY = "de.robv.android.xposed.category.MODULE_SETTINGS"
    const val RECOVERY_COMMAND =
        "adb shell pm enable com.liuml.apptimelimiter/.LauncherAlias\n" +
            "adb shell am start -n com.liuml.apptimelimiter/.MainActivity"
}
