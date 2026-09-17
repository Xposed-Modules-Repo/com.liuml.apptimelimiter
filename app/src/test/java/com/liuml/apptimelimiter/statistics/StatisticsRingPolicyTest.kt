package com.liuml.apptimelimiter.statistics

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class StatisticsRingPolicyTest {
    @Test fun `extreme duplicates saturate per package without wrapping`() {
        val cap = StatisticsRingPolicy.MAX_PACKAGE_DURATION_MILLIS
        val entries = listOf(
            RingUsage("a", Long.MAX_VALUE), RingUsage("a", Long.MAX_VALUE),
            RingUsage("b", Long.MAX_VALUE), RingUsage("b", 1),
        )
        val normalized = StatisticsRingPolicy.normalize(entries)
        assertEquals(listOf(RingUsage("a", cap), RingUsage("b", cap)), normalized)
        assertEquals(normalized, StatisticsRingPolicy.normalize(entries.reversed()))
        assertEquals(normalized, StatisticsRingPolicy.normalize(normalized))
        val sectors = StatisticsRingPolicy.sectors(entries, setOf("a"))
        assertEquals(2 * cap, sectors.sumOf { it.durationMillis })
        assertEquals(setOf("b"), sectors.last().members)
        assertEquals(180.0, sectors.first().sweepDegrees, 0.0)
        assertEquals(180.0, sectors.last().sweepDegrees, 0.0)
    }

    @Test fun `many maximal durations keep exact total and other sum bounded`() {
        val cap = StatisticsRingPolicy.MAX_PACKAGE_DURATION_MILLIS
        val entries = (1..1000).map { RingUsage("app$it", Long.MAX_VALUE) }
        val sectors = StatisticsRingPolicy.sectors(entries, entries.map { it.packageName }.toSet())
        assertEquals(1000L * cap, sectors.single().durationMillis)
        assertEquals(1000, sectors.single().members.size)
        assertNull(sectors.single().packageName)
        assertEquals(360.0, sectors.single().sweepDegrees, 0.0)
        assertTrue(StatisticsRingPolicy.iconCandidates(entries).isEmpty())
    }

    @Test fun `blank names and nonpositive durations cannot become sectors or icons`() {
        val entries = listOf(
            RingUsage("", Long.MAX_VALUE), RingUsage(" \t\n", Long.MAX_VALUE),
            RingUsage("negative", Long.MIN_VALUE), RingUsage("zero", 0),
            RingUsage("valid", 20), RingUsage("valid", -10), RingUsage("valid", 30),
        )
        assertEquals(listOf(RingUsage("valid", 50)), StatisticsRingPolicy.normalize(entries))
        assertEquals(setOf("valid"), StatisticsRingPolicy.iconCandidates(entries))
        val sectors = StatisticsRingPolicy.sectors(entries, entries.map { it.packageName }.toSet())
        assertEquals("valid", sectors.single().packageName)
        assertEquals(setOf("valid"), sectors.single().members)
        assertEquals(50L, sectors.single().durationMillis)
        assertTrue(StatisticsRingPolicy.sectors(entries.take(4), emptySet()).isEmpty())
        assertTrue(StatisticsRingPolicy.iconCandidates(emptyList()).isEmpty())
    }

    @Test fun `full seven days remain exact and total is not capped across apps`() {
        val cap = StatisticsRingPolicy.MAX_PACKAGE_DURATION_MILLIS
        val day = cap / 7
        val entries = (1..7).flatMap { listOf(RingUsage("a", day), RingUsage("b", day)) }
        val sectors = StatisticsRingPolicy.sectors(entries, setOf("a", "b"))
        assertEquals(listOf(cap, cap), sectors.map { it.durationMillis })
        assertEquals(2 * cap, sectors.sumOf { it.durationMillis })
        assertEquals(setOf("a", "b"), StatisticsRingPolicy.iconCandidates(entries))
        assertEquals(listOf(RingUsage("a", cap)), StatisticsRingPolicy.normalize(
            listOf(RingUsage("a", cap - 1), RingUsage("a", 1), RingUsage("a", Long.MAX_VALUE)),
        ))
        assertEquals(listOf(RingUsage("a", cap - 1)), StatisticsRingPolicy.normalize(listOf(RingUsage("a", cap - 1))))
    }

    @Test fun `continuous painted arcs have no separator dead zones on either side`() {
        val sectors = StatisticsRingPolicy.sectors(
            listOf(RingUsage("a", 50), RingUsage("b", 25), RingUsage("other", 25)), setOf("a", "b"),
        )
        fun hit(angle: Double): UsageRingSector? {
            val radians = Math.toRadians(angle)
            return StatisticsRingPolicy.hitTest(sectors, cos(radians) * 100, sin(radians) * 100, 100.0, 20.0)
        }
        sectors.forEachIndexed { index, sector ->
            val previous = sectors[(index + sectors.size - 1) % sectors.size]
            assertEquals(previous, hit(sector.startDegrees - 0.001))
            assertEquals(sector, hit(sector.startDegrees + 0.001))
            assertEquals(sector, hit(sector.startDegrees))
        }
        for (angle in -90 until 270) assertNotNull(hit(angle + 0.5))
    }

    @Test fun `invalid geometry never selects a sector`() {
        val sectors = StatisticsRingPolicy.sectors(listOf(RingUsage("a", 10)), setOf("a"))
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.0, -1.0)) {
            assertNull(StatisticsRingPolicy.hitTest(sectors, 100.0, 0.0, invalid, 20.0))
            assertNull(StatisticsRingPolicy.hitTest(sectors, 100.0, 0.0, 100.0, invalid))
        }
        assertNull(StatisticsRingPolicy.hitTest(sectors, Double.NaN, 0.0, 100.0, 20.0))
        assertNull(StatisticsRingPolicy.hitTest(sectors, 0.0, Double.POSITIVE_INFINITY, 100.0, 20.0))
    }

    @Test fun `missing icons and small shares are exactly the other members`() {
        val entries = listOf(RingUsage("a", 70), RingUsage("missing", 26), RingUsage("tiny", 4))
        val sectors = StatisticsRingPolicy.sectors(entries, setOf("a", "tiny"))
        assertEquals(listOf("a", null), sectors.map { it.packageName })
        assertEquals(setOf("missing", "tiny"), sectors.last().members)
        assertEquals(30L, sectors.last().durationMillis)
        assertEquals(100L, sectors.sumOf { it.durationMillis })
        assertEquals(360.0, sectors.sumOf { it.sweepDegrees }, 0.000001)
    }

    @Test fun `icons omitted for collision are merged and midpoints recalculated`() {
        val entries = listOf(RingUsage("a", 80), RingUsage("b", 10), RingUsage("c", 10))
        val sectors = StatisticsRingPolicy.sectors(entries, entries.map { it.packageName }.toSet(), 45.0)
        assertEquals(listOf("a", "b", null), sectors.map { it.packageName })
        assertEquals(setOf("c"), sectors.last().members)
        assertEquals(100L, sectors.sumOf { it.durationMillis })
    }

    @Test fun `ties are package stable and input reordering does not change binding`() {
        val entries = (1..8).map { RingUsage("app$it", 100) }
        val icons = entries.map { it.packageName }.toSet()
        val sectors = StatisticsRingPolicy.sectors(entries, icons)
        assertEquals(sectors, StatisticsRingPolicy.sectors(entries.reversed(), icons))
        assertEquals(setOf("app7", "app8"), sectors.last().members)
        assertEquals(800L, sectors.sumOf { it.durationMillis })
    }

    @Test fun `every painted sector midpoint hits the same package or other`() {
        val entries = listOf(RingUsage("a", 50), RingUsage("b", 30), RingUsage("missing", 20))
        val sectors = StatisticsRingPolicy.sectors(entries, setOf("a", "b"))
        sectors.forEach { sector ->
            val angle = Math.toRadians(sector.midpointDegrees)
            assertEquals(sector, StatisticsRingPolicy.hitTest(sectors, cos(angle) * 100, sin(angle) * 100, 100.0, 20.0))
        }
        assertNull(StatisticsRingPolicy.hitTest(sectors, 0.0, 0.0, 100.0, 20.0))
        assertNull(StatisticsRingPolicy.hitTest(sectors, 140.0, 0.0, 100.0, 20.0))
        assertNull(StatisticsRingPolicy.hitTest(sectors, 89.9, 0.0, 100.0, 20.0))
        assertNull(StatisticsRingPolicy.hitTest(sectors, 110.1, 0.0, 100.0, 20.0))
    }

    @Test fun `boundary belongs to following sector and top wraps to first`() {
        val sectors = StatisticsRingPolicy.sectors(listOf(RingUsage("a", 50), RingUsage("b", 50)), setOf("a", "b"))
        assertEquals("a", StatisticsRingPolicy.hitTest(sectors, 0.0, -100.0, 100.0, 20.0)?.packageName)
        assertEquals("b", StatisticsRingPolicy.hitTest(sectors, 0.0, 100.0, 100.0, 20.0)?.packageName)
    }

    @Test fun `all missing makes one complete other ring and zero makes no hit`() {
        val sectors = StatisticsRingPolicy.sectors(listOf(RingUsage("missing", 10)), emptySet())
        assertNull(sectors.single().packageName)
        assertEquals(360.0, sectors.single().sweepDegrees, 0.0)
        assertEquals(sectors.single(), StatisticsRingPolicy.hitTest(sectors, 100.0, 0.0, 100.0, 20.0))
        val empty = StatisticsRingPolicy.sectors(listOf(RingUsage("zero", 0)), setOf("zero"))
        assertTrue(empty.isEmpty())
        assertNull(StatisticsRingPolicy.hitTest(empty, 100.0, 0.0, 100.0, 20.0))
    }

    @Test fun `weekly duplicate packages keep all seven days and bind once`() {
        val entries = (1..7).flatMap { listOf(RingUsage("a", 100), RingUsage("missing", 20)) }
        val sectors = StatisticsRingPolicy.sectors(entries, setOf("a"))
        assertEquals(700L, sectors.first().durationMillis)
        assertEquals(140L, sectors.last().durationMillis)
        assertEquals(840L, sectors.sumOf { it.durationMillis })
        assertEquals(setOf("missing"), sectors.last().members)
    }
}
