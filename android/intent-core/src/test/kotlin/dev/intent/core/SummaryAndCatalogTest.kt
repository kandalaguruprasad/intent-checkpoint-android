package dev.intent.core

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SummaryAndCatalogTest {
    private val t0 = 1_700_000_000_000L

    private fun session(
        id: String,
        pkg: String = "com.instagram.android",
        started: Boolean = true,
        planned: Long? = 600,
        state: SessionState = SessionState.SESSION_COMPLETED,
        wall: Long? = 300,
        fgMs: Long = 300_000,
        ext: Int = 0,
        activeSince: Long? = null,
    ) = Session(
        id = id, packageName = pkg, appName = pkg.substringAfterLast('.'), intention = "x",
        startedAt = if (started) t0 else null, plannedDurationSeconds = if (started) planned else null,
        plannedEndAt = null, endedAt = null, wallClockSeconds = if (started) wall else null,
        extensionCount = ext, state = state,
        completionReason = if (state.isTerminal) CompletionReason.DONE else null,
        backgroundedAt = null, createdAt = t0, updatedAt = t0, foregroundMs = fgMs, activeSince = activeSince,
    )

    @Test
    fun `summary counts come straight from session rows`() {
        val s = DailySummary.of(
            listOf(
                session("a"),
                session("b", planned = 300, wall = 900, fgMs = 600_000, ext = 2),
                session("c", planned = null, fgMs = 120_000),
                session("d", started = false, state = SessionState.SESSION_ABANDONED, fgMs = 0),
                session("e", pkg = "com.reddit.frontpage", state = SessionState.SESSION_ACTIVE, fgMs = 60_000, activeSince = t0),
            ),
            nowMs = t0 + 30_000,
        )
        assertEquals(5, s.opensNoticed)
        assertEquals(4, s.intentionalSessions)
        assertEquals(1, s.choseNotToOpen)
        assertEquals(600 + 300 + 600, s.plannedSeconds.toInt())
        assertEquals(300 + 600 + 90, s.actualSecondsTimed.toInt(), "running interval counted, no-timer excluded")
        assertEquals(300 + 600 + 120 + 90, s.actualSecondsAll.toInt())
        assertEquals(2, s.extensions)
        assertEquals(1, s.finishedOnTime, "only 'a': 'b' was extended, 'e' is still running")
        assertEquals(listOf("com.instagram.android", "com.reddit.frontpage"), s.perApp.map { it.packageName })
        assertEquals(4, s.perApp.first().opens)
    }

    @Test
    fun `empty day is all zeros`() {
        val s = DailySummary.of(emptyList(), t0)
        assertEquals(0, s.opensNoticed)
        assertTrue(s.perApp.isEmpty())
    }

    @Test
    fun `known packages beat declared category, declared beats other`() {
        assertEquals(AppCategory.SOCIAL, AppCategory.of("com.instagram.android", null))
        assertEquals(AppCategory.VIDEO, AppCategory.of("com.google.android.youtube", 7))
        assertEquals(AppCategory.GAMES, AppCategory.of("com.example.game", 0))
        assertEquals(AppCategory.OTHER, AppCategory.of("com.example.notes", 7))
        assertEquals(AppCategory.OTHER, AppCategory.of("com.example.x", null))
    }

    @Test
    fun `sensitive heuristics`() {
        assertTrue(SensitiveApps.isSensitive("com.phonepe.app", "PhonePe"))
        assertTrue(SensitiveApps.isSensitive("com.example.mybank", "My Bank"))
        assertTrue(SensitiveApps.isSensitive("com.example.auth", "Authenticator"))
        assertTrue(SensitiveApps.isSensitive("com.example.x", "Google Pay"))
        assertFalse(SensitiveApps.isSensitive("com.instagram.android", "Instagram"))
        assertFalse(SensitiveApps.isSensitive("com.spotify.music", "Spotify"), "substring 'pay' in 'spotify' must not match")
        assertFalse(SensitiveApps.isSensitive("com.google.android.youtube", "YouTube"))
    }
}
