package dev.intent.checkpoint.monitoring

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.Base64
import android.util.LruCache
import dev.intent.core.AppCategory
import dev.intent.core.NeverMonitor
import dev.intent.core.SensitiveApps
import java.io.ByteArrayOutputStream

data class LaunchableApp(
    val packageName: String,
    val label: String,
    val category: AppCategory,
    val sensitive: Boolean,
    /** PNG data URI, small enough to ship across the bridge. */
    val iconDataUri: String?,
)

/**
 * Installed apps the user can open from the launcher. Visibility comes from the manifest's
 * `<queries>` LAUNCHER intent, not QUERY_ALL_PACKAGES (ADR-009, revised in ADR-012).
 */
object AppCatalog {
    private const val ICON_PX = 96
    private val iconCache = LruCache<String, String>(256)

    fun launchable(context: Context): List<LaunchableApp> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .asSequence()
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName != context.packageName && !NeverMonitor.contains(it.packageName) }
            .map { info ->
                val label = pm.getApplicationLabel(info).toString()
                LaunchableApp(
                    packageName = info.packageName,
                    label = label,
                    category = AppCategory.of(info.packageName, info.category),
                    sensitive = SensitiveApps.isSensitive(info.packageName, label),
                    iconDataUri = iconDataUri(context, info.packageName),
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    fun iconDataUri(context: Context, packageName: String): String? {
        iconCache.get(packageName)?.let { return it }
        val drawable = AppIcons.load(context, packageName) ?: return null
        val out = ByteArrayOutputStream()
        drawable.toBitmap(ICON_PX).compress(Bitmap.CompressFormat.PNG, 100, out)
        val uri = "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        iconCache.put(packageName, uri)
        return uri
    }

    private fun Drawable.toBitmap(px: Int): Bitmap {
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        setBounds(0, 0, px, px)
        draw(canvas)
        return bmp
    }
}

object AppIcons {
    fun load(context: Context, packageName: String): Drawable? =
        try {
            context.packageManager.getApplicationIcon(packageName)
        } catch (_: Exception) {
            null // uninstalled, or not visible to us
        }
}
