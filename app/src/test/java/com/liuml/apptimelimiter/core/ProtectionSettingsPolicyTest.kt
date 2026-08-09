package com.liuml.apptimelimiter.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectionSettingsPolicyTest {
    @Test
    fun accessibilityOnlyHidesHookOnlySettings() {
        val visibility = ProtectionSettingsPolicy.resolve(
            xposedAvailable = false,
            nonRootEnabled = true,
            shizukuSelected = false,
            launcherIconHidden = false,
        )

        assertFalse(visibility.showXposedExecution)
        assertFalse(visibility.showHookReminderSettings)
        assertTrue(visibility.showNonRootExecution)
        assertFalse(visibility.showShizukuDetails)
        assertFalse(visibility.showLauncherIconControl)
    }

    @Test
    fun shizukuDetailsOnlyAppearForSelectedNonRootMode() {
        val visibility = ProtectionSettingsPolicy.resolve(
            xposedAvailable = false,
            nonRootEnabled = true,
            shizukuSelected = true,
            launcherIconHidden = false,
        )

        assertTrue(visibility.showNonRootExecution)
        assertTrue(visibility.showShizukuDetails)
        assertFalse(visibility.showHookReminderSettings)
    }

    @Test
    fun xposedShowsHookExecutionAndReminderSettings() {
        val visibility = ProtectionSettingsPolicy.resolve(
            xposedAvailable = true,
            nonRootEnabled = false,
            shizukuSelected = false,
            launcherIconHidden = false,
        )

        assertTrue(visibility.showXposedExecution)
        assertTrue(visibility.showHookReminderSettings)
        assertTrue(visibility.showLauncherIconControl)
        assertFalse(visibility.launcherControlIsRecoveryOnly)
    }

    @Test
    fun xposedSettingsRemainVisibleWhileFrameworkStateIsUnknown() {
        val visibility = ProtectionSettingsPolicy.resolve(
            xposedAvailable = false,
            nonRootEnabled = false,
            shizukuSelected = false,
            launcherIconHidden = false,
        )

        assertTrue(visibility.showXposedExecution)
        assertTrue(visibility.showHookReminderSettings)
        assertTrue(visibility.showLauncherIconControl)
    }

    @Test
    fun nonRootModeHidesHookSectionsEvenWhenFrameworkIsAvailable() {
        val visibility = ProtectionSettingsPolicy.resolve(
            xposedAvailable = true,
            nonRootEnabled = true,
            shizukuSelected = false,
            launcherIconHidden = false,
        )

        assertFalse(visibility.showXposedExecution)
        assertFalse(visibility.showHookReminderSettings)
        assertTrue(visibility.showNonRootExecution)
        assertTrue(visibility.showLauncherIconControl)
    }

    @Test
    fun hiddenLauncherCanAlwaysBeRecoveredWithoutXposed() {
        val visibility = ProtectionSettingsPolicy.resolve(
            xposedAvailable = false,
            nonRootEnabled = true,
            shizukuSelected = false,
            launcherIconHidden = true,
        )

        assertTrue(visibility.showLauncherIconControl)
        assertTrue(visibility.launcherControlIsRecoveryOnly)
    }
}
