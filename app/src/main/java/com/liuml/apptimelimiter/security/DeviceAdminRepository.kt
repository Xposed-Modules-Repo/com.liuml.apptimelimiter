package com.liuml.apptimelimiter.security

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings

class DeviceAdminRepository(context: Context) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(DevicePolicyManager::class.java)
    private val component = ComponentName(appContext, TimeStopDeviceAdminReceiver::class.java)

    fun isActive(): Boolean = runCatching {
        manager?.isAdminActive(component) == true
    }.getOrDefault(false)

    fun activationIntent(): Intent {
        val adminIntent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component)
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "启用后，卸载时需要先解除时停的设备管理权限。",
            )
        }
        return if (adminIntent.resolveActivity(appContext.packageManager) != null) {
            adminIntent
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }
    }

    fun canActivate(): Boolean = runCatching {
        Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .resolveActivity(appContext.packageManager) != null
    }.getOrDefault(false)

    /* Kept separate from activationIntent for callers that need a stable explicit component. */
    private fun legacyActivationIntent(): Intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
        putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component)
        putExtra(
            DevicePolicyManager.EXTRA_ADD_EXPLANATION,
            "启用后，卸载时需要先解除时停的设备管理权限。",
        )
    }

    fun disable(): Boolean = runCatching {
        manager?.removeActiveAdmin(component)
        !isActive()
    }.getOrDefault(false)
}
