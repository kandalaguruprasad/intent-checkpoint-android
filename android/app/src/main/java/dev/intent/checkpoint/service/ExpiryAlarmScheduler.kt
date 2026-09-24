package dev.intent.checkpoint.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dev.intent.checkpoint.engine.EngineLog
import dev.intent.checkpoint.permissions.PermissionChecker

/**
 * Doze-safe backstop for timer expiry (PRD §29, P0-010). The poll loop catches expiry within one
 * interval while the service is alive; this alarm covers the service being throttled or killed.
 *
 * Exact alarms need SCHEDULE_EXACT_ALARM, which is denied by default for new installs on
 * Android 14+. Without it we fall back to an inexact while-idle alarm, which Doze may defer by
 * minutes. (USE_EXACT_ALARM is not an option: Play restricts it to alarm-clock/calendar apps.)
 */
class ExpiryAlarmScheduler(private val context: Context) {
    private val alarms = context.getSystemService(AlarmManager::class.java)

    fun sync(atMs: Long?) {
        val pi = pendingIntent()
        if (atMs == null) {
            alarms?.cancel(pi)
            return
        }
        try {
            if (PermissionChecker.canScheduleExactAlarms(context)) {
                alarms?.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
            } else {
                alarms?.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
            }
        } catch (e: SecurityException) {
            // Exact-alarm access revoked between the check and the call.
            EngineLog.w("exact alarm rejected, falling back to inexact", e)
            alarms?.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
        }
    }

    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, ExpiryAlarmReceiver::class.java).setAction(ACTION_EXPIRY),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val ACTION_EXPIRY = "dev.intent.checkpoint.action.EXPIRY_ALARM"
        private const val REQUEST_CODE = 1001
    }
}
