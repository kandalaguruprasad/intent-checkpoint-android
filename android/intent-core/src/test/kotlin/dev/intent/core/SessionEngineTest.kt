package dev.intent.core

import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionEngineTest {
    private val ig = "com.instagram.android"
    private val wa = "com.whatsapp"
    private val launcher = "com.google.android.apps.nexuslauncher"

    private lateinit var store: InMemorySessionStore
    private lateinit var clock: FakeClock
    private var rules = mutableMapOf<String, AppRule>()
    private lateinit var engine: SessionEngine
    private val ids = SequentialIds() // shared: ids must stay unique across simulated restarts

    @Before
    fun setUp() {
        store = InMemorySessionStore()
        clock = FakeClock()
        rules = mutableMapOf(ig to AppRule(ig, "Instagram"))
        engine = newEngine()
    }

    private fun newEngine() = SessionEngine(store, { rules[it] }, clock, ids)

    private fun only(): Session = store.sessions.values.single()

    private inline fun <reified T : EngineEffect> List<EngineEffect>.has(): Boolean = any { it is T }

    private fun alarmOf(fx: List<EngineEffect>): Long? =
        fx.filterIsInstance<EngineEffect.SyncExpiryAlarm>().last().atMs

    /** Open Instagram and get through the checkpoint with a 10-minute timer. */
    private fun startActiveSession(intention: String = "Reply to Ravi", seconds: Long? = 600): Session {
        engine.onForegroundChanged(ig)
        val id = only().id
        engine.onPauseElapsed(id)
        engine.submitIntention(id, intention, seconds)
        return store.get(id)!!
    }

    // --- detection -> checkpoint -------------------------------------------------------------

    @Test
    fun `monitored app with no session shows pause and persists a PAUSE row`() {
        val fx = engine.onForegroundChanged(ig)
        val s = only()
        assertEquals(SessionState.PAUSE, s.state)
        assertNull(s.startedAt)
        val pause = fx.filterIsInstance<EngineEffect.ShowPause>().single()
        assertEquals(1_200, pause.pauseMs)
    }

    @Test
    fun `unmonitored and never-monitor packages are ignored`() {
        rules["com.android.phone"] = AppRule("com.android.phone", "Phone")
        assertTrue(engine.onForegroundChanged(wa).none { it is EngineEffect.ShowPause })
        assertTrue(engine.onForegroundChanged("com.android.phone").none { it is EngineEffect.ShowPause })
        assertTrue(store.sessions.isEmpty())
    }

    @Test
    fun `disabled rule does not trigger`() {
        rules[ig] = AppRule(ig, "Instagram", monitoringEnabled = false)
        engine.onForegroundChanged(ig)
        assertTrue(store.sessions.isEmpty())
    }

    @Test
    fun `repeated foreground reports for the same package never create a second checkpoint`() {
        engine.onForegroundChanged(ig)
        repeat(5) { assertTrue(engine.onForegroundChanged(ig).isEmpty()) }
        assertEquals(1, store.sessions.size)
    }

    @Test
    fun `pause elapsing moves to AWAITING_INTENTION and shows form`() {
        engine.onForegroundChanged(ig)
        val fx = engine.onPauseElapsed(only().id)
        assertEquals(SessionState.AWAITING_INTENTION, only().state)
        assertTrue(fx.has<EngineEffect.ShowIntentionForm>())
        assertTrue(engine.onPauseElapsed(only().id).isEmpty(), "second elapse is a no-op")
    }

    @Test
    fun `submit writes intention, timing and ACTIVE state, shows reminder, arms alarm`() {
        engine.onForegroundChanged(ig)
        val id = only().id
        engine.onPauseElapsed(id)
        val t0 = clock.now
        val fx = engine.submitIntention(id, "  Reply to Ravi ", 600)
        val s = only()
        assertEquals(SessionState.SESSION_ACTIVE, s.state)
        assertEquals("Reply to Ravi", s.intention)
        assertEquals(t0, s.startedAt)
        assertEquals(t0 + 600_000, s.plannedEndAt)
        assertTrue(fx.has<EngineEffect.HideCheckpoint>())
        assertTrue(fx.has<EngineEffect.ShowReminder>())
        assertEquals(t0 + 600_000, alarmOf(fx))
    }

    @Test
    fun `submit is allowed straight from PAUSE (fast typist)`() {
        engine.onForegroundChanged(ig)
        engine.submitIntention(only().id, "x", 60)
        assertEquals(SessionState.SESSION_ACTIVE, only().state)
    }

    @Test
    fun `empty intention is allowed and stored as unspecified`() {
        val s = startActiveSession(intention = "   ")
        assertEquals(Session.UNSPECIFIED_INTENTION, s.intention)
    }

    @Test
    fun `no timer means no planned end and no alarm`() {
        engine.onForegroundChanged(ig)
        val fx = engine.submitIntention(only().id, "Browse", null)
        assertNull(only().plannedEndAt)
        assertNull(alarmOf(fx))
    }

    @Test
    fun `timerEnabled false ignores the requested duration`() {
        rules[ig] = AppRule(ig, "Instagram", timerEnabled = false)
        engine.onForegroundChanged(ig)
        engine.submitIntention(only().id, "x", 600)
        assertNull(only().plannedEndAt)
    }

    @Test
    fun `non-positive duration is rejected`() {
        engine.onForegroundChanged(ig)
        val e = assertFailsWith<EngineException> { engine.submitIntention(only().id, "x", 0) }
        assertEquals(EngineException.Code.INVALID_ARGUMENT, e.code)
    }

    @Test
    fun `not now abandons and takes the user home`() {
        engine.onForegroundChanged(ig)
        val fx = engine.dismissCheckpoint(only().id)
        assertEquals(SessionState.SESSION_ABANDONED, only().state)
        assertEquals(CompletionReason.ABANDONED, only().completionReason)
        assertNull(only().startedAt)
        assertTrue(fx.has<EngineEffect.GoHome>())
    }

    @Test
    fun `leaving during the checkpoint abandons it and hides the overlay`() {
        engine.onForegroundChanged(ig)
        val fx = engine.onForegroundChanged(launcher)
        assertEquals(SessionState.SESSION_ABANDONED, only().state)
        assertTrue(fx.has<EngineEffect.HideCheckpoint>())
    }

    @Test
    fun `askIntention false starts a session directly with the default timer`() {
        rules[ig] = AppRule(ig, "Instagram", askIntention = false, defaultTimerSeconds = 300)
        val fx = engine.onForegroundChanged(ig)
        assertEquals(SessionState.SESSION_ACTIVE, only().state)
        assertEquals(300, only().plannedDurationSeconds)
        assertTrue(fx.has<EngineEffect.ShowReminder>())
        assertTrue(fx.none { it is EngineEffect.ShowPause })
    }

    // --- grace window ------------------------------------------------------------------------

    @Test
    fun `leaving an active session enters grace and hides reminder`() {
        startActiveSession()
        val fx = engine.onForegroundChanged(wa)
        assertEquals(SessionState.BACKGROUND_GRACE, only().state)
        assertEquals(clock.now, only().backgroundedAt)
        assertTrue(fx.has<EngineEffect.HideReminder>())
        assertEquals(only().plannedEndAt, alarmOf(fx), "backstop stays armed while in grace")
    }

    @Test
    fun `return within grace resumes the same session with its original planned end`() {
        val s = startActiveSession()
        engine.onForegroundChanged(wa)
        clock.advanceSeconds(119)
        val fx = engine.onForegroundChanged(ig)
        val resumed = only()
        assertEquals(s.id, resumed.id)
        assertEquals(SessionState.SESSION_ACTIVE, resumed.state)
        assertEquals(s.plannedEndAt, resumed.plannedEndAt)
        assertTrue(fx.has<EngineEffect.ShowReminder>())
        assertTrue(fx.none { it is EngineEffect.ShowPause })
    }

    @Test
    fun `return after grace abandons old session and shows a fresh checkpoint`() {
        val s = startActiveSession()
        clock.advanceSeconds(30)
        engine.onForegroundChanged(wa)
        val leftAt = clock.now
        clock.advanceSeconds(121)
        val fx = engine.onForegroundChanged(ig)
        val old = store.get(s.id)!!
        assertEquals(SessionState.SESSION_ABANDONED, old.state)
        assertEquals(leftAt, old.endedAt, "actual time ends when the user left")
        assertEquals(30, old.wallClockSeconds)
        assertEquals(2, store.sessions.size)
        assertTrue(fx.has<EngineEffect.ShowPause>())
    }

    @Test
    fun `tick abandons grace sessions once the window lapses`() {
        startActiveSession()
        engine.onForegroundChanged(wa)
        clock.advanceSeconds(60)
        assertTrue(engine.tick().isEmpty())
        clock.advanceSeconds(60)
        engine.tick()
        assertEquals(SessionState.SESSION_ABANDONED, only().state)
    }

    @Test
    fun `screen off behaves like leaving and screen on within grace resumes`() {
        val s = startActiveSession()
        engine.onForegroundChanged(null)
        assertEquals(SessionState.BACKGROUND_GRACE, only().state)
        clock.advanceSeconds(20)
        engine.onForegroundChanged(ig)
        assertEquals(s.id, only().id)
        assertEquals(SessionState.SESSION_ACTIVE, only().state)
    }

    @Test
    fun `custom grace per app is honoured`() {
        rules[ig] = AppRule(ig, "Instagram", reentryGraceSeconds = 10)
        startActiveSession()
        engine.onForegroundChanged(wa)
        clock.advanceSeconds(11)
        engine.onForegroundChanged(ig)
        assertEquals(2, store.sessions.size)
    }

    // --- expiry and extension ----------------------------------------------------------------

    @Test
    fun `tick at planned end expires and shows reconsideration checkpoint`() {
        startActiveSession(seconds = 120)
        clock.advanceSeconds(119)
        assertTrue(engine.tick().isEmpty())
        clock.advanceSeconds(1)
        val fx = engine.tick()
        assertEquals(SessionState.TIME_EXPIRED, only().state)
        assertTrue(fx.has<EngineEffect.HideReminder>())
        assertTrue(fx.has<EngineEffect.ShowExpired>())
        assertNull(alarmOf(fx))
    }

    @Test
    fun `timer keeps running in grace (wall clock) and expiry shows on return`() {
        startActiveSession(seconds = 120)
        engine.onForegroundChanged(wa)
        clock.advanceSeconds(100)
        engine.onForegroundChanged(ig)
        clock.advanceSeconds(20)
        engine.tick()
        assertEquals(SessionState.TIME_EXPIRED, only().state)
    }

    @Test
    fun `returning in grace after planned end goes straight to expiry checkpoint`() {
        startActiveSession(seconds = 60)
        engine.onForegroundChanged(wa)
        clock.advanceSeconds(90)
        val fx = engine.onForegroundChanged(ig)
        assertEquals(SessionState.TIME_EXPIRED, only().state)
        assertTrue(fx.has<EngineEffect.ShowExpired>())
    }

    @Test
    fun `extension flow increments count, records reason, extends from now`() {
        startActiveSession(seconds = 120)
        clock.advanceSeconds(125)
        engine.tick()
        val id = only().id
        val fx1 = engine.beginExtension(id)
        assertEquals(SessionState.EXTENSION_REQUEST, only().state)
        assertTrue(fx1.has<EngineEffect.ShowExpired>())
        val fx2 = engine.requestExtension(id, " finish thread ", 300)
        val s = only()
        assertEquals(SessionState.SESSION_ACTIVE, s.state)
        assertEquals(1, s.extensionCount)
        assertEquals(clock.now + 300_000, s.plannedEndAt)
        assertEquals(120, s.plannedDurationSeconds, "original plan is preserved for copy/metrics")
        assertEquals("finish thread", store.extensions.single().reason)
        assertTrue(fx2.has<EngineEffect.ShowReminder>())
        assertEquals(s.plannedEndAt, alarmOf(fx2))
    }

    @Test
    fun `extensions are uncapped`() {
        val id = startActiveSession(seconds = 60).id
        repeat(10) {
            clock.advanceSeconds(3600)
            engine.tick()
            engine.requestExtension(id, null, 60)
        }
        assertEquals(10, only().extensionCount)
    }

    @Test
    fun `cancel extension returns to expiry checkpoint`() {
        startActiveSession(seconds = 60)
        clock.advanceSeconds(60)
        engine.tick()
        engine.beginExtension(only().id)
        engine.cancelExtension(only().id)
        assertEquals(SessionState.TIME_EXPIRED, only().state)
    }

    @Test
    fun `extension disallowed by rule is rejected`() {
        rules[ig] = AppRule(ig, "Instagram", extensionAllowed = false)
        startActiveSession(seconds = 60)
        clock.advanceSeconds(60)
        engine.tick()
        assertFailsWith<EngineException> { engine.beginExtension(only().id) }
    }

    @Test
    fun `back on expiry snoozes briefly without resetting the timer or counting an extension`() {
        startActiveSession(seconds = 60)
        clock.advanceSeconds(60)
        engine.tick()
        val fx = engine.snoozeExpired(only().id)
        val s = only()
        assertEquals(SessionState.SESSION_ACTIVE, s.state)
        assertEquals(clock.now + 60_000, s.plannedEndAt)
        assertEquals(0, s.extensionCount)
        clock.advanceSeconds(60)
        assertTrue(engine.tick().has<EngineEffect.ShowExpired>(), "checkpoint comes back")
        assertTrue(fx.has<EngineEffect.HideCheckpoint>())
    }

    @Test
    fun `leaving at the expiry checkpoint completes as left`() {
        startActiveSession(seconds = 60)
        clock.advanceSeconds(60)
        engine.tick()
        engine.onForegroundChanged(launcher)
        assertEquals(SessionState.SESSION_COMPLETED, only().state)
        assertEquals(CompletionReason.LEFT, only().completionReason)
    }

    // --- completion --------------------------------------------------------------------------

    @Test
    fun `done while in front completes, records wall clock and shows completion card`() {
        val s = startActiveSession()
        clock.advanceSeconds(200)
        val fx = engine.complete(s.id, CompletionReason.DONE)
        val done = only()
        assertEquals(SessionState.SESSION_COMPLETED, done.state)
        assertEquals(200, done.wallClockSeconds)
        assertTrue(fx.has<EngineEffect.ShowCompletion>())
        assertTrue(fx.has<EngineEffect.HideReminder>())
        assertNull(alarmOf(fx))
    }

    @Test
    fun `leave completes and goes home`() {
        val s = startActiveSession()
        val fx = engine.complete(s.id, CompletionReason.LEFT)
        assertTrue(fx.has<EngineEffect.GoHome>())
    }

    @Test
    fun `after completion the next open is a fresh checkpoint`() {
        val s = startActiveSession()
        engine.complete(s.id, CompletionReason.DONE)
        engine.onForegroundChanged(wa)
        engine.onForegroundChanged(ig)
        assertEquals(2, store.sessions.size)
    }

    @Test
    fun `terminal sessions reject further commands`() {
        val s = startActiveSession()
        engine.complete(s.id, CompletionReason.DONE)
        assertFailsWith<EngineException> { engine.complete(s.id, CompletionReason.DONE) }
        assertFailsWith<EngineException> { engine.requestExtension(s.id, null, 60) }
        val e = assertFailsWith<EngineException> { engine.complete("nope", CompletionReason.DONE) }
        assertEquals(EngineException.Code.SESSION_NOT_FOUND, e.code)
    }

    // --- process death / reboot --------------------------------------------------------------

    @Test
    fun `restore after process death keeps the session and resumes on next poll`() {
        val s = startActiveSession(seconds = 600)
        clock.advanceSeconds(30)
        engine = newEngine() // process died; only the store survives
        engine.restore()
        assertEquals(SessionState.BACKGROUND_GRACE, only().state)
        val fx = engine.onForegroundChanged(ig)
        val resumed = only()
        assertEquals(s.id, resumed.id)
        assertEquals(SessionState.SESSION_ACTIVE, resumed.state)
        assertEquals(s.plannedEndAt, resumed.plannedEndAt)
        assertEquals("Reply to Ravi", resumed.intention)
        assertTrue(fx.has<EngineEffect.ShowReminder>())
    }

    @Test
    fun `restore recomputes expiry against wall clock rather than resuming blindly`() {
        startActiveSession(seconds = 120)
        clock.advanceSeconds(300) // service was dead through the planned end
        engine = newEngine()
        engine.restore()
        val fx = engine.onForegroundChanged(ig)
        assertEquals(SessionState.TIME_EXPIRED, only().state)
        assertTrue(fx.has<EngineEffect.ShowExpired>())
    }

    @Test
    fun `restore abandons a session that died mid-pause`() {
        engine.onForegroundChanged(ig)
        engine = newEngine()
        engine.restore()
        assertEquals(SessionState.SESSION_ABANDONED, only().state)
        engine.onForegroundChanged(ig)
        assertEquals(2, store.sessions.size, "next detection re-fires cleanly")
    }

    @Test
    fun `restore keeps an unresolved intention checkpoint and re-shows it`() {
        engine.onForegroundChanged(ig)
        engine.onPauseElapsed(only().id)
        engine = newEngine()
        engine.restore()
        val fx = engine.onForegroundChanged(ig)
        assertEquals(1, store.sessions.size)
        assertTrue(fx.has<EngineEffect.ShowIntentionForm>())
    }

    @Test
    fun `restore keeps the expiry alarm armed even before the first poll`() {
        val s = startActiveSession(seconds = 600)
        engine = newEngine()
        val fx = engine.restore()
        assertEquals(s.plannedEndAt, alarmOf(fx), "poll loop may be dead; the alarm is the only wake-up")
        val fx2 = engine.onForegroundChanged(ig)
        assertNotNull(alarmOf(fx2))
    }

    @Test
    fun `alarm is cleared once the planned end has passed`() {
        startActiveSession(seconds = 60)
        engine.onForegroundChanged(wa)
        clock.advanceSeconds(61)
        assertNull(alarmOf(engine.tick().ifEmpty { engine.restore() }))
    }

    @Test
    fun `stale unresolved checkpoints are abandoned by tick`() {
        engine.onForegroundChanged(ig)
        engine.onPauseElapsed(only().id)
        engine = newEngine()
        engine.restore()
        clock.advanceSeconds(31 * 60)
        engine.tick()
        assertEquals(SessionState.SESSION_ABANDONED, only().state)
    }

    @Test
    fun `boot sweep closes every open session as device_restarted`() {
        startActiveSession()
        rules[wa] = AppRule(wa, "WhatsApp")
        engine.onForegroundChanged(wa) // ig -> grace, wa -> pause
        engine = newEngine()
        val fx = engine.bootSweep()
        assertTrue(store.sessions.values.all { it.state == SessionState.SESSION_ABANDONED })
        assertTrue(store.sessions.values.all { it.completionReason == CompletionReason.DEVICE_RESTARTED })
        assertNull(alarmOf(fx))
    }

    @Test
    fun `device_restarted cannot be requested through complete`() {
        val s = startActiveSession()
        assertFailsWith<EngineException> { engine.complete(s.id, CompletionReason.DEVICE_RESTARTED) }
    }

    // --- multiple apps -----------------------------------------------------------------------

    @Test
    fun `switching directly between two monitored apps graces one and checkpoints the other`() {
        rules[wa] = AppRule(wa, "WhatsApp")
        val s = startActiveSession()
        val fx = engine.onForegroundChanged(wa)
        assertEquals(SessionState.BACKGROUND_GRACE, store.get(s.id)!!.state)
        assertTrue(fx.has<EngineEffect.ShowPause>())
        assertEquals(wa, (fx.filterIsInstance<EngineEffect.ShowPause>().single()).session.packageName)
    }

    @Test
    fun `alarm tracks the earliest active planned end`() {
        rules[wa] = AppRule(wa, "WhatsApp")
        startActiveSession(seconds = 600)
        engine.onForegroundChanged(wa)
        val waId = store.findOpenForPackage(wa)!!.id
        val fx = engine.submitIntention(waId, "x", 60)
        assertEquals(clock.now + 60_000, alarmOf(fx))
    }

    @Test
    fun `session toString never leaks the intention`() {
        val s = startActiveSession(intention = "secret plans")
        assertTrue("secret plans" !in s.toString())
        assertTrue("secret plans" !in EngineEffect.ShowReminder(s, true).toString())
    }

    @Test
    fun `warning levels are derived from planned end`() {
        val s = startActiveSession(seconds = 600)
        assertEquals(WarningLevel.NONE, s.warningLevel(clock.now))
        assertEquals(WarningLevel.TWO_MINUTES, s.warningLevel(s.plannedEndAt!! - 120_000))
        assertEquals(WarningLevel.THIRTY_SECONDS, s.warningLevel(s.plannedEndAt!! - 30_000))
    }
}
