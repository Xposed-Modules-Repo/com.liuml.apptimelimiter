package com.liuml.apptimelimiter.core

import java.time.ZonedDateTime

enum class RestrictionReason(val quotaKind: QuotaKind? = null) {
    SCHEDULE, COOLDOWN,
    APP_DAILY(QuotaKind.APP_DAILY), GROUP_DAILY(QuotaKind.GROUP_DAILY),
    APP_PER_LAUNCH(QuotaKind.APP_PER_LAUNCH), GROUP_PER_LAUNCH(QuotaKind.GROUP_PER_LAUNCH),
    SESSION_PLAN,
}

/** Display evidence only; it never participates in gate or quota decisions. */
data class RuleUsageMeasurement(val usedMillis: Long?, val limitMillis: Long?) {
    init {
        require(usedMillis == null || usedMillis >= 0L)
        require(limitMillis == null || limitMillis > 0L)
    }

    companion object {
        fun fromProvider(usedMillis: Long, limitSeconds: Long, minSeconds: Long, maxSeconds: Long): RuleUsageMeasurement? {
            val used = usedMillis.takeIf { it >= 0L }
            val limit = limitSeconds.takeIf {
                it > 0L && it in minSeconds..maxSeconds && it <= Long.MAX_VALUE / 1000L
            }?.times(1000L)
            return if (used == null && limit == null) null else RuleUsageMeasurement(used, limit)
        }
    }
}

/** One evaluation's values, never a cache across callbacks or rule versions. Null means disabled. */
data class RuleDecisionSnapshot(
    val scheduleBlocked: Boolean = false,
    val cooldownRemainingMillis: Long = 0L,
    val appDailyRemainingMillis: Long? = null,
    val appPerLaunchRemainingMillis: Long? = null,
    val groupDailyRemainingMillis: Long? = null,
    val groupPerLaunchRemainingMillis: Long? = null,
    val planRemainingMillis: Long? = null,
    val grouped: Boolean = false,
    val availabilityKnown: Boolean = true,
    val appDailyMeasurement: RuleUsageMeasurement? = null,
    val groupDailyMeasurement: RuleUsageMeasurement? = null,
) {
    private fun quotaRemaining(reason: RestrictionReason): Long? = when (reason) {
        RestrictionReason.APP_DAILY -> appDailyRemainingMillis.takeUnless { grouped }
        RestrictionReason.APP_PER_LAUNCH -> appPerLaunchRemainingMillis.takeUnless { grouped }
        RestrictionReason.GROUP_DAILY -> groupDailyRemainingMillis
        RestrictionReason.GROUP_PER_LAUNCH -> groupPerLaunchRemainingMillis
        else -> null
    }

    val reasons: List<RestrictionReason> = java.util.Collections.unmodifiableList(
        RestrictionReason.values().filter { reason ->
            when (reason) {
                RestrictionReason.SCHEDULE -> scheduleBlocked
                RestrictionReason.COOLDOWN -> cooldownRemainingMillis > 0L
                RestrictionReason.SESSION_PLAN -> planRemainingMillis?.let { it <= 0L } == true
                else -> quotaRemaining(reason)?.let { it <= 0L } == true
            }
        },
    )
    val primaryReason: RestrictionReason? get() = reasons.firstOrNull()
    val reachedKinds: Set<QuotaKind> = java.util.Collections.unmodifiableSet(reasons.mapNotNull { it.quotaKind }.toSet())
    val gate: LimitGateDecision = LimitEnforcementPolicy.evaluate(
        LimitGateSnapshot(scheduleBlocked, cooldownRemainingMillis, reachedKinds.isNotEmpty()),
    )
    val quotaRemainingMillis: Long? = RestrictionReason.values().mapNotNull(::quotaRemaining).minOrNull()
    val nextThresholdMillis: Long? = if (primaryReason != null) null else
            listOfNotNull(quotaRemainingMillis, planRemainingMillis).minOrNull()
    val planIsNextThreshold: Boolean = primaryReason == null &&
        planRemainingMillis != null &&
        (quotaRemainingMillis == null || planRemainingMillis < checkNotNull(quotaRemainingMillis))
}

object RuleAvailabilityPolicy {
    /** A forecast with no further usage. Unknown session/plan reset must never promise a time. */
    fun nextAvailable(
        snapshot: RuleDecisionSnapshot,
        now: ZonedDateTime,
        schedules: List<ScheduleConstraint>,
        sessionResetAt: ZonedDateTime? = null,
    ): ZonedDateTime? {
        val reasons = snapshot.reasons
        if (!snapshot.availabilityKnown) return null
        if (RestrictionReason.SESSION_PLAN in reasons) return null
        val sessionBlocked = reasons.any {
            it == RestrictionReason.APP_PER_LAUNCH || it == RestrictionReason.GROUP_PER_LAUNCH
        }
        if (sessionBlocked && sessionResetAt == null) return null
        var candidate = now
        if (snapshot.cooldownRemainingMillis > 0L) {
            candidate = runCatching { now.plusNanos(Math.multiplyExact(snapshot.cooldownRemainingMillis, 1_000_000L)) }
                .getOrNull() ?: return null
        }
        if (reasons.any { it == RestrictionReason.APP_DAILY || it == RestrictionReason.GROUP_DAILY }) {
            val midnight = now.toLocalDate().plusDays(1).atStartOfDay(now.zone)
            if (midnight.isAfter(candidate)) candidate = midnight
        }
        if (sessionBlocked && checkNotNull(sessionResetAt).isAfter(candidate)) candidate = sessionResetAt
        // Re-evaluate at the latest reset, not at the original schedule transition.
        val schedule = ScheduleEvaluator.evaluateAll(schedules, candidate)
        if (snapshot.scheduleBlocked && schedules.isEmpty()) return null
        if (schedule.allowed) return candidate
        // Keep the availability contract explicit even if transition semantics change later.
        return schedule.nextTransition?.takeIf {
            ScheduleEvaluator.evaluateAll(schedules, it).allowed
        }
    }
}

object RestrictionReasonText {
    fun label(reason: RestrictionReason, english: Boolean): String = if (english) when (reason) {
        RestrictionReason.SCHEDULE -> "Outside available hours"
        RestrictionReason.COOLDOWN -> "Cooldown is active"
        RestrictionReason.APP_DAILY -> "App daily allowance exhausted"
        RestrictionReason.GROUP_DAILY -> "Group daily allowance exhausted"
        RestrictionReason.APP_PER_LAUNCH -> "App session allowance exhausted"
        RestrictionReason.GROUP_PER_LAUNCH -> "Group session allowance exhausted"
        RestrictionReason.SESSION_PLAN -> "This session's plan has ended"
    } else when (reason) {
        RestrictionReason.SCHEDULE -> "当前不在可用时段"
        RestrictionReason.COOLDOWN -> "冷却尚未结束"
        RestrictionReason.APP_DAILY -> "个人每日额度已耗尽"
        RestrictionReason.GROUP_DAILY -> "分组每日共享额度已耗尽"
        RestrictionReason.APP_PER_LAUNCH -> "个人单次额度已耗尽"
        RestrictionReason.GROUP_PER_LAUNCH -> "分组单次共享额度已耗尽"
        RestrictionReason.SESSION_PLAN -> "本次使用计划已到期"
    }

    fun details(snapshot: RuleDecisionSnapshot, english: Boolean): String = buildList {
        addAll(snapshot.reasons.mapIndexed { index, reason ->
            val prefix = if (english) {
                if (index == 0) "Main reason: " else "Also restricted: "
            } else if (index == 0) "主要原因：" else "其他限制："
            prefix + label(reason, english)
        })
        if (!snapshot.grouped) snapshot.appDailyMeasurement?.let {
            add(measurementText(it, if (english) "App daily (system recorded)" else "个人每日（系统记录）", english))
        }
        snapshot.groupDailyMeasurement?.let {
            add(measurementText(it, if (english) "Group daily (shared total)" else "分组每日（共享累计）", english))
        }
        if (snapshot.cooldownRemainingMillis > 0L) {
            add((if (english) "Cooldown remaining: " else "剩余冷却：") + duration(snapshot.cooldownRemainingMillis, english))
        }
    }.joinToString("\n")

    private fun measurementText(measurement: RuleUsageMeasurement, name: String, english: Boolean): String {
        val values = listOfNotNull(
            measurement.usedMillis?.let { (if (english) "used " else "已用 ") + duration(it, english) },
            measurement.limitMillis?.let { (if (english) "allowance " else "额度 ") + duration(it, english) },
        )
        return "$name: ${values.joinToString(" / ")}"
    }

    private fun duration(millis: Long, english: Boolean): String {
        val seconds = millis / 1000L + if (millis % 1000L > 0L) 1L else 0L
        val hours = seconds / 3600L
        val minutes = seconds % 3600L / 60L
        val remainder = seconds % 60L
        return if (english) {
            if (hours > 0L) "${hours}h ${minutes}m ${remainder}s" else "${minutes}m ${remainder}s"
        } else {
            if (hours > 0L) "${hours}小时${minutes}分${remainder}秒" else "${minutes}分${remainder}秒"
        }
    }
}
