package dev.intent.core

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ForegroundTrackerTest {
    private val t0 = 1_700_000_000_000L

    @Test
    fun `first poll uses look-back and is flagged as not a latency sample`() {
        val tracker = ForegroundTracker(initialLookbackMs = 60_000)
        assertEquals(t0 - 60_000, tracker.queryWindowStart(t0))
        val change = tracker.process(listOf(ForegroundEvent("a", t0 - 30_000)), t0)!!
        assertEquals("a", change.packageName)
        assertTrue(change.fromInitialLookback)
    }

    @Test
    fun `live change reports latency from the event timestamp`() {
        val tracker = ForegroundTracker()
        tracker.process(emptyList(), t0)
        val change = tracker.process(listOf(ForegroundEvent("ig", t0 + 400)), t0 + 1_300)!!
        assertFalse(change.fromInitialLookback)
        assertEquals(900, change.detectionLatencyMs)
    }

    @Test
    fun `duplicate resumes of the same package are collapsed`() {
        val tracker = ForegroundTracker()
        tracker.process(emptyList(), t0)
        assertEquals("ig", tracker.process(listOf(ForegroundEvent("ig", t0 + 100)), t0 + 1_000)?.packageName)
        assertNull(tracker.process(listOf(ForegroundEvent("ig", t0 + 100), ForegroundEvent("ig", t0 + 1_500)), t0 + 2_000))
        assertNull(tracker.process(listOf(ForegroundEvent("ig", t0 + 1_500)), t0 + 3_000))
    }

    @Test
    fun `latest event in the window wins`() {
        val tracker = ForegroundTracker()
        tracker.process(emptyList(), t0)
        val c = tracker.process(
            listOf(ForegroundEvent("b", t0 + 900), ForegroundEvent("a", t0 + 200)),
            t0 + 1_000,
        )
        assertEquals("b", c?.packageName)
    }

    @Test
    fun `cursor advances so already-processed events are not replayed`() {
        val tracker = ForegroundTracker(flushMarginMs = 10_000)
        tracker.process(emptyList(), t0)
        tracker.process(listOf(ForegroundEvent("a", t0 + 100)), t0 + 1_000)
        tracker.process(listOf(ForegroundEvent("b", t0 + 1_500)), t0 + 2_000)
        assertEquals(t0 + 1_500, tracker.queryWindowStart(t0 + 3_000))
        // a stale "a" at +100 is before the cursor and must not flip us back
        assertNull(tracker.process(listOf(ForegroundEvent("a", t0 + 100), ForegroundEvent("b", t0 + 1_500)), t0 + 3_000))
    }

    @Test
    fun `quiet log keeps the query window bounded`() {
        val tracker = ForegroundTracker(flushMarginMs = 10_000)
        tracker.process(listOf(ForegroundEvent("a", t0)), t0)
        tracker.process(emptyList(), t0 + 3_600_000)
        assertEquals(t0 + 3_590_000, tracker.queryWindowStart(t0 + 3_600_000))
    }

    @Test
    fun `screen off reports null and ignores the pre-screen-off resume afterwards`() {
        val tracker = ForegroundTracker()
        tracker.process(emptyList(), t0)
        tracker.process(listOf(ForegroundEvent("ig", t0 + 100)), t0 + 1_000)
        val off = tracker.onScreenOff(t0 + 2_000)!!
        assertNull(off.packageName)
        assertNull(tracker.onScreenOff(t0 + 2_500), "already off")
        assertNull(tracker.process(listOf(ForegroundEvent("ig", t0 + 100)), t0 + 3_000))
        assertEquals("ig", tracker.process(listOf(ForegroundEvent("ig", t0 + 5_000)), t0 + 5_500)?.packageName)
    }

    @Test
    fun `ignored packages never become foreground`() {
        val tracker = ForegroundTracker(ignored = setOf("com.android.systemui"))
        tracker.process(emptyList(), t0)
        assertNull(tracker.process(listOf(ForegroundEvent("com.android.systemui", t0 + 10)), t0 + 100))
    }
}
