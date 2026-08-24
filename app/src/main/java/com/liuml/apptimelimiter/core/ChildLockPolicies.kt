package com.liuml.apptimelimiter.core

enum class ChildLockTrigger {
    APP_DAILY,
    GROUP_DAILY,
    APP_PER_LAUNCH,
    GROUP_PER_LAUNCH,
    SCHEDULE,
    COOLDOWN,
    SESSION_PLAN,
}

object ChildLockTriggerPolicy {
    fun supportsTemporaryOverride(trigger: ChildLockTrigger): Boolean = when (trigger) {
        ChildLockTrigger.APP_DAILY,
        ChildLockTrigger.GROUP_DAILY,
        ChildLockTrigger.APP_PER_LAUNCH,
        ChildLockTrigger.GROUP_PER_LAUNCH,
        ChildLockTrigger.SCHEDULE,
        ChildLockTrigger.COOLDOWN,
        ChildLockTrigger.SESSION_PLAN,
        -> true
    }
}

data class PinAttemptState(
    val failedAttempts: Int = 0,
    val lockoutUntilMillis: Long = 0L,
)

data class PinAttemptDecision(
    val state: PinAttemptState,
    val accepted: Boolean,
    val locked: Boolean,
    val remainingMillis: Long,
)

object PinAttemptPolicy {
    fun evaluate(
        state: PinAttemptState,
        pinMatches: Boolean,
        nowMillis: Long,
    ): PinAttemptDecision {
        val normalizedState = normalize(state, nowMillis)
        val remaining = (normalizedState.lockoutUntilMillis - nowMillis).coerceAtLeast(0L)
        if (remaining > 0L) {
            return PinAttemptDecision(
                normalizedState,
                accepted = false,
                locked = true,
                remaining,
            )
        }
        if (pinMatches) {
            return PinAttemptDecision(PinAttemptState(), accepted = true, locked = false, 0L)
        }
        val failures = (normalizedState.failedAttempts + 1).coerceAtMost(MAX_FAILURES)
        val delay = lockoutMillis(failures)
        val updated = PinAttemptState(
            failedAttempts = failures,
            lockoutUntilMillis = if (delay > 0L) safeAdd(nowMillis, delay) else 0L,
        )
        return PinAttemptDecision(
            state = updated,
            accepted = false,
            locked = delay > 0L,
            remainingMillis = delay,
        )
    }

    fun normalize(state: PinAttemptState, nowMillis: Long): PinAttemptState {
        val maximumUntil = safeAdd(nowMillis.coerceAtLeast(0L), MAX_LOCKOUT_MILLIS)
        return if (state.lockoutUntilMillis > maximumUntil) {
            state.copy(lockoutUntilMillis = maximumUntil)
        } else {
            state
        }
    }

    fun remainingMillis(state: PinAttemptState, nowMillis: Long): Long =
        (normalize(state, nowMillis).lockoutUntilMillis - nowMillis).coerceAtLeast(0L)

    private fun lockoutMillis(failures: Int): Long = when (failures) {
        in 0..4 -> 0L
        5 -> 30_000L
        6 -> 60_000L
        7 -> 5 * 60_000L
        else -> MAX_LOCKOUT_MILLIS
    }

    private fun safeAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right

    private const val MAX_FAILURES = 1_000_000
    private const val MAX_LOCKOUT_MILLIS = 15 * 60_000L
}

data class TemporaryOverrideIdentity(
    val packageName: String,
    val processSessionId: String,
    val ruleVersion: Long,
    val groupVersion: Long,
    val protectionModeGeneration: Long,
)

data class TemporaryParentOverride(
    val identity: TemporaryOverrideIdentity,
    val grantedAtElapsedMillis: Long,
    val expiresAtElapsedMillis: Long,
)

object ParentOverrideDurationPolicy {
    const val MIN_MINUTES = 1
    const val MAX_MINUTES = 60
    const val DEFAULT_MINUTES = 5

    fun normalizeMinutes(minutes: Int): Int = minutes.coerceIn(MIN_MINUTES, MAX_MINUTES)

    fun durationMillis(minutes: Int): Long = normalizeMinutes(minutes) * 60_000L
}

object TemporaryParentOverridePolicy {
    fun isValid(
        granted: TemporaryParentOverride,
        current: TemporaryOverrideIdentity,
        screenInteractive: Boolean,
        nowElapsedMillis: Long,
    ): Boolean = screenInteractive &&
        nowElapsedMillis >= granted.grantedAtElapsedMillis &&
        nowElapsedMillis < granted.expiresAtElapsedMillis &&
        granted.identity.packageName.isNotBlank() &&
        granted.identity.packageName == current.packageName &&
        granted.identity.processSessionId.isNotBlank() &&
        granted.identity.processSessionId == current.processSessionId &&
        granted.identity.ruleVersion == current.ruleVersion &&
        granted.identity.groupVersion == current.groupVersion &&
        granted.identity.protectionModeGeneration == current.protectionModeGeneration

    fun remainingMillis(
        granted: TemporaryParentOverride,
        current: TemporaryOverrideIdentity,
        screenInteractive: Boolean,
        nowElapsedMillis: Long,
    ): Long = if (isValid(granted, current, screenInteractive, nowElapsedMillis)) {
        (granted.expiresAtElapsedMillis - nowElapsedMillis).coerceAtLeast(0L)
    } else {
        0L
    }
}

object ManagerUnlockSessionPolicy {
    const val BACKGROUND_GRACE_MILLIS = 30_000L

    fun remainsUnlocked(
        unlocked: Boolean,
        backgroundStartedAtElapsedMillis: Long?,
        nowElapsedMillis: Long,
    ): Boolean {
        if (!unlocked) return false
        val started = backgroundStartedAtElapsedMillis ?: return true
        return nowElapsedMillis - started <= BACKGROUND_GRACE_MILLIS
    }
}

object ParentControlSessionPolicy {
    fun ownsSessionUi(
        authenticationPending: Boolean,
        overrideAwaitingResume: Boolean,
        overrideActive: Boolean,
    ): Boolean = authenticationPending || overrideAwaitingResume || overrideActive
}

/** Validates the system-supplied caller before a foreground bootstrap restores provider access. */
object ParentAuthBootstrapPolicy {
    fun isAllowed(
        callingPackage: String?,
        requestedPackage: String?,
        ownPackage: String,
        configuredPackages: Set<String>,
    ): Boolean {
        val caller = callingPackage.orEmpty()
        val requested = requestedPackage.orEmpty()
        return PackageNamePolicy.isValid(caller) &&
            caller == requested &&
            caller != ownPackage &&
            caller in configuredPackages
    }
}
