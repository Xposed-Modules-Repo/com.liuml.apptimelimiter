package com.liuml.apptimelimiter.nonroot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundExecutionPolicyTest {
    @Test
    fun `launcher is a definitive home surface and never a target`() {
        assertEquals(
            ForegroundPackageKind.HOME,
            ForegroundPackagePolicy.classify(
                packageName = LAUNCHER,
                ownPackageName = OWN,
                targetPackages = setOf(TARGET),
                homePackages = setOf(LAUNCHER),
                inputMethodPackage = IME,
            ),
        )
    }

    @Test
    fun `disruptive action requires matching confirmed target and generation`() {
        val target = ForegroundExecutionSnapshot(
            packageName = TARGET,
            kind = ForegroundPackageKind.TARGET_APP,
            source = ForegroundSignalSource.ACCESSIBILITY_WINDOW_STATE,
            generation = 4L,
            observedAtMillis = 100L,
            confirmedByAccessibility = true,
        )
        assertTrue(NonRootActionGuard.mayExecuteDisruptiveAction(TARGET, target, 4L))
        assertFalse(NonRootActionGuard.mayExecuteDisruptiveAction(TARGET, target, 3L))
        assertFalse(
            NonRootActionGuard.mayExecuteDisruptiveAction(
                TARGET,
                target.copy(
                    packageName = LAUNCHER,
                    kind = ForegroundPackageKind.HOME,
                    generation = 5L,
                ),
            ),
        )
        assertFalse(
            NonRootActionGuard.mayExecuteDisruptiveAction(
                TARGET,
                target.copy(
                    source = ForegroundSignalSource.USAGE_STATS_RECOVERY,
                    confirmedByAccessibility = false,
                ),
            ),
        )
    }

    @Test
    fun `moving to launcher cancels pending target action`() {
        assertTrue(
            NonRootActionGuard.shouldCancelPendingAction(
                targetPackageName = TARGET,
                newForegroundPackageName = LAUNCHER,
                newForegroundKind = ForegroundPackageKind.HOME,
                ownPackageName = OWN,
            ),
        )
        assertFalse(
            NonRootActionGuard.shouldCancelPendingAction(
                targetPackageName = TARGET,
                newForegroundPackageName = OWN,
                newForegroundKind = ForegroundPackageKind.TIME_STOP_ACTIVITY,
                ownPackageName = OWN,
            ),
        )
    }

    @Test
    fun `authoritative target signal restores snapshot after transient system surface`() {
        val transient = ForegroundExecutionSnapshot(
            packageName = "com.android.systemui",
            kind = ForegroundPackageKind.SYSTEM_UI,
            source = ForegroundSignalSource.ACCESSIBILITY_WINDOWS_CHANGED,
            generation = 5L,
            observedAtMillis = 1_000L,
            confirmedByAccessibility = true,
        )

        assertTrue(
            NonRootActionGuard.shouldRestoreSamePackageSnapshot(
                currentForegroundPackageName = TARGET,
                signalPackageName = TARGET,
                signalKind = ForegroundPackageKind.TARGET_APP,
                signalConfirmedByAccessibility = true,
                currentExecutionSnapshot = transient,
            ),
        )
        assertFalse(
            NonRootActionGuard.shouldRestoreSamePackageSnapshot(
                currentForegroundPackageName = TARGET,
                signalPackageName = TARGET,
                signalKind = ForegroundPackageKind.TARGET_APP,
                signalConfirmedByAccessibility = false,
                currentExecutionSnapshot = transient,
            ),
        )
        assertFalse(
            NonRootActionGuard.shouldRestoreSamePackageSnapshot(
                currentForegroundPackageName = TARGET,
                signalPackageName = TARGET,
                signalKind = ForegroundPackageKind.TARGET_APP,
                signalConfirmedByAccessibility = true,
                currentExecutionSnapshot = transient.copy(
                    packageName = TARGET,
                    kind = ForegroundPackageKind.TARGET_APP,
                ),
            ),
        )
    }

    private companion object {
        const val OWN = "com.liuml.apptimelimiter"
        const val TARGET = "com.example.target"
        const val LAUNCHER = "com.example.launcher"
        const val IME = "com.example.ime"
    }
}
