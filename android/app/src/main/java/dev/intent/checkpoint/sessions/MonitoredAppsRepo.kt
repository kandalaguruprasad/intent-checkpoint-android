package dev.intent.checkpoint.sessions

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import dev.intent.core.NeverMonitor

data class MonitoredAppRow(val packageName: String, val appName: String, val category: String)

/** Writes to `monitored_apps` / `app_rules`. Engine thread only; call [onChanged] after writes. */
class MonitoredAppsRepo(private val db: IntentDatabase, private val onChanged: () -> Unit) {

    fun list(): List<MonitoredAppRow> =
        db.readableDatabase.rawQuery(
            "SELECT package_name, app_name, category FROM monitored_apps WHERE monitoring_enabled = 1 ORDER BY added_at",
            null,
        ).use { c -> buildList { while (c.moveToNext()) add(MonitoredAppRow(c.getString(0), c.getString(1), c.getString(2))) } }

    /**
     * Replaces the monitored set. Removed apps lose their rules (FK cascade) but keep their session
     * history, since sessions deliberately have no FK to monitored_apps (PRD §64). Existing rules for
     * apps that stay selected are preserved.
     */
    fun replaceAll(apps: List<MonitoredAppRow>, ownPackage: String) {
        val wanted = apps
            .filter { it.packageName != ownPackage && !NeverMonitor.contains(it.packageName) }
            .associateBy { it.packageName }
        val w = db.writableDatabase
        w.beginTransaction()
        try {
            val existing = w.rawQuery("SELECT package_name FROM monitored_apps", null)
                .use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
            for (pkg in existing - wanted.keys) {
                w.delete("monitored_apps", "package_name = ?", arrayOf(pkg))
            }
            val now = System.currentTimeMillis()
            for (app in wanted.values) {
                if (app.packageName in existing) {
                    w.update(
                        "monitored_apps",
                        ContentValues().apply {
                            put("app_name", app.appName)
                            put("category", app.category)
                            put("monitoring_enabled", 1)
                        },
                        "package_name = ?",
                        arrayOf(app.packageName),
                    )
                } else {
                    w.insertOrThrow(
                        "monitored_apps",
                        null,
                        ContentValues().apply {
                            put("package_name", app.packageName)
                            put("app_name", app.appName)
                            put("category", app.category)
                            put("added_at", now)
                            put("monitoring_enabled", 1)
                        },
                    )
                    w.insertWithOnConflict(
                        "app_rules",
                        null,
                        IntentDatabase.defaultRuleValues(app.packageName),
                        SQLiteDatabase.CONFLICT_IGNORE,
                    )
                }
            }
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
        onChanged()
    }
}
