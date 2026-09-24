package dev.intent.checkpoint.engine

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import dev.intent.checkpoint.monitoring.UsageEventsSource
import dev.intent.checkpoint.overlay.OverlayCallbacks
import dev.intent.checkpoint.overlay.OverlayController
import dev.intent.checkpoint.permissions.PermissionChecker
import dev.intent.checkpoint.permissions.PermissionSnapshot
import dev.intent.checkpoint.service.ExpiryAlarmScheduler
import dev.intent.checkpoint.sessions.EngineSettings
import dev.intent.checkpoint.sessions.IntentDatabase
import dev.intent.checkpoint.sessions.LatencyLog
import dev.intent.checkpoint.sessions.MonitoredAppRow
import dev.intent.checkpoint.sessions.MonitoredAppsRepo
import dev.intent.checkpoint.sessions.SqliteRuleProvider
import dev.intent.checkpoint.sessions.SqliteSessionStore
import dev.intent.core.CompletionReason
import dev.intent.core.DailySummary
import dev.intent.core.EngineEffect
import dev.intent.core.ForegroundChange
import dev.intent.core.ForegroundTracker
import dev.intent.core.LatencySummary
import dev.intent.core.PollPolicy
import dev.intent.core.RuleProvider
import dev.intent.core.Session
import dev.intent.core.SessionEngine
import dev.intent.core.SessionState
import java.util.UUID
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * Owns the monitoring engine inside the `:engine` process: one dedicated thread serialises every
 * state-machine transition and DB write; overlay work is posted to the main thread.
 *
 * Callers: [dev.intent.checkpoint.service.MonitoringService] (poll loop lifetime),
 * [dev.intent.checkpoint.bridge.EngineProvider] (RN commands, cross-process), the overlay
 * (user taps), and the boot/alarm receivers.
 */
class EngineHost private constructor(context: Context, startReason: StartReason) : OverlayCallbacks {

    enum class StartReason { NORMAL, BOOT }

    /** Notified on the engine thread after any session change, for the FGS notification. */
    fun interface SessionListener {
        fun onActiveSessionChanged(session: Session?)
    }

    private val app = context.applicationContext
    private val thread = HandlerThread("intent-engine").apply { start() }
    private val handler = Handler(thread.looper)

    private val db = IntentDatabase(app)
    private val dbRules = SqliteRuleProvider(db)
    private val settings = EngineSettings(db)
    private val latency = LatencyLog(db)
    private val store = SqliteSessionStore(db)
    private val monitoredApps = MonitoredAppsRepo(db) { dbRules.invalidate() }

    @Volatile private var permissions: PermissionSnapshot = PermissionChecker.snapshot(app)

    /**
     * Fail open: without the overlay permission nothing can be shown, so no app counts as
     * monitored and no phantom checkpoints are written to history (PRD §41).
     */
    private val rules = RuleProvider { pkg -> if (permissions.overlay) dbRules.ruleFor(pkg) else null }

    private val engine = SessionEngine(
        store = store,
        rules = rules,
        clock = { System.currentTimeMillis() },
        ids = { UUID.randomUUID().toString() },
    )

    private val usage = UsageEventsSource(app)
    private val power = app.getSystemService(PowerManager::class.java)
    private val overlay = OverlayController(app, this)
    private val preview = OverlayPreview(app)
    private val alarms = ExpiryAlarmScheduler(app)
    private val events = EngineEvents(app)
    private val policy = PollPolicy()

    // Engine-thread state.
    private var tracker = newTracker()
    private var polling = false
    private var screenOn = true
    private var lastPermissionCheckMs = 0L
    private var sessionListener: SessionListener? = null

    private val pollRunnable = object : Runnable {
        override fun run() {
            pollOnce()
            if (polling) handler.postDelayed(this, policy.intervalMs(screenOn))
        }
    }

    init {
        handler.post {
            val fx = if (startReason == StartReason.BOOT) engine.bootSweep() else engine.restore()
            apply(fx)
            events.sessionRestored(engine.activeSession())
        }
    }

    // --- lifecycle (service) -----------------------------------------------------------------

    val isMonitoringEnabled: Boolean get() = call { settings.monitoringEnabled }

    val isPolling: Boolean get() = call { polling }

    /**
     * Non-blocking, for main-thread callers (receivers). Runs [block] on the engine thread after
     * everything already queued (startup restore/sweep, alarm evaluation).
     */
    fun whenReady(block: (monitoringEnabled: Boolean) -> Unit) {
        handler.post { block(settings.monitoringEnabled) }
    }

    fun monitoredPackages(): List<String> = call { dbRules.monitoredPackages() }

    fun startPolling() {
        handler.post {
            settings.monitoringEnabled = true
            if (!polling) {
                polling = true
                tracker = newTracker()
                handler.post(pollRunnable)
                events.monitoringStarted()
                EngineLog.d("polling started")
            }
        }
    }

    /** [userInitiated] false when the OS stops the service: keep the "enabled" intent. */
    fun stopPolling(userInitiated: Boolean) {
        handler.post {
            if (userInitiated) settings.monitoringEnabled = false
            polling = false
            handler.removeCallbacks(pollRunnable)
            apply(engine.onForegroundChanged(null))
            overlay.hideAll()
            events.monitoringStopped()
            EngineLog.d("polling stopped (user=$userInitiated)")
        }
    }

    fun setSessionListener(listener: SessionListener?) {
        handler.post {
            sessionListener = listener
            listener?.onActiveSessionChanged(engine.activeSession())
        }
    }

    /** Alarm backstop: evaluate once even if the poll loop is not running (P0-010). */
    fun onExpiryAlarm() {
        handler.post {
            // Monitoring switched off by the user: no interventions, not even a stale alarm's.
            if (!settings.monitoringEnabled) return@post
            EngineLog.d("expiry alarm")
            pollOnce()
        }
    }

    // --- commands (RN via EngineProvider; blocking, cross-process) ---------------------------

    fun activeSession(): Session? = call { engine.activeSession() }

    /** Sessions created in `[fromMs, toMs)` — the caller picks the local-day bounds. */
    fun sessions(fromMs: Long, toMs: Long, limit: Int): List<Session> = call { store.createdBetween(fromMs, toMs, limit) }

    fun summary(fromMs: Long, toMs: Long): DailySummary =
        call { DailySummary.of(store.createdBetween(fromMs, toMs), System.currentTimeMillis()) }

    fun monitoredApps(): List<MonitoredAppRow> = call { monitoredApps.list() }

    fun setMonitoredApps(apps: List<MonitoredAppRow>) = call { monitoredApps.replaceAll(apps, app.packageName) }

    var onboardingComplete: Boolean
        get() = call { settings.onboardingComplete }
        set(value) = call { settings.onboardingComplete = value }

    /** Renders one overlay surface with fixture data; never touches sessions (design preview). */
    fun previewOverlay(kind: String) {
        val pkg = call { monitoredApps.list().firstOrNull()?.packageName } ?: app.packageName
        preview.show(kind, pkg)
    }

    fun latencySummary(): LatencySummary = call { latency.summary() }

    fun clearLatency() = call { latency.clear() }

    fun submitIntention(sessionId: String, intention: String, plannedSeconds: Long?) =
        call { apply(engine.submitIntention(sessionId, intention, plannedSeconds)) }

    fun requestExtension(sessionId: String, reason: String?, extraSeconds: Long) =
        call { apply(engine.requestExtension(sessionId, reason, extraSeconds)) }

    fun completeSession(sessionId: String, reason: CompletionReason) =
        call { apply(engine.complete(sessionId, reason)) }

    // --- overlay callbacks (main thread -> engine thread, fire and forget) -------------------

    override fun onPauseElapsed(sessionId: String) = post { engine.onPauseElapsed(sessionId) }

    override fun onSubmitIntention(sessionId: String, intention: String, plannedSeconds: Long?) =
        post { engine.submitIntention(sessionId, intention, plannedSeconds) }

    override fun onNotNow(sessionId: String) = post { engine.dismissCheckpoint(sessionId) }

    override fun onDone(sessionId: String) = post { engine.complete(sessionId, CompletionReason.DONE) }

    override fun onEnd(sessionId: String) = post { engine.complete(sessionId, CompletionReason.LEFT) }

    override fun onBeginExtension(sessionId: String) = post { engine.beginExtension(sessionId) }

    override fun onCancelExtension(sessionId: String) = post { engine.cancelExtension(sessionId) }

    override fun onRequestExtension(sessionId: String, reason: String?, extraSeconds: Long) =
        post { engine.requestExtension(sessionId, reason, extraSeconds) }

    override fun onSnoozeExpired(sessionId: String) = post { engine.snoozeExpired(sessionId) }

    // --- internals ---------------------------------------------------------------------------

    private fun pollOnce() {
        val now = System.currentTimeMillis()
        refreshPermissionsIfDue(now)
        if (!permissions.usageAccess) {
            // Revoked mid-use: stop detecting silently, keep timers honest (PRD §17, Story 8).
            apply(engine.tick())
            return
        }
        val interactive = power?.isInteractive ?: true
        if (!interactive) {
            if (screenOn) {
                screenOn = false
                tracker.onScreenOff(now)?.let(::onForegroundChange)
            }
            apply(engine.tick())
            return
        }
        screenOn = true
        val latest = usage.latestResume(tracker.queryWindowStart(now), now, IGNORED_PACKAGES)
        tracker.process(listOfNotNull(latest), now)?.let(::onForegroundChange)
        apply(engine.tick())
    }

    private fun onForegroundChange(change: ForegroundChange) {
        val pkg = change.packageName
        if (pkg != null && !change.fromInitialLookback && dbRules.ruleFor(pkg) != null) {
            latency.record(pkg, change.detectionLatencyMs, change.detectedAtMs)
            EngineLog.d("detected $pkg latency=${change.detectionLatencyMs}ms")
        }
        events.foregroundAppChanged(pkg)
        apply(engine.onForegroundChanged(pkg))
    }

    private fun refreshPermissionsIfDue(now: Long) {
        if (now - lastPermissionCheckMs < policy.permissionCheckIntervalMs) return
        lastPermissionCheckMs = now
        val fresh = PermissionChecker.snapshot(app)
        if (fresh != permissions) {
            permissions = fresh
            events.permissionChanged(fresh)
            if (!fresh.overlay) overlay.hideAll()
        }
    }

    private fun apply(effects: List<EngineEffect>) {
        var sessionsChanged = false
        for (fx in effects) {
            when (fx) {
                is EngineEffect.ShowPause -> overlay.showPause(fx.session, fx.pauseMs)
                is EngineEffect.ShowIntentionForm ->
                    overlay.showIntentionForm(fx.session, fx.rule, store.recentIntentions(fx.session.packageName))
                is EngineEffect.ShowReminder -> overlay.showReminder(fx.session, fx.warningsEnabled)
                is EngineEffect.HideReminder -> overlay.hideReminder()
                is EngineEffect.ShowExpired -> overlay.showExpired(fx.session, fx.extensionAllowed)
                is EngineEffect.ShowCompletion -> overlay.showCompletion(fx.session)
                is EngineEffect.HideCheckpoint -> overlay.hideCheckpoint()
                is EngineEffect.SyncExpiryAlarm -> alarms.sync(fx.atMs)
                is EngineEffect.GoHome -> overlay.goHome()
                is EngineEffect.SessionChanged -> {
                    sessionsChanged = true
                    events.sessionStateChanged(fx.session)
                    if (fx.session.state == SessionState.TIME_EXPIRED) events.timerExpired(fx.session.id)
                }
            }
        }
        if (sessionsChanged) sessionListener?.onActiveSessionChanged(engine.activeSession())
    }

    private fun post(block: () -> List<EngineEffect>) {
        handler.post {
            try {
                apply(block())
            } catch (e: Exception) {
                // A stale tap (session already resolved elsewhere) must never crash the service.
                EngineLog.w("overlay command rejected", e)
            }
        }
    }

    /** Runs [block] on the engine thread and waits. Never call from the engine or main thread. */
    private fun <T> call(block: () -> T): T {
        check(Looper.myLooper() != handler.looper) { "call() from engine thread would deadlock" }
        check(Looper.myLooper() != Looper.getMainLooper()) { "call() must not block the main thread" }
        val task = FutureTask(block)
        handler.post(task)
        return try {
            task.get(CALL_TIMEOUT_S, TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        }
    }

    private fun newTracker() = ForegroundTracker(initialLookbackMs = INITIAL_LOOKBACK_MS, ignored = IGNORED_PACKAGES)

    companion object {
        /**
         * Long first-poll look-back so a restart while the user sits in one app for a long time
         * still finds the current foreground. One-off cost; later polls query a few seconds.
         */
        private const val INITIAL_LOOKBACK_MS = 3 * 60 * 60_000L
        private const val CALL_TIMEOUT_S = 5L

        /** Resumes that are not a real "app switch" for our purposes. */
        private val IGNORED_PACKAGES = setOf("com.android.systemui")

        @Volatile private var instance: EngineHost? = null

        fun get(context: Context, reason: StartReason = StartReason.NORMAL): EngineHost {
            check(AppProcess.isEngineProcess(context)) { "EngineHost lives in the :engine process only" }
            return instance ?: synchronized(this) {
                instance ?: EngineHost(context, reason).also { instance = it }
            }
        }
    }
}
