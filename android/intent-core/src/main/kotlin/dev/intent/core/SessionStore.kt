package dev.intent.core

/**
 * Durable session storage. The Android implementation is SQLite-backed and is the single source
 * of truth for session state (PRD §30); the engine never keeps session state only in memory.
 */
interface SessionStore {
    fun insert(session: Session)
    fun update(session: Session)
    fun get(id: String): Session?

    /** The non-terminal session for [packageName], if any. At most one exists per package. */
    fun findOpenForPackage(packageName: String): Session?

    fun findAllOpen(): List<Session>

    fun insertExtension(extension: SessionExtension)
}

fun interface Clock {
    fun nowMs(): Long
}

fun interface IdGenerator {
    fun newId(): String
}
