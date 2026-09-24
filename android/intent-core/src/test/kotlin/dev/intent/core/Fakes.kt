package dev.intent.core

class InMemorySessionStore : SessionStore {
    val sessions = linkedMapOf<String, Session>()
    val extensions = mutableListOf<SessionExtension>()

    override fun insert(session: Session) {
        require(session.id !in sessions) { "duplicate id ${session.id}" }
        sessions[session.id] = session
    }

    override fun update(session: Session) {
        require(session.id in sessions) { "unknown id ${session.id}" }
        sessions[session.id] = session
    }

    override fun get(id: String): Session? = sessions[id]

    override fun findOpenForPackage(packageName: String): Session? =
        sessions.values.lastOrNull { it.packageName == packageName && !it.state.isTerminal }

    override fun findAllOpen(): List<Session> = sessions.values.filter { !it.state.isTerminal }

    override fun insertExtension(extension: SessionExtension) {
        extensions += extension
    }
}

class FakeClock(var now: Long = 1_700_000_000_000L) : Clock {
    override fun nowMs(): Long = now
    fun advanceSeconds(s: Long) { now += s * 1000 }
}

class SequentialIds : IdGenerator {
    private var n = 0
    override fun newId(): String = "id-${++n}"
}
