package dev.intent.core

data class EngineConfig(
    /**
     * Back-press on the expiry checkpoint is treated as "need more time, no request" (PRD §15.8):
     * the checkpoint closes and re-appears after this short snooze. It never resets the timer to
     * "no limit", and it is not counted as an extension.
     */
    val backDismissSnoozeMs: Long = 60_000,
    /** Unresolved checkpoints for an app that is no longer in front are abandoned after this. */
    val staleCheckpointMs: Long = 30 * 60_000,
)

/**
 * The session state machine (PRD §16). Pure and single-threaded: the Android host must call it
 * from one thread (the engine thread) and execute the returned effects in order.
 *
 * Every transition is written to [store] before effects are returned, so the durable row is
 * always at least as new as anything the UI renders (PRD §42).
 */
class SessionEngine(
    private val store: SessionStore,
    private val rules: RuleProvider,
    private val clock: Clock,
    private val ids: IdGenerator,
    private val config: EngineConfig = EngineConfig(),
) {
    /** Last known foreground package. In-memory only; re-derived from UsageEvents after restart. */
    var foregroundPackage: String? = null
        private set

    /**
     * Process start (service restart, alarm wake-up, RN relaunch): recompute every open session
     * against wall-clock instead of resuming blindly (PRD §15.6).
     */
    fun restore(): List<EngineEffect> {
        val now = clock.nowMs()
        val fx = mutableListOf<EngineEffect>()
        foregroundPackage = null
        for (s in store.findAllOpen()) {
            when (s.state) {
                // Died mid-pause: nothing was committed, next foreground poll re-triggers cleanly.
                SessionState.PAUSE -> end(s, SessionState.SESSION_ABANDONED, CompletionReason.ABANDONED, now, fx)
                // We don't know whether the target is still in front until the first poll. Start
                // the grace window now; the poll moves it back to ACTIVE if the user is still there.
                SessionState.SESSION_ACTIVE -> save(
                    s.stopClock(now).copy(state = SessionState.BACKGROUND_GRACE, backgroundedAt = now, updatedAt = now),
                    fx,
                )
                // AWAITING_INTENTION / TIME_EXPIRED / EXTENSION_REQUEST stay unresolved and are
                // re-shown when their app is next in front. BACKGROUND_GRACE expires via tick().
                else -> Unit
            }
        }
        fx += syncAlarm()
        return fx
    }

    /** BOOT_COMPLETED: a session across a reboot is meaningless to resume (PRD §17, §43). */
    fun bootSweep(): List<EngineEffect> {
        val fx = mutableListOf<EngineEffect>()
        foregroundPackage = null
        for (s in store.findAllOpen()) {
            // Last moment we actually know the user was in the app; not the time of the sweep.
            val lastKnown = s.backgroundedAt ?: s.updatedAt
            end(s, SessionState.SESSION_ABANDONED, CompletionReason.DEVICE_RESTARTED, lastKnown, fx)
        }
        fx += syncAlarm()
        return fx
    }

    /** [packageName] is null when nothing is meaningfully in front (screen off / locked). */
    fun onForegroundChanged(packageName: String?): List<EngineEffect> {
        val previous = foregroundPackage
        if (packageName == previous) return emptyList()
        val now = clock.nowMs()
        val fx = mutableListOf<EngineEffect>()
        foregroundPackage = packageName
        if (previous != null) onLeave(previous, now, fx)
        if (packageName != null) onEnter(packageName, now, fx)
        fx += syncAlarm()
        return fx
    }

    /** Periodic evaluation (poll loop and alarm backstop): expiry and grace-window timeouts. */
    fun tick(): List<EngineEffect> {
        val now = clock.nowMs()
        val fx = mutableListOf<EngineEffect>()
        for (s in store.findAllOpen()) {
            when {
                s.state == SessionState.SESSION_ACTIVE && s.plannedEndAt != null && now >= s.plannedEndAt ->
                    expire(s, now, fx)
                s.state == SessionState.BACKGROUND_GRACE && graceExpired(s, now) ->
                    abandonFromGrace(s, fx)
                s.state.isCheckpoint && s.packageName != foregroundPackage &&
                    now - s.createdAt >= config.staleCheckpointMs ->
                    end(s, SessionState.SESSION_ABANDONED, CompletionReason.ABANDONED, now, fx)
            }
        }
        if (fx.isNotEmpty()) fx += syncAlarm()
        return fx
    }

    fun onPauseElapsed(sessionId: String): List<EngineEffect> {
        val s = store.get(sessionId) ?: return emptyList()
        if (s.state != SessionState.PAUSE) return emptyList()
        val rule = rules.ruleFor(s.packageName) ?: return emptyList()
        val fx = mutableListOf<EngineEffect>()
        val next = save(s.copy(state = SessionState.AWAITING_INTENTION, updatedAt = clock.nowMs()), fx)
        if (foregroundPackage == s.packageName) fx += EngineEffect.ShowIntentionForm(next, rule)
        return fx
    }

    /**
     * Continue on the intention checkpoint. An empty intention is allowed on purpose (PRD §15.2).
     * [plannedSeconds] null means "No timer".
     */
    fun submitIntention(sessionId: String, intention: String, plannedSeconds: Long?): List<EngineEffect> {
        val s = requireSession(sessionId)
        requireState(s, s.state.isCheckpoint, "submitIntention")
        if (plannedSeconds != null && plannedSeconds <= 0) {
            throw EngineException(EngineException.Code.INVALID_ARGUMENT, "plannedSeconds must be positive")
        }
        val rule = rules.ruleFor(s.packageName)
        val seconds = if (rule?.timerEnabled == false) null else plannedSeconds
        val now = clock.nowMs()
        val fx = mutableListOf<EngineEffect>()
        val inFront = foregroundPackage == s.packageName
        val next = save(
            s.copy(
                intention = intention.trim().ifEmpty { Session.UNSPECIFIED_INTENTION },
                startedAt = now,
                plannedDurationSeconds = seconds,
                plannedEndAt = seconds?.let { now + it * 1000 },
                state = if (inFront) SessionState.SESSION_ACTIVE else SessionState.BACKGROUND_GRACE,
                backgroundedAt = if (inFront) null else now,
                activeSince = if (inFront) now else null,
                updatedAt = now,
            ),
            fx,
        )
        fx += EngineEffect.HideCheckpoint
        if (inFront) showReminderIfEnabled(next, fx)
        fx += syncAlarm()
        return fx
    }

    /** "Not now" or back-press on the intention checkpoint: abandon and take the user home. */
    fun dismissCheckpoint(sessionId: String): List<EngineEffect> {
        val s = requireSession(sessionId)
        requireState(s, s.state.isCheckpoint, "dismissCheckpoint")
        val fx = mutableListOf<EngineEffect>()
        end(s, SessionState.SESSION_ABANDONED, CompletionReason.ABANDONED, clock.nowMs(), fx)
        fx += EngineEffect.HideCheckpoint
        if (foregroundPackage == s.packageName) fx += EngineEffect.GoHome
        fx += syncAlarm()
        return fx
    }

    /** "I need more time" on the expiry checkpoint: switch to the extension form. */
    fun beginExtension(sessionId: String): List<EngineEffect> {
        val s = requireSession(sessionId)
        requireState(s, s.state == SessionState.TIME_EXPIRED, "beginExtension")
        val rule = rules.ruleFor(s.packageName)
        if (rule?.extensionAllowed == false) {
            throw EngineException(EngineException.Code.INVALID_STATE, "extensions disabled for ${s.packageName}")
        }
        val fx = mutableListOf<EngineEffect>()
        val next = save(s.copy(state = SessionState.EXTENSION_REQUEST, updatedAt = clock.nowMs()), fx)
        fx += EngineEffect.ShowExpired(next, extensionAllowed = true)
        return fx
    }

    /** Back-press on the extension form returns to the expiry checkpoint. */
    fun cancelExtension(sessionId: String): List<EngineEffect> {
        val s = requireSession(sessionId)
        requireState(s, s.state == SessionState.EXTENSION_REQUEST, "cancelExtension")
        val fx = mutableListOf<EngineEffect>()
        val next = save(s.copy(state = SessionState.TIME_EXPIRED, updatedAt = clock.nowMs()), fx)
        fx += EngineEffect.ShowExpired(next, extensionAllowed = rules.ruleFor(s.packageName)?.extensionAllowed != false)
        return fx
    }

    /** No hard cap on extensions in V1 — capping would trap the user (PRD §15.9). */
    fun requestExtension(sessionId: String, reason: String?, extraSeconds: Long): List<EngineEffect> {
        val s = requireSession(sessionId)
        requireState(
            s,
            s.state.isExpiredCheckpoint || s.state == SessionState.SESSION_ACTIVE,
            "requestExtension",
        )
        if (extraSeconds <= 0) {
            throw EngineException(EngineException.Code.INVALID_ARGUMENT, "extraSeconds must be positive")
        }
        val now = clock.nowMs()
        val fx = mutableListOf<EngineEffect>()
        store.insertExtension(
            SessionExtension(
                id = ids.newId(),
                sessionId = s.id,
                reason = reason?.trim()?.ifEmpty { null },
                addedSeconds = extraSeconds,
                createdAt = now,
            ),
        )
        val base = maxOf(now, s.plannedEndAt ?: now)
        val next = save(
            s.copy(
                plannedEndAt = base + extraSeconds * 1000,
                extensionCount = s.extensionCount + 1,
                state = SessionState.SESSION_ACTIVE,
                updatedAt = now,
            ),
            fx,
        )
        fx += EngineEffect.HideCheckpoint
        if (foregroundPackage == s.packageName) showReminderIfEnabled(next, fx)
        fx += syncAlarm()
        return fx
    }

    /** Back-press on the expiry checkpoint (PRD §15.8). */
    fun snoozeExpired(sessionId: String): List<EngineEffect> {
        val s = requireSession(sessionId)
        requireState(s, s.state.isExpiredCheckpoint, "snoozeExpired")
        val now = clock.nowMs()
        val fx = mutableListOf<EngineEffect>()
        val next = save(
            s.copy(
                plannedEndAt = now + config.backDismissSnoozeMs,
                state = SessionState.SESSION_ACTIVE,
                updatedAt = now,
            ),
            fx,
        )
        fx += EngineEffect.HideCheckpoint
        if (foregroundPackage == s.packageName) showReminderIfEnabled(next, fx)
        fx += syncAlarm()
        return fx
    }

    /**
     * Terminal transition from any open state. DONE shows the completion card while the app is
     * in front; LEFT takes the user home; ABANDONED ends silently.
     */
    fun complete(sessionId: String, reason: CompletionReason): List<EngineEffect> {
        val s = requireSession(sessionId)
        requireState(s, !s.state.isTerminal, "complete")
        if (reason == CompletionReason.DEVICE_RESTARTED) {
            throw EngineException(EngineException.Code.INVALID_ARGUMENT, "device_restarted is reserved for boot sweep")
        }
        val now = clock.nowMs()
        val fx = mutableListOf<EngineEffect>()
        val terminal =
            if (reason == CompletionReason.ABANDONED) SessionState.SESSION_ABANDONED else SessionState.SESSION_COMPLETED
        val ended = end(s, terminal, reason, now, fx)
        fx += EngineEffect.HideReminder
        fx += EngineEffect.HideCheckpoint
        val inFront = foregroundPackage == s.packageName
        when {
            inFront && reason == CompletionReason.DONE -> fx += EngineEffect.ShowCompletion(ended)
            inFront && reason == CompletionReason.LEFT -> fx += EngineEffect.GoHome
            else -> Unit
        }
        fx += syncAlarm()
        return fx
    }

    fun activeSession(): Session? {
        val open = store.findAllOpen()
        return open.firstOrNull { it.packageName == foregroundPackage } ?: open.maxByOrNull { it.updatedAt }
    }

    // --- transitions -------------------------------------------------------------------------

    private fun onLeave(packageName: String, now: Long, fx: MutableList<EngineEffect>) {
        val s = store.findOpenForPackage(packageName) ?: return
        when (s.state) {
            SessionState.SESSION_ACTIVE -> {
                save(
                    s.stopClock(now).copy(state = SessionState.BACKGROUND_GRACE, backgroundedAt = now, updatedAt = now),
                    fx,
                )
                fx += EngineEffect.HideReminder
            }
            // Pressing Home on the checkpoint is the same answer as "Not now".
            SessionState.PAUSE, SessionState.AWAITING_INTENTION -> {
                end(s, SessionState.SESSION_ABANDONED, CompletionReason.ABANDONED, now, fx)
                fx += EngineEffect.HideCheckpoint
            }
            // Leaving at the expiry checkpoint is the "Leave {app}" answer, taken by the user.
            SessionState.TIME_EXPIRED, SessionState.EXTENSION_REQUEST -> {
                end(s, SessionState.SESSION_COMPLETED, CompletionReason.LEFT, now, fx)
                fx += EngineEffect.HideCheckpoint
            }
            else -> Unit
        }
    }

    private fun onEnter(packageName: String, now: Long, fx: MutableList<EngineEffect>) {
        if (NeverMonitor.contains(packageName)) return
        val rule = rules.ruleFor(packageName)?.takeIf { it.monitoringEnabled } ?: return
        val existing = store.findOpenForPackage(packageName)
        if (existing == null) {
            startCheckpoint(packageName, rule, now, fx)
            return
        }
        when (existing.state) {
            SessionState.PAUSE -> fx += EngineEffect.ShowPause(existing, rule.pauseDurationMs)
            SessionState.AWAITING_INTENTION -> fx += EngineEffect.ShowIntentionForm(existing, rule)
            SessionState.SESSION_ACTIVE ->
                if (existing.plannedEndAt != null && now >= existing.plannedEndAt) {
                    expire(existing, now, fx)
                } else {
                    showReminderIfEnabled(existing, fx)
                }
            SessionState.BACKGROUND_GRACE ->
                if (graceExpired(existing, now)) {
                    abandonFromGrace(existing, fx)
                    startCheckpoint(packageName, rule, now, fx)
                } else {
                    val resumed = save(
                        existing.copy(
                            state = SessionState.SESSION_ACTIVE,
                            backgroundedAt = null,
                            activeSince = now,
                            updatedAt = now,
                        ),
                        fx,
                    )
                    if (resumed.plannedEndAt != null && now >= resumed.plannedEndAt) {
                        expire(resumed, now, fx)
                    } else {
                        showReminderIfEnabled(resumed, fx)
                    }
                }
            SessionState.TIME_EXPIRED, SessionState.EXTENSION_REQUEST ->
                fx += EngineEffect.ShowExpired(existing, rule.extensionAllowed)
            SessionState.SESSION_COMPLETED, SessionState.SESSION_ABANDONED -> Unit
        }
    }

    private fun startCheckpoint(packageName: String, rule: AppRule, now: Long, fx: MutableList<EngineEffect>) {
        if (!rule.askIntention) {
            val seconds = if (rule.timerEnabled) rule.defaultTimerSeconds else null
            val s = newSession(packageName, rule, now).copy(
                intention = Session.UNSPECIFIED_INTENTION,
                startedAt = now,
                plannedDurationSeconds = seconds,
                plannedEndAt = seconds?.let { now + it * 1000 },
                state = SessionState.SESSION_ACTIVE,
                activeSince = now,
            )
            store.insert(s)
            fx += EngineEffect.SessionChanged(s)
            showReminderIfEnabled(s, fx)
            return
        }
        val s = newSession(packageName, rule, now)
        store.insert(s)
        fx += EngineEffect.SessionChanged(s)
        fx += EngineEffect.ShowPause(s, rule.pauseDurationMs)
    }

    private fun newSession(packageName: String, rule: AppRule, now: Long) = Session(
        id = ids.newId(),
        packageName = packageName,
        appName = rule.appName,
        intention = "",
        startedAt = null,
        plannedDurationSeconds = null,
        plannedEndAt = null,
        endedAt = null,
        wallClockSeconds = null,
        extensionCount = 0,
        state = SessionState.PAUSE,
        completionReason = null,
        backgroundedAt = null,
        createdAt = now,
        updatedAt = now,
    )

    private fun expire(s: Session, now: Long, fx: MutableList<EngineEffect>) {
        val next = save(s.copy(state = SessionState.TIME_EXPIRED, updatedAt = now), fx)
        if (foregroundPackage == s.packageName) {
            fx += EngineEffect.HideReminder
            fx += EngineEffect.ShowExpired(next, rules.ruleFor(s.packageName)?.extensionAllowed != false)
        }
    }

    private fun graceExpired(s: Session, now: Long): Boolean {
        val since = s.backgroundedAt ?: return true
        val graceMs = (rules.ruleFor(s.packageName)?.reentryGraceSeconds ?: AppRule.DEFAULT_REENTRY_GRACE_SECONDS) * 1000
        return now - since >= graceMs
    }

    /** Actual time ends when the user left, not when the grace window ran out (ADR-004). */
    private fun abandonFromGrace(s: Session, fx: MutableList<EngineEffect>) {
        end(s, SessionState.SESSION_ABANDONED, CompletionReason.ABANDONED, s.backgroundedAt ?: s.updatedAt, fx)
    }

    private fun end(
        s: Session,
        terminal: SessionState,
        reason: CompletionReason,
        endedAt: Long,
        fx: MutableList<EngineEffect>,
    ): Session = save(
        s.stopClock(endedAt).copy(
            state = terminal,
            completionReason = reason,
            endedAt = endedAt,
            wallClockSeconds = s.startedAt?.let { ((endedAt - it) / 1000).coerceAtLeast(0) },
            backgroundedAt = null,
            updatedAt = clock.nowMs(),
        ),
        fx,
    )

    /** Closes the running in-front interval at [at] (never negative, e.g. boot sweep). */
    private fun Session.stopClock(at: Long): Session {
        val since = activeSince ?: return this
        return copy(foregroundMs = foregroundMs + (at - since).coerceAtLeast(0), activeSince = null)
    }

    private fun showReminderIfEnabled(s: Session, fx: MutableList<EngineEffect>) {
        val rule = rules.ruleFor(s.packageName)
        if (rule?.floatingReminderEnabled != false) {
            fx += EngineEffect.ShowReminder(s, warningsEnabled = rule?.warningsEnabled != false)
        }
    }

    /**
     * Grace sessions keep their alarm: after a restart every ACTIVE session is parked in grace
     * until the first poll, and the alarm may be the only thing that wakes us (poll loop dead).
     * A wake for a session the user really left is one cheap no-op evaluation.
     */
    private fun syncAlarm(): EngineEffect.SyncExpiryAlarm = EngineEffect.SyncExpiryAlarm(
        store.findAllOpen()
            .filter { it.state == SessionState.SESSION_ACTIVE || it.state == SessionState.BACKGROUND_GRACE }
            .mapNotNull { it.plannedEndAt }
            .filter { it > clock.nowMs() }
            .minOrNull(),
    )

    private fun save(s: Session, fx: MutableList<EngineEffect>): Session {
        store.update(s)
        fx += EngineEffect.SessionChanged(s)
        return s
    }

    private fun requireSession(id: String): Session =
        store.get(id) ?: throw EngineException(EngineException.Code.SESSION_NOT_FOUND, "no session $id")

    private fun requireState(s: Session, ok: Boolean, op: String) {
        if (!ok) throw EngineException(EngineException.Code.INVALID_STATE, "$op not allowed in ${s.state}")
    }
}
