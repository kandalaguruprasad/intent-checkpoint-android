package dev.intent.checkpoint.sessions

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import dev.intent.core.LatencySummary

/** Engine-owned key/value settings in `user_settings` (single-process owner, unlike prefs). */
class EngineSettings(private val db: IntentDatabase) {
    var monitoringEnabled: Boolean
        get() = get(KEY_MONITORING_ENABLED) == "1"
        set(value) = put(KEY_MONITORING_ENABLED, if (value) "1" else "0")

    private fun get(key: String): String? =
        db.readableDatabase.query("user_settings", arrayOf("value"), "key = ?", arrayOf(key), null, null, null)
            .use { if (it.moveToFirst()) it.getString(0) else null }

    private fun put(key: String, value: String) {
        db.writableDatabase.insertWithOnConflict(
            "user_settings",
            null,
            ContentValues().apply {
                put("key", key)
                put("value", value)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    private companion object {
        const val KEY_MONITORING_ENABLED = "monitoring_enabled"
    }
}

/** P0-011 detection-latency samples. Stores package + latency only; never session content. */
class LatencyLog(private val db: IntentDatabase) {
    fun record(packageName: String, latencyMs: Long, detectedAt: Long) {
        db.writableDatabase.insert(
            "latency_samples",
            null,
            ContentValues().apply {
                put("package_name", packageName)
                put("latency_ms", latencyMs)
                put("detected_at", detectedAt)
            },
        )
    }

    fun summary(): LatencySummary =
        db.readableDatabase.rawQuery("SELECT latency_ms FROM latency_samples", null).use { c ->
            LatencySummary.of(buildList { while (c.moveToNext()) add(c.getLong(0)) })
        }

    fun clear() {
        db.writableDatabase.delete("latency_samples", null, null)
    }
}
