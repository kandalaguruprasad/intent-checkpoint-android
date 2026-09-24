package dev.intent.core

/**
 * Side effects requested by [SessionEngine]. The engine is pure decision logic; the Android host
 * executes these (overlay windows, AlarmManager, launcher intent, event emission).
 */
sealed interface EngineEffect {
    /** Show the breathing pause, then call [SessionEngine.onPauseElapsed] after [pauseMs]. */
    data class ShowPause(val session: Session, val pauseMs: Long) : EngineEffect

    data class ShowIntentionForm(val session: Session, val rule: AppRule) : EngineEffect

    data class ShowReminder(val session: Session, val warningsEnabled: Boolean) : EngineEffect

    data object HideReminder : EngineEffect

    /** Reconsideration checkpoint; renders the extension form when state is EXTENSION_REQUEST. */
    data class ShowExpired(val session: Session, val extensionAllowed: Boolean) : EngineEffect

    data class ShowCompletion(val session: Session) : EngineEffect

    /** Removes whichever blocking checkpoint surface (pause/form/expired/completion) is shown. */
    data object HideCheckpoint : EngineEffect

    /**
     * Keep exactly one Doze-safe backstop alarm at the earliest future `plannedEndAt` of an
     * active or in-grace session, or none when [atMs] is null (PRD §29).
     */
    data class SyncExpiryAlarm(val atMs: Long?) : EngineEffect

    /** Bring the launcher to front. Never framed as "closing" the target app (PRD §15.10). */
    data object GoHome : EngineEffect

    data class SessionChanged(val session: Session) : EngineEffect
}

class EngineException(val code: Code, message: String) : RuntimeException(message) {
    enum class Code(val wire: String) {
        SESSION_NOT_FOUND("E_SESSION_NOT_FOUND"),
        INVALID_STATE("E_INVALID_STATE"),
        INVALID_ARGUMENT("E_INVALID_ARGUMENT"),
    }
}
