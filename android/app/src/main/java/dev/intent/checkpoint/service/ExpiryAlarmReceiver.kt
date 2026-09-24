package dev.intent.checkpoint.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.intent.checkpoint.engine.EngineHost

/**
 * Timer backstop (P0-010). Fires with the RN process dead and even with the engine process dead:
 * the process is started for this receiver, the engine restores from SQLite, polls once, and
 * shows the expiry checkpoint if the target app is in front. An exact alarm also grants a
 * temporary allowance to restart the foreground service.
 */
class ExpiryAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ExpiryAlarmScheduler.ACTION_EXPIRY) return
        val app = context.applicationContext
        val pending = goAsync()
        val host = EngineHost.get(app)
        host.onExpiryAlarm()
        host.whenReady { enabled ->
            try {
                if (enabled) MonitoringService.start(app)
            } finally {
                pending.finish()
            }
        }
    }
}
