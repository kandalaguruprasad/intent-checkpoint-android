package dev.intent.checkpoint.sessions

import android.content.ContentValues
import android.database.Cursor
import dev.intent.core.CompletionReason
import dev.intent.core.Session
import dev.intent.core.SessionExtension
import dev.intent.core.SessionState
import dev.intent.core.SessionStore

/** SQLite-backed [SessionStore]. Called only from the engine thread. */
class SqliteSessionStore(private val db: IntentDatabase) : SessionStore {

    override fun insert(session: Session) {
        db.writableDatabase.insertOrThrow(TABLE, null, session.toValues())
    }

    override fun update(session: Session) {
        val rows = db.writableDatabase.update(TABLE, session.toValues(), "id = ?", arrayOf(session.id))
        check(rows == 1) { "session ${session.id} not found for update" }
    }

    override fun get(id: String): Session? = queryOne("id = ?", arrayOf(id))

    override fun findOpenForPackage(packageName: String): Session? =
        queryOne("package_name = ? AND state NOT IN ($TERMINAL)", arrayOf(packageName))

    override fun findAllOpen(): List<Session> =
        query("state NOT IN ($TERMINAL)", emptyArray(), orderBy = "created_at ASC")

    override fun insertExtension(extension: SessionExtension) {
        db.writableDatabase.insertOrThrow(
            "session_extensions",
            null,
            ContentValues().apply {
                put("id", extension.id)
                put("session_id", extension.sessionId)
                put("reason", extension.reason)
                put("added_seconds", extension.addedSeconds)
                put("created_at", extension.createdAt)
            },
        )
    }

    // --- read model for the RN screens (not part of the engine's SessionStore contract) --------

    /** Sessions created in `[fromMs, toMs)`, newest first. */
    fun createdBetween(fromMs: Long, toMs: Long, limit: Int = 500): List<Session> =
        query("created_at >= ? AND created_at < ?", arrayOf(fromMs.toString(), toMs.toString()), "created_at DESC", limit.toString())

    /**
     * Last distinct intentions the user typed for [packageName], for one-tap reuse on the
     * checkpoint. Skips the "(unspecified)" placeholder.
     */
    fun recentIntentions(packageName: String, limit: Int = 3): List<String> =
        db.readableDatabase.rawQuery(
            """
            SELECT intention FROM sessions
            WHERE package_name = ? AND started_at IS NOT NULL AND intention != '' AND intention != ?
            GROUP BY intention ORDER BY MAX(started_at) DESC LIMIT ?
            """,
            arrayOf(packageName, Session.UNSPECIFIED_INTENTION, limit.toString()),
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    private fun queryOne(where: String, args: Array<String>): Session? =
        query(where, args, orderBy = "created_at DESC", limit = "1").firstOrNull()

    private fun query(where: String, args: Array<String>, orderBy: String, limit: String? = null): List<Session> =
        db.readableDatabase.query(TABLE, null, where, args, null, null, orderBy, limit).use { c ->
            buildList { while (c.moveToNext()) add(c.toSession()) }
        }

    private fun Session.toValues() = ContentValues().apply {
        put("id", id)
        put("package_name", packageName)
        put("app_name", appName)
        put("intention", intention)
        put("started_at", startedAt)
        put("planned_duration_seconds", plannedDurationSeconds)
        put("planned_end_at", plannedEndAt)
        put("ended_at", endedAt)
        put("wall_clock_seconds", wallClockSeconds)
        put("extension_count", extensionCount)
        put("state", state.name)
        put("completion_reason", completionReason?.wire)
        put("backgrounded_at", backgroundedAt)
        put("created_at", createdAt)
        put("updated_at", updatedAt)
        put("foreground_ms", foregroundMs)
        put("active_since", activeSince)
    }

    private fun Cursor.toSession() = Session(
        id = str("id")!!,
        packageName = str("package_name")!!,
        appName = str("app_name")!!,
        intention = str("intention") ?: "",
        startedAt = long("started_at"),
        plannedDurationSeconds = long("planned_duration_seconds"),
        plannedEndAt = long("planned_end_at"),
        endedAt = long("ended_at"),
        wallClockSeconds = long("wall_clock_seconds"),
        extensionCount = long("extension_count")?.toInt() ?: 0,
        state = SessionState.valueOf(str("state")!!),
        completionReason = CompletionReason.fromWire(str("completion_reason")),
        backgroundedAt = long("backgrounded_at"),
        createdAt = long("created_at")!!,
        updatedAt = long("updated_at")!!,
        foregroundMs = long("foreground_ms") ?: 0,
        activeSince = long("active_since"),
    )

    private fun Cursor.str(col: String): String? =
        getColumnIndexOrThrow(col).let { if (isNull(it)) null else getString(it) }

    private fun Cursor.long(col: String): Long? =
        getColumnIndexOrThrow(col).let { if (isNull(it)) null else getLong(it) }

    private companion object {
        const val TABLE = "sessions"
        const val TERMINAL = "'SESSION_COMPLETED','SESSION_ABANDONED'"
    }
}
