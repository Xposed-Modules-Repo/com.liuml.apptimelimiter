package com.liuml.apptimelimiter.statistics

data class UsageTransition(
    val packageName: String,
    override val timestampMillis: Long,
    val foreground: Boolean,
) : UsageTimelineEvent

data class ScreenInteractiveTransition(
    override val timestampMillis: Long,
    val interactive: Boolean,
) : UsageTimelineEvent

sealed interface UsageTimelineEvent {
    val timestampMillis: Long
}

data class CalculatedUsageSummary(
    val durationMillis: Long,
    val launchCount: Int,
    val lastUsedAtMillis: Long,
)

/** Calculates durations strictly inside [startMillis, endMillis]. */
object UsageEventDurationCalculator {
    fun calculate(
        packageNames: Collection<String>,
        startMillis: Long,
        endMillis: Long,
        transitions: List<UsageTimelineEvent>,
    ): Map<String, Long> = calculateSummaries(
        packageNames = packageNames,
        startMillis = startMillis,
        endMillis = endMillis,
        transitions = transitions,
    ).mapValues { it.value.durationMillis }

    fun calculateSummaries(
        packageNames: Collection<String>,
        startMillis: Long,
        endMillis: Long,
        transitions: List<UsageTimelineEvent>,
    ): Map<String, CalculatedUsageSummary> {
        val tracked = packageNames.toSet()
        if (tracked.isEmpty() || endMillis <= startMillis) {
            return tracked.associateWith {
                CalculatedUsageSummary(0L, 0, 0L)
            }
        }
        val foreground = tracked.associateWith { false }.toMutableMap()
        val startedAt = mutableMapOf<String, Long>()
        val durations = tracked.associateWith { 0L }.toMutableMap()
        val launchCounts = tracked.associateWith { 0 }.toMutableMap()
        val lastUsedAt = tracked.associateWith { 0L }.toMutableMap()
        var interactive = true
        var boundaryInitialized = false

        fun initializeBoundary() {
            if (boundaryInitialized) return
            if (interactive) {
                foreground.filterValues { it }.keys.forEach { packageName ->
                    startedAt[packageName] = startMillis
                    // A session carried across midnight still contributes one use session today.
                    launchCounts[packageName] = 1
                    lastUsedAt[packageName] = startMillis
                }
            }
            boundaryInitialized = true
        }

        fun stopCounting(packageName: String, timestampMillis: Long) {
            val started = startedAt.remove(packageName) ?: return
            durations[packageName] = durations.getValue(packageName) +
                (timestampMillis - started).coerceAtLeast(0L)
        }

        fun applyTransition(transition: UsageTimelineEvent, beforeBoundary: Boolean) {
            when (transition) {
                is UsageTransition -> {
                    val packageName = transition.packageName
                    if (packageName !in tracked) return
                    if (transition.foreground) {
                        if (foreground[packageName] != true) {
                            foreground[packageName] = true
                            if (!beforeBoundary && interactive) {
                                val started = transition.timestampMillis.coerceAtLeast(startMillis)
                                startedAt[packageName] = started
                                launchCounts[packageName] = launchCounts.getValue(packageName) + 1
                                lastUsedAt[packageName] = maxOf(lastUsedAt.getValue(packageName), started)
                            }
                        }
                    } else if (foreground[packageName] == true) {
                        if (!beforeBoundary) stopCounting(packageName, transition.timestampMillis)
                        foreground[packageName] = false
                    }
                }

                is ScreenInteractiveTransition -> {
                    if (interactive == transition.interactive) return
                    if (!beforeBoundary) {
                        if (transition.interactive) {
                            foreground.filterValues { it }.keys.forEach { packageName ->
                                startedAt[packageName] = transition.timestampMillis
                                    .coerceAtLeast(startMillis)
                            }
                        } else {
                            foreground.filterValues { it }.keys.forEach { packageName ->
                                stopCounting(packageName, transition.timestampMillis)
                            }
                        }
                    }
                    interactive = transition.interactive
                }
            }
        }

        transitions.asSequence()
            .sortedBy(UsageTimelineEvent::timestampMillis)
            .forEach { transition ->
                if (transition.timestampMillis < startMillis) {
                    applyTransition(transition, beforeBoundary = true)
                    return@forEach
                }
                initializeBoundary()
                if (transition.timestampMillis > endMillis) return@forEach
                applyTransition(transition, beforeBoundary = false)
            }

        initializeBoundary()
        startedAt.keys.toList().forEach { packageName ->
            val started = startedAt[packageName] ?: startMillis
            durations[packageName] = durations.getValue(packageName) +
                (endMillis - started).coerceAtLeast(0L)
        }
        val maxDuration = endMillis - startMillis
        return tracked.associateWith { packageName ->
            val duration = durations.getValue(packageName).coerceIn(0L, maxDuration)
            CalculatedUsageSummary(
                durationMillis = duration,
                // Some OEMs omit the first foreground event while still exposing a valid interval.
                launchCount = if (duration > 0L) {
                    launchCounts.getValue(packageName).coerceAtLeast(1)
                } else {
                    launchCounts.getValue(packageName)
                },
                lastUsedAtMillis = lastUsedAt.getValue(packageName),
            )
        }
    }
}
