package dev.intent.checkpoint.sessions

import dev.intent.core.LatencySummary
import dev.intent.core.Session
import org.json.JSONObject

/**
 * Wire format between the `:engine` process and the RN module. Field names mirror the
 * TypeScript `SessionRecord` in src/native/types.ts.
 */
object SessionJson {
    fun toJson(s: Session): JSONObject = JSONObject().apply {
        put("id", s.id)
        put("packageName", s.packageName)
        put("appName", s.appName)
        put("intention", s.intention)
        put("startedAt", s.startedAt ?: JSONObject.NULL)
        put("plannedDurationSeconds", s.plannedDurationSeconds ?: JSONObject.NULL)
        put("plannedEndAt", s.plannedEndAt ?: JSONObject.NULL)
        put("endedAt", s.endedAt ?: JSONObject.NULL)
        put("wallClockSeconds", s.wallClockSeconds ?: JSONObject.NULL)
        put("extensionCount", s.extensionCount)
        put("state", s.state.name)
        put("completionReason", s.completionReason?.wire ?: JSONObject.NULL)
        put("createdAt", s.createdAt)
        put("updatedAt", s.updatedAt)
    }

    fun toJson(l: LatencySummary): JSONObject = JSONObject().apply {
        put("count", l.count)
        put("medianMs", l.medianMs ?: JSONObject.NULL)
        put("p95Ms", l.p95Ms ?: JSONObject.NULL)
        put("maxMs", l.maxMs ?: JSONObject.NULL)
    }
}
