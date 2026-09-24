package dev.intent.checkpoint.permissions

import android.Manifest
import android.app.AlarmManager
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings

data class PermissionSnapshot(
    val usageAccess: Boolean,
    val overlay: Boolean,
    val notifications: Boolean,
    val batteryUnrestricted: Boolean,
    val exactAlarms: Boolean,
)

/** Direct platform checks; safe to call from any process or thread (PRD §24). */
object PermissionChecker {
    fun snapshot(context: Context) = PermissionSnapshot(
        usageAccess = hasUsageAccess(context),
        overlay = canDrawOverlays(context),
        notifications = canPostNotifications(context),
        batteryUnrestricted = isIgnoringBatteryOptimizations(context),
        exactAlarms = canScheduleExactAlarms(context),
    )

    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Deprecated in the API 36 stubs with no drop-in replacement down to minSdk 26.
            @Suppress("DEPRECATION")
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return if (mode == AppOpsManager.MODE_DEFAULT) {
            // MODE_DEFAULT defers to the permission itself (some OEM builds report it this way).
            context.checkCallingOrSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            mode == AppOpsManager.MODE_ALLOWED
        }
    }

    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun canPostNotifications(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    /** Best-effort only: OEM battery managers can still kill us when this is true (PRD §45). */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) ?: false

    fun canScheduleExactAlarms(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() ?: false
        } else {
            true
        }
}
