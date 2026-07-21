package com.liuml.apptimelimiter.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.UserHandle

class InstalledAppsRepository(private val context: Context) {
    private val packageManager = context.packageManager

    fun loadLaunchableApps(): List<InstalledApp> {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos = if (Build.VERSION.SDK_INT >= 33) {
            packageManager.queryIntentActivities(
                launcherIntent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)
        }

        return resolveInfos
            .asSequence()
            .mapNotNull { it.activityInfo?.applicationInfo }
            .filter { it.packageName != context.packageName }
            // LSPosed scopes packages per Android user. Rules in this app are currently keyed
            // only by package name, so showing a clone from another user/profile would imply a
            // level of control that we cannot represent safely yet.
            .filter { UserHandle.getUserHandleForUid(it.uid) == Process.myUserHandle() }
            .distinctBy { it.packageName }
            .map { appInfo ->
                InstalledApp(
                    label = appInfo.loadLabel(packageManager).toString().ifBlank { appInfo.packageName },
                    packageName = appInfo.packageName,
                    isSystemApp = appInfo.flags and
                        (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0,
                )
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
            .toList()
    }
}
