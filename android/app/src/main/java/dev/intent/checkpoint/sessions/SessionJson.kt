package dev.intent.checkpoint.sessions

import dev.intent.checkpoint.monitoring.LaunchableApp
import dev.intent.core.DailySummary
import dev.intent.core.LatencySummary
import dev.intent.core.Session
import org.json.JSONArray
import org.json.JSONObject

/**
 * Wire format between the `:engine` process and the RN module. Field names mirror the
 * TypeScript types in src/native/types.ts.
 */
object SessionJson {
    fun toJson(s: Session, nowMs: Long = System.currentTimeMillis()): JSONObject = JSONObject().apply {
        put("id", s.id)
        put("packageName", s.packageName)
        put("appName", s.appName)
        put("intention", s.intention)
        put("startedAt", s.startedAt ?: JSONObject.NULL)
        put("plannedDurationSeconds", s.plannedDurationSeconds ?: JSONObject.NULL)
        put("plannedEndAt", s.plannedEndAt ?: JSONObject.NULL)
        put("endedAt", s.endedAt ?: JSONObject.NULL)
        put("wallClockSeconds", s.wallClockSeconds ?: JSONObject.NULL)
        put("foregroundSeconds", s.foregroundMsAt(nowMs) / 1000)
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

    fun toJson(d: DailySummary): JSONObject = JSONObject().apply {
        put("opensNoticed", d.opensNoticed)
        put("intentionalSessions", d.intentionalSessions)
        put("choseNotToOpen", d.choseNotToOpen)
        put("plannedSeconds", d.plannedSeconds)
        put("actualSecondsTimed", d.actualSecondsTimed)
        put("actualSecondsAll", d.actualSecondsAll)
        put("extensions", d.extensions)
        put("finishedOnTime", d.finishedOnTime)
        put(
            "perApp",
            JSONArray(
                d.perApp.map {
                    JSONObject()
                        .put("packageName", it.packageName)
                        .put("appName", it.appName)
                        .put("opens", it.opens)
                        .put("actualSeconds", it.actualSeconds)
                },
            ),
        )
    }

    fun monitoredToJson(rows: List<MonitoredAppRow>): JSONArray = JSONArray(
        rows.map { JSONObject().put("packageName", it.packageName).put("appName", it.appName).put("category", it.category) },
    )

    fun monitoredFromJson(json: String): List<MonitoredAppRow> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            MonitoredAppRow(o.getString("packageName"), o.getString("appName"), o.optString("category", "other"))
        }
    }

    fun toJson(a: LaunchableApp): JSONObject = JSONObject().apply {
        put("packageName", a.packageName)
        put("label", a.label)
        put("category", a.category.wire)
        put("sensitive", a.sensitive)
        put("icon", a.iconDataUri ?: JSONObject.NULL)
    }
}
