package com.liuml.apptimelimiter.statistics

import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

data class RingUsage(val packageName: String, val durationMillis: Long)

data class UsageRingSector(
    val packageName: String?,
    val members: Set<String>,
    val durationMillis: Long,
    val startDegrees: Double,
    val sweepDegrees: Double,
) {
    val midpointDegrees: Double get() = startDegrees + sweepDegrees / 2.0
}

/** One partition drives painting, decorative icons, hit testing and Other details. */
object StatisticsRingPolicy {
    const val MAX_PACKAGE_DURATION_MILLIS: Long = 7L * 24 * 60 * 60 * 1000

    /** At most seven days per package, even when duplicate rows or corrupt Longs arrive.
     * Do not cap the cross-package total: concurrent usage can legitimately exceed seven days.
     * A List has at most Int.MAX_VALUE rows, so the sum of these bounded package durations
     * (and every subset) fits in Long: Int.MAX_VALUE * 604800000 < Long.MAX_VALUE.
     * Blank package names cannot be bound to details and are discarded, not renamed.
     */
    fun normalize(entries: List<RingUsage>): List<RingUsage> {
        val durations = mutableMapOf<String, Long>()
        entries.forEach { entry ->
            if (entry.packageName.isBlank() || entry.durationMillis <= 0L) return@forEach
            val previous = durations[entry.packageName] ?: 0L
            // Subtract before adding so even Long.MAX_VALUE cannot overflow.
            durations[entry.packageName] = previous + minOf(
                entry.durationMillis, MAX_PACKAGE_DURATION_MILLIS - previous,
            )
        }
        return durations.map { (name, duration) -> RingUsage(name, duration) }
            .sortedWith(compareByDescending<RingUsage> { it.durationMillis }.thenBy { it.packageName })
    }

    fun iconCandidates(entries: List<RingUsage>): Set<String> {
        val normalized = normalize(entries)
        val total = normalized.sumOf { it.durationMillis }
        return normalized.filter { it.durationMillis.toDouble() / total >= 0.05 }
            .mapTo(linkedSetOf()) { it.packageName }
    }

    fun sectors(
        entries: List<RingUsage>,
        iconPackages: Set<String>,
        minimumSeparationDegrees: Double = 30.0,
        maxIcons: Int = 6,
    ): List<UsageRingSector> {
        val sorted = normalize(entries)
        val total = sorted.sumOf { it.durationMillis }
        if (total <= 0L) return emptyList()
        val shown = sorted.filter {
            it.packageName in iconPackages && it.durationMillis.toDouble() / total >= 0.05
        }.take(maxIcons.coerceAtLeast(0)).toMutableList()
        // Removing a colliding icon also removes its separate arc. Recompute midpoints until
        // all remaining icons fit, including the wraparound pair at twelve o'clock.
        while (shown.size > 1) {
            var angle = -90.0
            val midpoints = shown.map {
                val sweep = it.durationMillis.toDouble() / total * 360.0
                (angle + sweep / 2.0).also { angle += sweep }
            }
            val collision = shown.indices.firstOrNull { i ->
                (0 until i).any { j ->
                    val distance = kotlin.math.abs(midpoints[i] - midpoints[j])
                    min(distance, 360.0 - distance) < minimumSeparationDegrees
                }
            } ?: break
            shown.removeAt(collision)
        }
        val shownNames = shown.map { it.packageName }.toSet()
        val other = sorted.filter { it.packageName !in shownNames }
        var start = -90.0
        val groups = shown.map { listOf(it) } + if (other.isEmpty()) emptyList() else listOf(other)
        return groups.mapIndexed { index, members ->
            val duration = members.sumOf { it.durationMillis }
            val sweep = if (index == groups.lastIndex) 270.0 - start else duration.toDouble() / total * 360.0
            UsageRingSector(
                if (index < shown.size) members.single().packageName else null,
                members.map { it.packageName }.toSet(), duration, start, sweep,
            ).also { start += sweep }
        }
    }

    /** Coordinates relative to center, with clockwise angles matching Compose drawArc.
     * Arcs are contiguous with no separator gaps; their half-open boundaries belong to the
     * following sector. The twelve-o'clock seam belongs to the first sector.
     */
    fun hitTest(sectors: List<UsageRingSector>, x: Double, y: Double, radius: Double, stroke: Double): UsageRingSector? {
        if (!radius.isFinite() || !stroke.isFinite() || radius <= 0 || stroke <= 0 ||
            !x.isFinite() || !y.isFinite()
        ) return null
        val distance = hypot(x, y)
        if (distance < radius - stroke / 2 || distance > radius + stroke / 2) return null
        val angle = ((Math.toDegrees(atan2(y, x)) + 90.0) % 360.0 + 360.0) % 360.0
        return sectors.firstOrNull { angle >= it.startDegrees + 90.0 && angle < it.startDegrees + 90.0 + it.sweepDegrees }
    }
}
