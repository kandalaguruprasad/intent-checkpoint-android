package dev.intent.checkpoint.bridge

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import dev.intent.checkpoint.bridge.EngineProtocol as P
import dev.intent.checkpoint.engine.EngineHost
import dev.intent.checkpoint.engine.EngineLog
import dev.intent.checkpoint.sessions.SessionJson
import dev.intent.core.CompletionReason
import dev.intent.core.EngineException
import org.json.JSONArray

/**
 * Synchronous RPC into the `:engine` process via [ContentProvider.call]: no AIDL, not exported,
 * same-UID only. This keeps the Kotlin session repository the single writer (PRD §25, §30) while
 * RN lives in a different process.
 *
 * Deliberately touches nothing in onCreate(): providers are created before Application.onCreate
 * for every process start, including boot, and the engine must choose restore vs boot sweep.
 */
class EngineProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        if (Binder.getCallingUid() != Process.myUid()) throw SecurityException("same-app only")
        val host = EngineHost.get(context!!)
        val x = extras ?: Bundle.EMPTY
        return try {
            when (method) {
                P.GET_ACTIVE_SESSION -> Bundle().apply {
                    host.activeSession()?.let { putString(P.KEY_JSON, SessionJson.toJson(it).toString()) }
                }
                P.SUBMIT_INTENTION -> {
                    val seconds = if (x.containsKey(P.KEY_PLANNED_SECONDS)) x.getLong(P.KEY_PLANNED_SECONDS) else null
                    host.submitIntention(x.required(P.KEY_SESSION_ID), x.getString(P.KEY_INTENTION) ?: "", seconds)
                    Bundle()
                }
                P.REQUEST_EXTENSION -> {
                    host.requestExtension(
                        x.required(P.KEY_SESSION_ID),
                        x.getString(P.KEY_REASON),
                        x.getLong(P.KEY_EXTRA_SECONDS),
                    )
                    Bundle()
                }
                P.COMPLETE_SESSION -> {
                    val reason = CompletionReason.fromWire(x.getString(P.KEY_REASON))
                        ?: throw EngineException(EngineException.Code.INVALID_ARGUMENT, "unknown reason")
                    host.completeSession(x.required(P.KEY_SESSION_ID), reason)
                    Bundle()
                }
                P.GET_LATENCY_STATS -> Bundle().apply {
                    putString(P.KEY_JSON, SessionJson.toJson(host.latencySummary()).toString())
                }
                P.CLEAR_LATENCY_STATS -> {
                    host.clearLatency()
                    Bundle()
                }
                P.GET_MONITORING_STATUS -> Bundle().apply {
                    putBoolean(P.KEY_ENABLED, host.isMonitoringEnabled)
                    putBoolean(P.KEY_RUNNING, host.isPolling)
                    putStringArrayList(P.KEY_PACKAGES, ArrayList(host.monitoredPackages()))
                }
                P.GET_SUMMARY -> Bundle().apply {
                    putString(P.KEY_JSON, SessionJson.toJson(host.summary(x.getLong(P.KEY_FROM), x.getLong(P.KEY_TO))).toString())
                }
                P.LIST_SESSIONS -> Bundle().apply {
                    val now = System.currentTimeMillis()
                    val list = host.sessions(x.getLong(P.KEY_FROM), x.getLong(P.KEY_TO), x.getInt(P.KEY_LIMIT, 200))
                    putString(P.KEY_JSON, JSONArray(list.map { SessionJson.toJson(it, now) }).toString())
                }
                P.GET_MONITORED_APPS -> Bundle().apply {
                    putString(P.KEY_JSON, SessionJson.monitoredToJson(host.monitoredApps()).toString())
                }
                P.SET_MONITORED_APPS -> {
                    host.setMonitoredApps(SessionJson.monitoredFromJson(x.required(P.KEY_JSON)))
                    Bundle()
                }
                P.GET_ONBOARDING -> Bundle().apply { putBoolean(P.KEY_DONE, host.onboardingComplete) }
                P.SET_ONBOARDING -> {
                    host.onboardingComplete = x.getBoolean(P.KEY_DONE)
                    Bundle()
                }
                P.PREVIEW_OVERLAY -> {
                    host.previewOverlay(x.required(P.KEY_KIND))
                    Bundle()
                }
                else -> error(P.E_UNKNOWN_METHOD, method)
            }
        } catch (e: EngineException) {
            error(e.code.wire, e.message ?: e.code.wire)
        } catch (e: Exception) {
            EngineLog.w("engine call $method failed", e)
            error(P.E_ENGINE_FAILURE, e.javaClass.simpleName)
        }
    }

    private fun Bundle.required(key: String): String =
        getString(key) ?: throw EngineException(EngineException.Code.INVALID_ARGUMENT, "missing $key")

    private fun error(code: String, message: String) = Bundle().apply {
        putString(P.KEY_ERROR_CODE, code)
        putString(P.KEY_ERROR_MESSAGE, message)
    }

    // Not a data provider.
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
