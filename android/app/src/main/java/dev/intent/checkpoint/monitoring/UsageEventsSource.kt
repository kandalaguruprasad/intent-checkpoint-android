package dev.intent.checkpoint.monitoring

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import dev.intent.core.ForegroundEvent

/**
 * Reads the UsageEvents log. There is no callback API for this (PRD §26) — it is queried on each
 * poll for the window since the tracker's cursor. Returns only the most recent resume in the
 * window: the tracker never needs more, and it keeps the long first-poll look-back allocation-free.
 */
class UsageEventsSource(context: Context) {
    private val usm = context.getSystemService(UsageStatsManager::class.java)

    fun latestResume(beginMs: Long, endMs: Long, ignored: Set<String>): ForegroundEvent? {
        val manager = usm ?: return null
        // Returns an empty log (not an exception) when Usage Access is not granted.
        val events = manager.queryEvents(beginMs, endMs) ?: return null
        val e = UsageEvents.Event()
        var latestPkg: String? = null
        var latestTs = Long.MIN_VALUE
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            // MOVE_TO_FOREGROUND == ACTIVITY_RESUMED (1); the old name works on every minSdk.
            @Suppress("DEPRECATION")
            if (e.eventType != UsageEvents.Event.MOVE_TO_FOREGROUND) continue
            val pkg = e.packageName ?: continue
            if (pkg in ignored) continue
            if (e.timeStamp >= latestTs) {
                latestTs = e.timeStamp
                latestPkg = pkg
            }
        }
        return latestPkg?.let { ForegroundEvent(it, latestTs) }
    }
}
