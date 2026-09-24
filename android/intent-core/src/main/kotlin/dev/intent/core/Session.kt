package dev.intent.core

/**
 * Persisted session states (PRD §16). IDLE and TARGET_DETECTED are never persisted — they are
 * evaluated inside a single engine tick. TIMER_WARNING is purely cosmetic and derived from
 * `plannedEndAt` at render time (see [warningLevel]), so it is not stored either.
 */
enum class SessionState {
    PAUSE,
    AWAITING_INTENTION,
    SESSION_ACTIVE,
    BACKGROUND_GRACE,
    TIME_EXPIRED,
    EXTENSION_REQUEST,
    SESSION_COMPLETED,
    SESSION_ABANDONED;

    val isTerminal: Boolean
        get() = this == SESSION_COMPLETED || this == SESSION_ABANDONED

    /** Checkpoint shown but the user has not committed to a session yet. */
    val isCheckpoint: Boolean
        get() = this == PAUSE || this == AWAITING_INTENTION

    /** Timer ran out and the reconsideration checkpoint is unresolved. */
    val isExpiredCheckpoint: Boolean
        get() = this == TIME_EXPIRED || this == EXTENSION_REQUEST
}

enum class CompletionReason(val wire: String) {
    DONE("done"),
    LEFT("left"),
    ABANDONED("abandoned"),
    DEVICE_RESTARTED("device_restarted");

    companion object {
        fun fromWire(value: String?): CompletionReason? = entries.firstOrNull { it.wire == value }
    }
}

enum class WarningLevel { NONE, TWO_MINUTES, THIRTY_SECONDS }

data class Session(
    val id: String,
    val packageName: String,
    val appName: String,
    val intention: String,
    val startedAt: Long?,
    val plannedDurationSeconds: Long?,
    val plannedEndAt: Long?,
    val endedAt: Long?,
    val wallClockSeconds: Long?,
    val extensionCount: Int,
    val state: SessionState,
    val completionReason: CompletionReason?,
    /** When the target app last left the foreground; only meaningful in BACKGROUND_GRACE. */
    val backgroundedAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
) {
    fun remainingMs(nowMs: Long): Long? = plannedEndAt?.let { (it - nowMs).coerceAtLeast(0) }

    fun warningLevel(nowMs: Long): WarningLevel {
        val remaining = remainingMs(nowMs) ?: return WarningLevel.NONE
        return when {
            remaining <= 30_000 -> WarningLevel.THIRTY_SECONDS
            remaining <= 120_000 -> WarningLevel.TWO_MINUTES
            else -> WarningLevel.NONE
        }
    }

    /** Intention text is treated like a password field: never rendered into logs (PRD §66). */
    override fun toString(): String =
        "Session(id=$id, pkg=$packageName, state=$state, intention=[REDACTED], " +
            "startedAt=$startedAt, plannedEndAt=$plannedEndAt, endedAt=$endedAt, " +
            "extensions=$extensionCount, reason=${completionReason?.wire})"

    companion object {
        const val UNSPECIFIED_INTENTION = "(unspecified)"
    }
}

data class SessionExtension(
    val id: String,
    val sessionId: String,
    val reason: String?,
    val addedSeconds: Long,
    val createdAt: Long,
) {
    override fun toString(): String =
        "SessionExtension(id=$id, sessionId=$sessionId, reason=[REDACTED], addedSeconds=$addedSeconds)"
}
