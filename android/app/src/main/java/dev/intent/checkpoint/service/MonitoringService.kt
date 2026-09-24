package dev.intent.checkpoint.service

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dev.intent.checkpoint.engine.EngineHost
import dev.intent.checkpoint.engine.EngineLog

/**
 * `specialUse` foreground service hosting the detection loop (PRD §27, ADR-003). Runs in the
 * `:engine` process. It owns only the loop's lifetime and the honest, persistent notification;
 * all state lives in [EngineHost] + SQLite, so an OS restart (START_STICKY) recomputes sessions
 * from the database instead of resuming blindly.
 */
class MonitoringService : Service() {
    private lateinit var host: EngineHost
    private lateinit var notifications: MonitoringNotification
    private var stoppedByUser = false

    override fun onCreate() {
        super.onCreate()
        host = EngineHost.get(this)
        notifications = MonitoringNotification(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stoppedByUser = true
                host.setSessionListener(null)
                host.stopPolling(userInitiated = true)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_END_SESSION -> intent.getStringExtra(EXTRA_SESSION_ID)?.let(host::onEnd)
        }
        if (!enterForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        host.setSessionListener { session -> notifications.update(session) }
        host.startPolling()
        return START_STICKY
    }

    override fun onDestroy() {
        if (!stoppedByUser) {
            // System stop: keep monitoring "enabled" so boot/alarms bring it back.
            host.setSessionListener(null)
            host.stopPolling(userInitiated = false)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun enterForeground(): Boolean = try {
        val notification = notifications.build(null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(MonitoringNotification.ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(MonitoringNotification.ID, notification)
        }
        true
    } catch (e: RuntimeException) {
        // ForegroundServiceStartNotAllowedException (12+) or a missing FGS-type permission.
        EngineLog.w("could not enter foreground", e)
        false
    }

    companion object {
        const val ACTION_START = "dev.intent.checkpoint.action.START_MONITORING"
        const val ACTION_STOP = "dev.intent.checkpoint.action.STOP_MONITORING"
        const val ACTION_END_SESSION = "dev.intent.checkpoint.action.END_SESSION"
        const val EXTRA_SESSION_ID = "sessionId"

        /**
         * Safe from any process. Returns false when Android refuses a background FGS start (e.g.
         * an inexact alarm fired while we are not allowed to start one); the next user launch or
         * exact alarm retries.
         */
        fun start(context: Context): Boolean = try {
            context.startForegroundService(Intent(context, MonitoringService::class.java).setAction(ACTION_START))
            true
        } catch (e: IllegalStateException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e is ForegroundServiceStartNotAllowedException) {
                EngineLog.w("background FGS start not allowed", e)
            } else {
                EngineLog.w("FGS start failed", e)
            }
            false
        }

        fun stop(context: Context) {
            context.startService(Intent(context, MonitoringService::class.java).setAction(ACTION_STOP))
        }
    }
}
