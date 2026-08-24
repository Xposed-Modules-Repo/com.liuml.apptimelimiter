package com.liuml.apptimelimiter.core

data class SharedGroupSessionRecord(
    val groupVersion: Long = Long.MIN_VALUE,
    val bootCount: Int = Int.MIN_VALUE,
    val sessionId: String = "",
    val usedMillis: Long = 0L,
    val activeOwnerId: String = "",
    val ownerLastSeenElapsedMillis: Long = 0L,
    val inactiveSinceElapsedMillis: Long = 0L,
    val handledSegmentIds: List<String> = emptyList(),
)

enum class SharedGroupSessionAction {
    ENTER,
    COMMIT,
    LEAVE,
}

data class SharedGroupSessionUpdate(
    val record: SharedGroupSessionRecord,
    val restarted: Boolean,
    val segmentAccepted: Boolean,
    val ownerTransferred: Boolean,
    val staleRequest: Boolean,
)

/**
 * Maintains one per-launch allowance across every member of an application group.
 *
 * The record is stored by the manager/provider, so changing from one member package to another
 * does not create another full allowance. Owner transfer is explicit: a frozen old process cannot
 * later append background time after a different member has taken over the foreground session.
 */
object SharedGroupSessionPolicy {
    const val MAX_HANDLED_SEGMENTS = 64
    const val DEFAULT_RESET_GAP_MILLIS = 30_000L

    fun update(
        existing: SharedGroupSessionRecord,
        action: SharedGroupSessionAction,
        groupVersion: Long,
        bootCount: Int,
        ownerId: String,
        expectedSessionId: String,
        generatedSessionId: String,
        segmentId: String,
        segmentMillis: Long,
        nowElapsedMillis: Long,
        resetGapMillis: Long,
    ): SharedGroupSessionUpdate {
        val now = nowElapsedMillis.coerceAtLeast(0L)
        val safeGap = resetGapMillis.coerceAtLeast(1L)
        val structurallyValid = existing.groupVersion == groupVersion &&
            existing.bootCount == bootCount &&
            existing.sessionId.isNotBlank() &&
            existing.ownerLastSeenElapsedMillis <= now &&
            existing.inactiveSinceElapsedMillis <= now
        // A process can be killed or frozen before it sends LEAVE. Treat the last owner heartbeat
        // as the inactivity boundary too, otherwise a dead owner would keep the group session alive
        // forever and every later member would inherit stale usage.
        val inactiveAt = maxOf(
            existing.inactiveSinceElapsedMillis,
            existing.ownerLastSeenElapsedMillis,
        )
        val expired = structurallyValid &&
            inactiveAt > 0L &&
            now - inactiveAt >= safeGap &&
            (
                existing.activeOwnerId.isBlank() ||
                    (
                        action == SharedGroupSessionAction.ENTER &&
                            existing.activeOwnerId != ownerId
                        )
                )
        val needsRestart = !structurallyValid || expired
        var record = if (needsRestart) {
            SharedGroupSessionRecord(
                groupVersion = groupVersion,
                bootCount = bootCount,
                sessionId = generatedSessionId,
            )
        } else {
            existing.copy(
                usedMillis = existing.usedMillis.coerceAtLeast(0L),
                handledSegmentIds = existing.handledSegmentIds
                    .asSequence()
                    .filter(String::isNotBlank)
                    .distinct()
                    .toList()
                    .takeLast(MAX_HANDLED_SEGMENTS),
            )
        }

        if (action != SharedGroupSessionAction.ENTER) {
            val stale = expectedSessionId.isBlank() || expectedSessionId != record.sessionId
            if (stale) {
                return SharedGroupSessionUpdate(
                    record = record,
                    restarted = needsRestart,
                    segmentAccepted = false,
                    ownerTransferred = false,
                    staleRequest = true,
                )
            }
        }

        val transferred = action == SharedGroupSessionAction.ENTER &&
            record.activeOwnerId.isNotBlank() &&
            record.activeOwnerId != ownerId
        if (action == SharedGroupSessionAction.ENTER) {
            record = record.copy(
                activeOwnerId = ownerId,
                ownerLastSeenElapsedMillis = now,
                inactiveSinceElapsedMillis = 0L,
            )
        }

        val safeSegmentId = segmentId.takeIf(String::isNotBlank)
        val duplicateSegment = safeSegmentId != null &&
            safeSegmentId in record.handledSegmentIds
        val canCommit = action != SharedGroupSessionAction.ENTER && !duplicateSegment
        if (canCommit) {
            val increment = segmentMillis.coerceAtLeast(0L)
            val newUsed = if (increment > Long.MAX_VALUE - record.usedMillis) {
                Long.MAX_VALUE
            } else {
                record.usedMillis + increment
            }
            val handled = record.handledSegmentIds.toMutableList()
            safeSegmentId?.let {
                handled.remove(it)
                handled.add(it)
                while (handled.size > MAX_HANDLED_SEGMENTS) handled.removeAt(0)
            }
            record = record.copy(
                usedMillis = newUsed,
                // A previous member can finish its foreground segment after the next member has
                // already entered. Count that segment, but never let the old owner extend the new
                // owner's heartbeat or clear its ownership.
                ownerLastSeenElapsedMillis = if (record.activeOwnerId == ownerId) {
                    now
                } else {
                    record.ownerLastSeenElapsedMillis
                },
                handledSegmentIds = handled,
            )
        }

        if (action == SharedGroupSessionAction.LEAVE && record.activeOwnerId == ownerId) {
            record = record.copy(
                activeOwnerId = "",
                ownerLastSeenElapsedMillis = now,
                inactiveSinceElapsedMillis = now,
            )
        }
        return SharedGroupSessionUpdate(
            record = record,
            restarted = needsRestart,
            segmentAccepted = canCommit,
            ownerTransferred = transferred,
            staleRequest = false,
        )
    }
}

object QuotaBoundaryPolicy {
    const val TOLERANCE_MILLIS = 250L

    fun normalizeRemainingMillis(remainingMillis: Long): Long =
        remainingMillis.coerceAtLeast(0L).let {
            if (it <= TOLERANCE_MILLIS) 0L else it
        }
}
