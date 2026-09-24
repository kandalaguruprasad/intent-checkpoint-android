package dev.intent.core

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LatencyStatsTest {
    @Test
    fun `empty input has no summary`() {
        val s = LatencySummary.of(emptyList())
        assertEquals(0, s.count)
        assertNull(s.medianMs)
        assertNull(s.meetsGoThreshold())
    }

    @Test
    fun `nearest-rank percentiles`() {
        val s = LatencySummary.of((1L..20L).map { it * 100 })
        assertEquals(1_000, s.medianMs)
        assertEquals(1_900, s.p95Ms)
        assertEquals(2_000, s.maxMs)
        assertEquals(true, s.meetsGoThreshold())
    }

    @Test
    fun `go threshold fails on slow tail`() {
        val s = LatencySummary.of(List(18) { 800L } + listOf(4_500L, 5_000L))
        assertEquals(800, s.medianMs)
        assertEquals(4_500, s.p95Ms)
        assertEquals(false, s.meetsGoThreshold())
    }
}
