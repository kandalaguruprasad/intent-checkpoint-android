package dev.intent.core

/**
 * Today-screen numbers, derived only from persisted sessions (PRD §50). Nothing here is
 * estimated; there is deliberately no "time saved".
 */
data class DailySummary(
    /** Every checkpoint Intent showed (each opening attempt creates one session row). */
    val opensNoticed: Int,
    /** Openings where the user committed to an intention. */
    val intentionalSessions: Int,
    /** "Not now", or left from the checkpoint without starting. */
    val choseNotToOpen: Int,
    /** Planned seconds of sessions that had a timer. */
    val plannedSeconds: Long,
    /** Actual foreground seconds of those same timed sessions, so the bars compare like with like. */
    val actualSecondsTimed: Long,
    /** Actual foreground seconds across all sessions, including "No timer". */
    val actualSecondsAll: Long,
    val extensions: Int,
    /** Ended without an extension and within the planned time. */
    val finishedOnTime: Int,
    val perApp: List<AppDay>,
) {
    data class AppDay(val packageName: String, val appName: String, val opens: Int, val actualSeconds: Long)

    companion object {
        fun of(sessions: List<Session>, nowMs: Long): DailySummary {
            val started = sessions.filter { it.startedAt != null }
            val timed = started.filter { it.plannedDurationSeconds != null }
            return DailySummary(
                opensNoticed = sessions.size,
                intentionalSessions = started.size,
                choseNotToOpen = sessions.count { it.state == SessionState.SESSION_ABANDONED && it.startedAt == null },
                plannedSeconds = timed.sumOf { it.plannedDurationSeconds!! },
                actualSecondsTimed = timed.sumOf { it.foregroundMsAt(nowMs) / 1000 },
                actualSecondsAll = started.sumOf { it.foregroundMsAt(nowMs) / 1000 },
                extensions = sessions.sumOf { it.extensionCount },
                finishedOnTime = timed.count {
                    it.state.isTerminal && it.extensionCount == 0 &&
                        (it.wallClockSeconds ?: Long.MAX_VALUE) <= it.plannedDurationSeconds!!
                },
                perApp = sessions.groupBy { it.packageName }.map { (pkg, list) ->
                    AppDay(
                        packageName = pkg,
                        appName = list.maxBy { it.createdAt }.appName,
                        opens = list.size,
                        actualSeconds = list.sumOf { it.foregroundMsAt(nowMs) / 1000 },
                    )
                }.sortedWith(compareByDescending<AppDay> { it.opens }.thenBy { it.appName }),
            )
        }
    }
}
