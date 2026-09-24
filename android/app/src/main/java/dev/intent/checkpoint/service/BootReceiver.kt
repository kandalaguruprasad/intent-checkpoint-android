package dev.intent.checkpoint.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.intent.checkpoint.engine.EngineHost

/**
 * Reboot: sweep sessions that spanned the reboot, then restart monitoring if the user had it on
 * (PRD §43). `specialUse` is not among the FGS types Android 15 bars from BOOT_COMPLETED starts.
 * App update: no sweep (not a reboot); just bring monitoring back.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reason = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> EngineHost.StartReason.BOOT
            Intent.ACTION_MY_PACKAGE_REPLACED -> EngineHost.StartReason.NORMAL
            else -> return
        }
        val app = context.applicationContext
        val pending = goAsync() // keep the process alive until the sweep is written
        EngineHost.get(app, reason).whenReady { enabled ->
            try {
                if (enabled) MonitoringService.start(app)
            } finally {
                pending.finish()
            }
        }
    }
}
