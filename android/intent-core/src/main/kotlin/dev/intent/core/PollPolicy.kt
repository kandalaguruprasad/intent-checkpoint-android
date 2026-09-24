package dev.intent.core

/**
 * Poll cadence for the detection loop (PRD §26, ADR-001). Latency is a function of this interval,
 * not a platform guarantee: expected detection delay is roughly interval/2 plus log flush time.
 */
data class PollPolicy(
    val screenOnIntervalMs: Long = 1_000,
    /** Foreground switches can't happen with the screen off; only timers need evaluating. */
    val screenOffIntervalMs: Long = 10_000,
    /** How often to re-check that Usage Access / Overlay are still granted. */
    val permissionCheckIntervalMs: Long = 10_000,
) {
    fun intervalMs(screenOn: Boolean): Long = if (screenOn) screenOnIntervalMs else screenOffIntervalMs
}
