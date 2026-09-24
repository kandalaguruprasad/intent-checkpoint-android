package dev.intent.checkpoint.sessions

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dev.intent.core.AppRule

/**
 * The durable store (PRD §33). Owned exclusively by the `:engine` process — the RN process never
 * opens this file; it reads through [dev.intent.checkpoint.bridge.EngineProvider].
 *
 * Phase 0 uses a framework SQLiteOpenHelper behind the core's SessionStore interface so the POC
 * carries no annotation-processing toolchain. ADR-005's Room migration lands in Phase 4 behind
 * the same interface; the schema here is the PRD proposal, so that swap is mechanical.
 */
class IntentDatabase(context: Context) :
    SQLiteOpenHelper(context.applicationContext, NAME, null, VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE monitored_apps (
              package_name TEXT PRIMARY KEY,
              app_name TEXT NOT NULL,
              category TEXT NOT NULL,
              added_at INTEGER NOT NULL,
              monitoring_enabled INTEGER NOT NULL DEFAULT 1
            )
            """,
        )
        db.execSQL(
            """
            CREATE TABLE app_rules (
              package_name TEXT PRIMARY KEY REFERENCES monitored_apps(package_name) ON DELETE CASCADE,
              ask_intention INTEGER NOT NULL DEFAULT 1,
              pause_duration_ms INTEGER NOT NULL DEFAULT 1200,
              timer_enabled INTEGER NOT NULL DEFAULT 1,
              default_timer_seconds INTEGER NOT NULL DEFAULT 600,
              custom_timer_allowed INTEGER NOT NULL DEFAULT 1,
              floating_reminder_enabled INTEGER NOT NULL DEFAULT 1,
              warnings_enabled INTEGER NOT NULL DEFAULT 1,
              extension_allowed INTEGER NOT NULL DEFAULT 1,
              reentry_grace_seconds INTEGER NOT NULL DEFAULT 120
            )
            """,
        )
        // Deviation from the PRD sketch: no FK from sessions to monitored_apps. Removing a
        // monitored app must keep its history (PRD §64), which a RESTRICT FK would block.
        // backgrounded_at is added because BACKGROUND_GRACE persists it (PRD §16.3).
        db.execSQL(
            """
            CREATE TABLE sessions (
              id TEXT PRIMARY KEY,
              package_name TEXT NOT NULL,
              app_name TEXT NOT NULL,
              intention TEXT NOT NULL DEFAULT '',
              started_at INTEGER,
              planned_duration_seconds INTEGER,
              planned_end_at INTEGER,
              ended_at INTEGER,
              wall_clock_seconds INTEGER,
              extension_count INTEGER NOT NULL DEFAULT 0,
              state TEXT NOT NULL,
              completion_reason TEXT,
              backgrounded_at INTEGER,
              created_at INTEGER NOT NULL,
              updated_at INTEGER NOT NULL
            )
            """,
        )
        db.execSQL("CREATE INDEX idx_sessions_package_state ON sessions(package_name, state)")
        db.execSQL("CREATE INDEX idx_sessions_created_at ON sessions(created_at)")
        db.execSQL(
            """
            CREATE TABLE session_extensions (
              id TEXT PRIMARY KEY,
              session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
              reason TEXT,
              added_seconds INTEGER NOT NULL,
              created_at INTEGER NOT NULL
            )
            """,
        )
        db.execSQL(
            """
            CREATE TABLE daily_stats (
              date TEXT PRIMARY KEY,
              attempt_count INTEGER NOT NULL DEFAULT 0,
              abandoned_at_checkpoint INTEGER NOT NULL DEFAULT 0,
              sessions_started INTEGER NOT NULL DEFAULT 0,
              planned_seconds_total INTEGER NOT NULL DEFAULT 0,
              actual_seconds_total INTEGER NOT NULL DEFAULT 0,
              extension_count INTEGER NOT NULL DEFAULT 0,
              completed_within_planned INTEGER NOT NULL DEFAULT 0
            )
            """,
        )
        db.execSQL("CREATE TABLE user_settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        // Phase 0 instrumentation for the GO/NO-GO latency gate (P0-011). Package names only.
        db.execSQL(
            """
            CREATE TABLE latency_samples (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              package_name TEXT NOT NULL,
              latency_ms INTEGER NOT NULL,
              detected_at INTEGER NOT NULL
            )
            """,
        )
        seedPocMonitoredApps(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // No released versions yet. Every future bump adds an explicit step here; never drop
        // user history silently (PRD §33, §41).
    }

    /** P0-008: Instagram is the single hardcoded monitored package for the POC. */
    private fun seedPocMonitoredApps(db: SQLiteDatabase) {
        val now = System.currentTimeMillis()
        for ((pkg, name) in POC_MONITORED_APPS) {
            db.insertOrThrow(
                "monitored_apps",
                null,
                ContentValues().apply {
                    put("package_name", pkg)
                    put("app_name", name)
                    put("category", "social")
                    put("added_at", now)
                    put("monitoring_enabled", 1)
                },
            )
            val rule = AppRule(pkg, name)
            db.insertOrThrow(
                "app_rules",
                null,
                ContentValues().apply {
                    put("package_name", pkg)
                    put("ask_intention", rule.askIntention.toInt())
                    put("pause_duration_ms", rule.pauseDurationMs)
                    put("timer_enabled", rule.timerEnabled.toInt())
                    put("default_timer_seconds", rule.defaultTimerSeconds)
                    put("custom_timer_allowed", rule.customTimerAllowed.toInt())
                    put("floating_reminder_enabled", rule.floatingReminderEnabled.toInt())
                    put("warnings_enabled", rule.warningsEnabled.toInt())
                    put("extension_allowed", rule.extensionAllowed.toInt())
                    put("reentry_grace_seconds", rule.reentryGraceSeconds)
                },
            )
        }
    }

    companion object {
        const val NAME = "intent.db"
        const val VERSION = 1

        val POC_MONITORED_APPS = linkedMapOf("com.instagram.android" to "Instagram")
    }
}

internal fun Boolean.toInt(): Int = if (this) 1 else 0
