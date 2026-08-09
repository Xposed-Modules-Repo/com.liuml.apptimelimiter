package com.liuml.apptimelimiter.core

data class ProtectionSettingsVisibility(
    val showXposedExecution: Boolean,
    val showHookReminderSettings: Boolean,
    val showNonRootExecution: Boolean,
    val showShizukuDetails: Boolean,
    val showLauncherIconControl: Boolean,
    val launcherControlIsRecoveryOnly: Boolean,
)

object ProtectionSettingsPolicy {
    fun resolve(
        xposedAvailable: Boolean,
        nonRootEnabled: Boolean,
        shizukuSelected: Boolean,
        launcherIconHidden: Boolean,
    ): ProtectionSettingsVisibility = ProtectionSettingsVisibility(
        showXposedExecution = !nonRootEnabled,
        showHookReminderSettings = !nonRootEnabled,
        showNonRootExecution = nonRootEnabled,
        showShizukuDetails = nonRootEnabled && shizukuSelected,
        showLauncherIconControl = !nonRootEnabled || xposedAvailable || launcherIconHidden,
        launcherControlIsRecoveryOnly = launcherIconHidden && !xposedAvailable,
    )
}
