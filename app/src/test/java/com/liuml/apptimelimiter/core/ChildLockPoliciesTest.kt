package com.liuml.apptimelimiter.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChildLockPoliciesTest {

    @Test
    fun `parent authentication and override exclusively own temporary session ui`() {
        assertFalse(ParentControlSessionPolicy.ownsSessionUi(false, false, false))
        assertTrue(ParentControlSessionPolicy.ownsSessionUi(true, false, false))
        assertTrue(ParentControlSessionPolicy.ownsSessionUi(false, true, false))
        assertTrue(ParentControlSessionPolicy.ownsSessionUi(false, false, true))
    }

    @Test
    fun `foreground bootstrap accepts only matching configured caller`() {
        assertTrue(
            ParentAuthBootstrapPolicy.isAllowed(
                callingPackage = "app.target",
                requestedPackage = "app.target",
                ownPackage = "app.manager",
                configuredPackages = setOf("app.target"),
            ),
        )
        assertFalse(
            ParentAuthBootstrapPolicy.isAllowed(
                callingPackage = "app.attacker",
                requestedPackage = "app.target",
                ownPackage = "app.manager",
                configuredPackages = setOf("app.target"),
            ),
        )
        assertFalse(
            ParentAuthBootstrapPolicy.isAllowed(
                callingPackage = "app.target",
                requestedPackage = "app.target",
                ownPackage = "app.manager",
                configuredPackages = emptySet(),
            ),
        )
    }
    @Test
    fun `fifth failure starts escalating lockout`() {
        var state = PinAttemptState()
        repeat(4) {
            val decision = PinAttemptPolicy.evaluate(state, false, 1_000L)
            assertFalse(decision.locked)
            state = decision.state
        }
        val fifth = PinAttemptPolicy.evaluate(state, false, 1_000L)
        assertTrue(fifth.locked)
        assertEquals(30_000L, fifth.remainingMillis)

        val sixth = PinAttemptPolicy.evaluate(
            fifth.state.copy(lockoutUntilMillis = 0L),
            false,
            40_000L,
        )
        assertEquals(60_000L, sixth.remainingMillis)
        val seventh = PinAttemptPolicy.evaluate(
            sixth.state.copy(lockoutUntilMillis = 0L),
            false,
            110_000L,
        )
        assertEquals(5 * 60_000L, seventh.remainingMillis)
        val eighth = PinAttemptPolicy.evaluate(
            seventh.state.copy(lockoutUntilMillis = 0L),
            false,
            500_000L,
        )
        assertEquals(15 * 60_000L, eighth.remainingMillis)
    }

    @Test
    fun `success clears failures and active lockout rejects without increment`() {
        val locked = PinAttemptState(5, 40_000L)
        val duringLockout = PinAttemptPolicy.evaluate(locked, true, 10_000L)
        assertTrue(duringLockout.locked)
        assertEquals(locked, duringLockout.state)

        val success = PinAttemptPolicy.evaluate(locked.copy(lockoutUntilMillis = 0L), true, 50_000L)
        assertTrue(success.accepted)
        assertEquals(PinAttemptState(), success.state)
    }

    @Test
    fun `clock rollback cannot create an excessive lockout`() {
        val stored = PinAttemptState(failedAttempts = 8, lockoutUntilMillis = 86_400_000L)
        val normalized = PinAttemptPolicy.normalize(stored, nowMillis = 1_000L)
        assertEquals(901_000L, normalized.lockoutUntilMillis)
        assertEquals(15 * 60_000L, PinAttemptPolicy.remainingMillis(normalized, 1_000L))
    }

    @Test
    fun `temporary override survives target process recreation but remains generation bound`() {
        val identity = TemporaryOverrideIdentity("app.a", "session-a", 2L, 3L, 4L)
        val granted = TemporaryParentOverride(identity, 1_000L, 61_000L)
        assertTrue(TemporaryParentOverridePolicy.isValid(granted, identity, true, 2_000L))
        assertTrue(
            TemporaryParentOverridePolicy.isValid(
                granted,
                identity.copy(processSessionId = "session-b"),
                true,
                2_000L,
            ),
        )
        assertFalse(
            TemporaryParentOverridePolicy.isValid(
                granted,
                identity.copy(ruleVersion = 5L),
                true,
                2_000L,
            ),
        )
        assertTrue(TemporaryParentOverridePolicy.isValid(granted, identity, false, 2_000L))
        assertFalse(TemporaryParentOverridePolicy.isValid(granted, identity, true, 61_000L))
    }

    @Test
    fun `parent override duration is clamped to one through sixty minutes`() {
        assertEquals(5, ParentOverrideDurationPolicy.DEFAULT_MINUTES)
        assertEquals(1, ParentOverrideDurationPolicy.normalizeMinutes(0))
        assertEquals(60, ParentOverrideDurationPolicy.normalizeMinutes(61))
        assertEquals(60_000L, ParentOverrideDurationPolicy.durationMillis(1))
        assertEquals(3_600_000L, ParentOverrideDurationPolicy.durationMillis(60))
    }

    @Test
    fun `manager unlock expires only after background grace`() {
        assertTrue(ManagerUnlockSessionPolicy.remainsUnlocked(true, null, 100_000L))
        assertTrue(ManagerUnlockSessionPolicy.remainsUnlocked(true, 100_000L, 130_000L))
        assertFalse(ManagerUnlockSessionPolicy.remainsUnlocked(true, 100_000L, 130_001L))
    }
}
