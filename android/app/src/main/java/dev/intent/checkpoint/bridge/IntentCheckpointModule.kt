package dev.intent.checkpoint.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.WritableArray
import com.facebook.react.bridge.WritableMap
import dev.intent.checkpoint.bridge.EngineProtocol as P
import dev.intent.checkpoint.engine.EngineEvents
import dev.intent.checkpoint.monitoring.UsageEventsSource
import dev.intent.checkpoint.permissions.PermissionChecker
import dev.intent.checkpoint.permissions.SettingsIntents
import dev.intent.checkpoint.service.MonitoringService
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * RN-process side of the bridge (PRD §25, ADR-008). Holds no session state: every session
 * read/write is forwarded to the `:engine` process, which owns SQLite. Promise rejections carry
 * typed codes (E_PERMISSION_DENIED, E_SESSION_NOT_FOUND, ...) so JS can render specific UI.
 */
class IntentCheckpointModule(private val reactContext: ReactApplicationContext) :
    NativeIntentCheckpointSpec(reactContext) {

    /** Binder calls to the engine never run on the JS or UI thread. */
    private val io: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "intent-bridge") }
    private val engineUri = Uri.parse("content://${reactContext.packageName}${P.AUTHORITY_SUFFIX}")
    private var listenerCount = 0
    private var receiverRegistered = false

    private val engineEvents = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val payload = intent.getStringExtra(EngineEvents.EXTRA_PAYLOAD) ?: return
            val map = runCatching { JSONObject(payload).toWritableMap() }.getOrNull() ?: return
            if (reactContext.hasActiveReactInstance()) reactContext.emitDeviceEvent(EVENT_NAME, map)
        }
    }

    // --- monitoring lifecycle ----------------------------------------------------------------

    override fun startMonitoring(promise: Promise) = io.runAsync(promise) {
        val p = PermissionChecker.snapshot(reactContext)
        if (!p.usageAccess || !p.overlay) {
            throw BridgeException("E_PERMISSION_DENIED", "Usage Access and Display over other apps are required")
        }
        if (!MonitoringService.start(reactContext)) {
            throw BridgeException("E_START_NOT_ALLOWED", "Android refused to start the monitoring service")
        }
        null
    }

    override fun stopMonitoring(promise: Promise) = io.runAsync(promise) {
        MonitoringService.stop(reactContext)
        null
    }

    override fun getMonitoringStatus(promise: Promise) = io.runAsync(promise) {
        val b = engineCall(P.GET_MONITORING_STATUS)
        Arguments.createMap().apply {
            putBoolean("enabled", b.getBoolean(P.KEY_ENABLED))
            putBoolean("running", b.getBoolean(P.KEY_RUNNING))
            putArray("monitoredPackages", Arguments.fromList(b.getStringArrayList(P.KEY_PACKAGES) ?: arrayListOf<String>()))
        }
    }

    /** Diagnostic: while this screen is open it is, honestly, our own package. */
    override fun getCurrentForegroundApp(promise: Promise) = io.runAsync(promise) {
        if (!PermissionChecker.hasUsageAccess(reactContext)) throw BridgeException("E_PERMISSION_DENIED", "Usage Access not granted")
        val now = System.currentTimeMillis()
        UsageEventsSource(reactContext).latestResume(now - 60 * 60_000L, now, emptySet())?.packageName
    }

    // --- permissions -------------------------------------------------------------------------

    override fun getPermissionState(promise: Promise) = io.runAsync(promise) {
        val p = PermissionChecker.snapshot(reactContext)
        Arguments.createMap().apply {
            putBoolean("usageAccess", p.usageAccess)
            putBoolean("overlay", p.overlay)
            putBoolean("notifications", p.notifications)
            putBoolean("batteryUnrestricted", p.batteryUnrestricted)
            putBoolean("exactAlarms", p.exactAlarms)
        }
    }

    override fun openUsageAccessSettings(promise: Promise) = settings(promise) { SettingsIntents.openUsageAccess(it) }

    override fun openOverlaySettings(promise: Promise) = settings(promise) { SettingsIntents.openOverlay(it) }

    override fun openBatteryOptimizationSettings(promise: Promise) =
        settings(promise) { SettingsIntents.openBatteryOptimization(it) }

    override fun openExactAlarmSettings(promise: Promise) = settings(promise) { SettingsIntents.openExactAlarm(it) }

    // --- sessions (write-through to the engine) ----------------------------------------------

    override fun getActiveSession(promise: Promise) = io.runAsync(promise) {
        engineCall(P.GET_ACTIVE_SESSION).getString(P.KEY_JSON)?.let { JSONObject(it).toWritableMap() }
    }

    override fun submitIntention(sessionId: String, intention: String, plannedSeconds: Double?, promise: Promise) =
        io.runAsync(promise) {
            engineCall(P.SUBMIT_INTENTION, Bundle().apply {
                putString(P.KEY_SESSION_ID, sessionId)
                putString(P.KEY_INTENTION, intention)
                plannedSeconds?.let { putLong(P.KEY_PLANNED_SECONDS, it.toLong()) }
            })
            null
        }

    override fun requestExtension(sessionId: String, reason: String?, extraSeconds: Double, promise: Promise) =
        io.runAsync(promise) {
            engineCall(P.REQUEST_EXTENSION, Bundle().apply {
                putString(P.KEY_SESSION_ID, sessionId)
                putString(P.KEY_REASON, reason)
                putLong(P.KEY_EXTRA_SECONDS, extraSeconds.toLong())
            })
            null
        }

    override fun completeSession(sessionId: String, reason: String, promise: Promise) = io.runAsync(promise) {
        engineCall(P.COMPLETE_SESSION, Bundle().apply {
            putString(P.KEY_SESSION_ID, sessionId)
            putString(P.KEY_REASON, reason)
        })
        null
    }

    // --- Phase 0 instrumentation -------------------------------------------------------------

    override fun getLatencyStats(promise: Promise) = io.runAsync(promise) {
        JSONObject(engineCall(P.GET_LATENCY_STATS).getString(P.KEY_JSON)!!).toWritableMap()
    }

    override fun clearLatencyStats(promise: Promise) = io.runAsync(promise) {
        engineCall(P.CLEAR_LATENCY_STATS)
        null
    }

    // --- events ------------------------------------------------------------------------------

    override fun addListener(eventName: String) {
        listenerCount++
        if (!receiverRegistered) {
            val filter = IntentFilter(EngineEvents.ACTION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                reactContext.registerReceiver(engineEvents, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                reactContext.registerReceiver(engineEvents, filter)
            }
            receiverRegistered = true
        }
    }

    override fun removeListeners(count: Double) {
        listenerCount = (listenerCount - count.toInt()).coerceAtLeast(0)
        if (listenerCount == 0) unregister()
    }

    override fun invalidate() {
        unregister()
        io.shutdown()
        super.invalidate()
    }

    private fun unregister() {
        if (receiverRegistered) {
            runCatching { reactContext.unregisterReceiver(engineEvents) }
            receiverRegistered = false
        }
    }

    // --- helpers -----------------------------------------------------------------------------

    private fun engineCall(method: String, extras: Bundle? = null): Bundle {
        val result = reactContext.contentResolver.call(engineUri, method, null, extras)
            ?: throw BridgeException(P.E_ENGINE_FAILURE, "engine unavailable")
        result.getString(P.KEY_ERROR_CODE)?.let { throw BridgeException(it, result.getString(P.KEY_ERROR_MESSAGE) ?: it) }
        return result
    }

    private fun settings(promise: Promise, open: (Context) -> Unit) {
        try {
            open(reactContext.currentActivity ?: reactContext)
            promise.resolve(null)
        } catch (e: RuntimeException) {
            promise.reject("E_SETTINGS_UNAVAILABLE", e.message, e)
        }
    }

    private fun ExecutorService.runAsync(promise: Promise, block: () -> Any?) {
        execute {
            try {
                promise.resolve(block())
            } catch (e: BridgeException) {
                promise.reject(e.code, e.message, e)
            } catch (e: Exception) {
                promise.reject(P.E_ENGINE_FAILURE, e.message ?: e.javaClass.simpleName, e)
            }
        }
    }

    private class BridgeException(val code: String, message: String) : RuntimeException(message)

    companion object {
        const val NAME = NativeIntentCheckpointSpec.NAME
        const val EVENT_NAME = "IntentCheckpointEvent"
    }
}

private fun JSONObject.toWritableMap(): WritableMap {
    val map = Arguments.createMap()
    for (key in keys()) {
        when (val v = get(key)) {
            JSONObject.NULL -> map.putNull(key)
            is Boolean -> map.putBoolean(key, v)
            is Int -> map.putInt(key, v)
            is Long -> map.putDouble(key, v.toDouble()) // epoch millis exceed Int
            is Double -> map.putDouble(key, v)
            is Number -> map.putDouble(key, v.toDouble())
            is String -> map.putString(key, v)
            is JSONObject -> map.putMap(key, v.toWritableMap())
            is JSONArray -> map.putArray(key, v.toWritableArray())
        }
    }
    return map
}

private fun JSONArray.toWritableArray(): WritableArray {
    val array = Arguments.createArray()
    for (i in 0 until length()) {
        when (val v = get(i)) {
            JSONObject.NULL -> array.pushNull()
            is Boolean -> array.pushBoolean(v)
            is Int -> array.pushInt(v)
            is Number -> array.pushDouble(v.toDouble())
            is String -> array.pushString(v)
            is JSONObject -> array.pushMap(v.toWritableMap())
            is JSONArray -> array.pushArray(v.toWritableArray())
        }
    }
    return array
}
