package dev.intent.checkpoint.permissions

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Deep links to the special-access settings screens. None of these permissions has an in-app
 * grant dialog; the user is walked to Settings after an education screen (PRD §36).
 */
object SettingsIntents {
    fun openUsageAccess(context: Context) = open(
        context,
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).setData(packageUri(context)),
        fallback = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
    )

    fun openOverlay(context: Context) = open(
        context,
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri(context)),
    )

    /**
     * The list screen, not ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS: the direct-request
     * permission is Play-restricted to a narrow set of app categories.
     */
    fun openBatteryOptimization(context: Context) = open(
        context,
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        fallback = appDetails(context),
    )

    fun openExactAlarm(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            open(context, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri(context)))
        } else {
            open(context, appDetails(context))
        }
    }

    private fun packageUri(context: Context): Uri = Uri.parse("package:${context.packageName}")

    private fun appDetails(context: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context))

    private fun open(context: Context, intent: Intent, fallback: Intent = appDetails(context)) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            // Some OEM builds strip the specific screen; app details always exists.
            context.startActivity(fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
