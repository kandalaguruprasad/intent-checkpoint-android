package dev.intent.checkpoint.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dev.intent.checkpoint.MainActivity
import dev.intent.checkpoint.R
import dev.intent.core.Session
import dev.intent.core.SessionState

/**
 * The persistent FGS notification (PRD §27). Honest and actionable, never hidden. It shows the
 * app and a live countdown but not the intention text, which stays on the lock-screen-safe side
 * of the privacy line.
 */
class MonitoringNotification(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Intentional use", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while Intent is watching for the apps you chose."
                setShowBadge(false)
            },
        )
    }

    fun build(session: Session?): Notification {
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_intent)
            .setContentTitle("Intentional use is active")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(openApp())

        val live = session?.takeIf { it.state == SessionState.SESSION_ACTIVE || it.state == SessionState.BACKGROUND_GRACE }
        val end = live?.plannedEndAt
        when {
            live != null && end != null && end > System.currentTimeMillis() -> builder
                .setContentText("${live.appName} · time remaining")
                .setWhen(end)
                .setShowWhen(true)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
            live != null -> builder.setContentText("${live.appName} · session in progress").setShowWhen(false)
            else -> builder.setContentText("Waiting for the apps you chose").setShowWhen(false)
        }
        if (live != null) {
            builder.addAction(Notification.Action.Builder(null, "End session", endSession(live.id)).build())
        }
        builder.addAction(Notification.Action.Builder(null, "Open controller", openApp()).build())
        return builder.build()
    }

    /** Thread-safe; called from the engine thread. */
    fun update(session: Session?) {
        manager.notify(ID, build(session))
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun endSession(sessionId: String): PendingIntent = PendingIntent.getService(
        context,
        1,
        Intent(context, MonitoringService::class.java)
            .setAction(MonitoringService.ACTION_END_SESSION)
            .putExtra(MonitoringService.EXTRA_SESSION_ID, sessionId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val ID = 42
        const val CHANNEL_ID = "monitoring"
    }
}
