package com.liuml.apptimelimiter.xposedstatus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XposedStatusPolicyTest {
    @Test
    fun serviceScopeIsKnownWithoutStartingTargetApp() {
        val snapshot = XposedFrameworkSnapshot(
            connected = true,
            apiVersion = 102,
            scopePackages = setOf(PACKAGE),
        )

        assertEquals(
            ManagedAppHookState.IN_SCOPE_IDLE,
            XposedStatusPolicy.resolve(PACKAGE, snapshot, 0, 20),
        )
    }

    @Test
    fun missingScopeWinsOverOldHeartbeat() {
        assertEquals(
            ManagedAppHookState.NOT_IN_SCOPE,
            XposedStatusPolicy.resolve(
                PACKAGE,
                XposedFrameworkSnapshot(connected = true),
                legacyHookVersionCode = 20,
                currentVersionCode = 20,
            ),
        )
    }

    @Test
    fun runningTargetReportsCurrentStaleAndFailedStates() {
        fun resolve(state: HookTargetState, version: Long = 20L) =
            XposedStatusPolicy.resolve(
                PACKAGE,
                XposedFrameworkSnapshot(
                    connected = true,
                    scopePackages = setOf(PACKAGE),
                    runningTargets = listOf(
                        XposedRunningTarget("$PACKAGE:worker", version, state),
                    ),
                ),
                legacyHookVersionCode = 0,
                currentVersionCode = 20,
            )

        assertEquals(ManagedAppHookState.RUNNING_CURRENT, resolve(HookTargetState.UP_TO_DATE))
        assertEquals(ManagedAppHookState.RUNNING_STALE, resolve(HookTargetState.STALE))
        assertEquals(
            ManagedAppHookState.RUNNING_STALE,
            resolve(HookTargetState.UP_TO_DATE, version = 19L),
        )
        assertEquals(ManagedAppHookState.RUNNING_FAILED, resolve(HookTargetState.FAILED))
    }

    @Test
    fun unavailableServiceFallsBackToLegacyHeartbeat() {
        val disconnected = XposedFrameworkSnapshot()
        assertEquals(
            ManagedAppHookState.LEGACY_VERIFIED,
            XposedStatusPolicy.resolve(PACKAGE, disconnected, 20, 20),
        )
        assertEquals(
            ManagedAppHookState.COMPATIBILITY_PENDING,
            XposedStatusPolicy.resolve(PACKAGE, disconnected, 0, 20),
        )
    }

    @Test
    fun processMatchingDoesNotConfuseSimilarPackageNames() {
        assertTrue(XposedStatusPolicy.processBelongsToPackage(PACKAGE, PACKAGE))
        assertTrue(XposedStatusPolicy.processBelongsToPackage("$PACKAGE:push", PACKAGE))
        assertFalse(XposedStatusPolicy.processBelongsToPackage("${PACKAGE}2", PACKAGE))
    }

    private companion object {
        const val PACKAGE = "com.example.target"
    }
}
