package dev.intent.core

/** One "activity resumed" entry from the UsageEvents log. [timestampMs] is wall-clock. */
data class ForegroundEvent(val packageName: String, val timestampMs: Long)

/** A foreground change derived from the event log, with how late we noticed it. */
data class ForegroundChange(
    val packageName: String?,
    val eventTimestampMs: Long,
    val detectedAtMs: Long,
    /** Found by the first poll's look-back, not observed live: not a latency sample. */
    val fromInitialLookback: Boolean = false,
) {
    val detectionLatencyMs: Long get() = (detectedAtMs - eventTimestampMs).coerceAtLeast(0)
}

/**
 * Turns the UsageEvents log (a query-on-demand log with no push API, PRD §26) into a stream of
 * distinct foreground changes.
 *
 * - Keeps a cursor so each poll only asks for the window since the last processed event.
 * - Collapses repeated resumes of the same package (multi-activity apps, config changes,
 *   multi-window) so the engine only ever sees real switches.
 * - Ignores packages in [ignored] (our own overlay host never produces activity events, but our
 *   own UI does, and it must count as leaving the target app — so it is NOT ignored by default).
 */
class ForegroundTracker(
    private val initialLookbackMs: Long = 5 * 60_000,
    /**
     * How far behind "now" the cursor trails when the log is quiet. UsageEvents can be flushed
     * slightly after their timestamp; this keeps late entries inside the next window while
     * bounding the query to a few seconds instead of growing for as long as one app stays open.
     */
    private val flushMarginMs: Long = 10_000,
    private val ignored: Set<String> = emptySet(),
) {
    var currentPackage: String? = null
        private set

    private var cursorMs: Long? = null

    /** Start of the window to query on this poll. Inclusive: duplicates are filtered below. */
    fun queryWindowStart(nowMs: Long): Long = cursorMs ?: (nowMs - initialLookbackMs)

    /**
     * Process events returned for `[queryWindowStart, nowMs]`. Returns the latest change, or null
     * when the foreground package is unchanged. Intermediate switches inside one poll window
     * (A -> B -> A within a second) are collapsed to the final state on purpose.
     */
    fun process(events: List<ForegroundEvent>, nowMs: Long): ForegroundChange? {
        val start = cursorMs
        val latest = events
            .asSequence()
            .filter { start == null || it.timestampMs >= start }
            .filter { it.packageName !in ignored }
            .maxByOrNull { it.timestampMs }
        cursorMs = maxOf(start ?: Long.MIN_VALUE, latest?.timestampMs ?: Long.MIN_VALUE, nowMs - flushMarginMs)
        if (latest == null || latest.packageName == currentPackage) return null
        currentPackage = latest.packageName
        return ForegroundChange(latest.packageName, latest.timestampMs, nowMs, fromInitialLookback = start == null)
    }

    /**
     * Screen went off / device locked. Nothing is in front; when the screen comes back the
     * top activity is resumed again and shows up as a fresh change.
     */
    fun onScreenOff(nowMs: Long): ForegroundChange? {
        // Only resumes that happen after this point may count as a return; otherwise the next
        // poll would re-read the pre-screen-off resume and "return" while the screen is dark.
        cursorMs = maxOf(cursorMs ?: Long.MIN_VALUE, nowMs)
        if (currentPackage == null) return null
        currentPackage = null
        return ForegroundChange(null, nowMs, nowMs)
    }
}
