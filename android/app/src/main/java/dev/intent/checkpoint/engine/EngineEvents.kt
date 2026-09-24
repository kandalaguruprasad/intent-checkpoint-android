package dev.intent.checkpoint.engine

import android.content.Context
import android.content.Intent
import dev.intent.checkpoint.permissions.PermissionSnapshot
import dev.intent.checkpoint.sessions.SessionJson
import dev.intent.core.Session
import org.json.JSONObject

/**
 * Engine -> RN events (PRD §25 `NativeEvent`). Delivered as a package-scoped broadcast from the
 * `:engine` process to the RN process; the module re-emits them to JS. Same-UID only: the RN
 * side registers with RECEIVER_NOT_EXPORTED.
 */
class EngineEvents(private val context: Context) {
    fun foregroundAppChanged(packageName: String?) =
        send(JSONObject().put("type", "foregroundAppChanged").put("packageName", packageName ?: JSONObject.NULL))

    fun monitoringStarted() = send(JSONObject().put("type", "monitoringStarted"))

    fun monitoringStopped() = send(JSONObject().put("type", "monitoringStopped"))

    fun permissionChanged(p: PermissionSnapshot) = send(
        JSONObject().put("type", "permissionChanged").put(
            "state",
            JSONObject()
                .put("usageAccess", p.usageAccess)
                .put("overlay", p.overlay)
                .put("notifications", p.notifications)
                .put("batteryUnrestricted", p.batteryUnrestricted)
                .put("exactAlarms", p.exactAlarms),
        ),
    )

    fun sessionStateChanged(s: Session) =
        send(JSONObject().put("type", "sessionStateChanged").put("session", SessionJson.toJson(s)))

    fun timerExpired(sessionId: String) =
        send(JSONObject().put("type", "timerExpired").put("sessionId", sessionId))

    fun sessionRestored(s: Session?) =
        send(JSONObject().put("type", "sessionRestored").put("session", s?.let(SessionJson::toJson) ?: JSONObject.NULL))

    private fun send(payload: JSONObject) {
        context.sendBroadcast(
            Intent(ACTION).setPackage(context.packageName).putExtra(EXTRA_PAYLOAD, payload.toString()),
        )
    }

    companion object {
        const val ACTION = "dev.intent.checkpoint.ENGINE_EVENT"
        const val EXTRA_PAYLOAD = "payload"
    }
}
