package dev.intent.checkpoint.engine

import android.content.Context
import dev.intent.checkpoint.overlay.OverlayCallbacks
import dev.intent.checkpoint.overlay.OverlayController
import dev.intent.core.AppRule
import dev.intent.core.CompletionReason
import dev.intent.core.Session
import dev.intent.core.SessionState

/**
 * Clickable design preview of the native overlays with fixture data (design brief, step A).
 * Uses the real overlay code, a separate controller, and never reads or writes sessions, so it is
 * safe to open from Settings or from adb while monitoring is running.
 */
internal class OverlayPreview(context: Context) : OverlayCallbacks {
    private val ctx = context.applicationContext
    private val overlay = OverlayController(ctx, this)
    private var packageName = ctx.packageName

    fun show(kind: String, pkg: String) {
        packageName = pkg
        val now = System.currentTimeMillis()
        when (kind) {
            "pause" -> overlay.showPause(fixture(SessionState.PAUSE, started = false), PAUSE_MS)
            "intention" -> overlay.showIntentionForm(fixture(SessionState.AWAITING_INTENTION, started = false), rule(), RECENT)
            "reminder" -> overlay.showReminder(fixture(SessionState.SESSION_ACTIVE, endInMs = 522_000), true)
            "reminder-warning" -> overlay.showReminder(fixture(SessionState.SESSION_ACTIVE, endInMs = 45_000), true)
            "reminder-no-timer" -> overlay.showReminder(fixture(SessionState.SESSION_ACTIVE, planned = null, startedAgoMs = 724_000), true)
            "timesup" -> overlay.showExpired(fixture(SessionState.TIME_EXPIRED, endInMs = 0), true)
            "extension" -> overlay.showExpired(fixture(SessionState.EXTENSION_REQUEST, endInMs = 0), true)
            "complete" -> overlay.showCompletion(
                fixture(SessionState.SESSION_COMPLETED, endInMs = 0).copy(
                    foregroundMs = 7 * 60_000,
                    endedAt = now,
                    completionReason = CompletionReason.DONE,
                ),
            )
            "hide" -> overlay.hideAll()
        }
    }

    // Preview flow: every button leads to the next surface or closes, never to a real session.
    override fun onPauseElapsed(sessionId: String) = show("intention", packageName)
    override fun onSubmitIntention(sessionId: String, intention: String, plannedSeconds: Long?) {
        overlay.hideCheckpoint()
        overlay.showReminder(
            fixture(SessionState.SESSION_ACTIVE, planned = plannedSeconds, endInMs = plannedSeconds?.times(1000) ?: 0)
                .copy(intention = intention.ifBlank { Session.UNSPECIFIED_INTENTION }),
            true,
        )
    }
    override fun onNotNow(sessionId: String) = overlay.hideAll()
    override fun onDone(sessionId: String) = show("complete", packageName)
    override fun onEnd(sessionId: String) = overlay.hideAll()
    override fun onBeginExtension(sessionId: String) = show("extension", packageName)
    override fun onCancelExtension(sessionId: String) = show("timesup", packageName)
    override fun onRequestExtension(sessionId: String, reason: String?, extraSeconds: Long) = overlay.hideAll()
    override fun onSnoozeExpired(sessionId: String) = overlay.hideAll()

    private fun rule() = AppRule(packageName, appName())

    private fun appName(): String = try {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (_: Exception) {
        "Preview"
    }

    private fun fixture(
        state: SessionState,
        started: Boolean = true,
        planned: Long? = 600,
        endInMs: Long = 0,
        startedAgoMs: Long = 0,
    ): Session {
        val now = System.currentTimeMillis()
        val startedAt = if (!started) null else if (planned != null) now + endInMs - planned * 1000 else now - startedAgoMs
        return Session(
            id = "preview-$state",
            packageName = packageName,
            appName = appName(),
            intention = if (started) "Reply to a message" else "",
            startedAt = startedAt,
            plannedDurationSeconds = if (started) planned else null,
            plannedEndAt = if (started && planned != null) now + endInMs else null,
            endedAt = null,
            wallClockSeconds = null,
            extensionCount = 0,
            state = state,
            completionReason = null,
            backgroundedAt = null,
            createdAt = now,
            updatedAt = now,
        )
    }

    private companion object {
        const val PAUSE_MS = 1_200L
        val RECENT = listOf("Reply to a message", "Check the group chat", "Post a story")
    }
}
