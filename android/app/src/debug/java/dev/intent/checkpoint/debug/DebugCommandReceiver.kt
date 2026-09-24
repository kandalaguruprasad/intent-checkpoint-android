package dev.intent.checkpoint.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.intent.checkpoint.engine.EngineHost
import dev.intent.checkpoint.service.MonitoringService
import dev.intent.checkpoint.sessions.MonitoredAppRow
import kotlin.concurrent.thread

/**
 * Debug-only test hooks (see src/debug/AndroidManifest.xml):
 *
 *   adb shell am broadcast -a dev.intent.checkpoint.DEBUG -p dev.intent.checkpoint --es cmd monitor --es pkg com.android.chrome --es label Chrome
 *   ... --es cmd onboarded | start | stop | latency | preview --es kind timesup
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val cmd = intent.getStringExtra("cmd") ?: return
        val pending = goAsync()
        // EngineHost's blocking calls must not run on the main thread.
        thread(name = "intent-debug") {
            try {
                val host = EngineHost.get(app)
                when (cmd) {
                    "monitor" -> host.setMonitoredApps(
                        listOf(
                            MonitoredAppRow(
                                intent.getStringExtra("pkg") ?: return@thread,
                                intent.getStringExtra("label") ?: "Test app",
                                intent.getStringExtra("category") ?: "other",
                            ),
                        ),
                    )
                    "onboarded" -> host.onboardingComplete = true
                    "start" -> MonitoringService.start(app)
                    "stop" -> MonitoringService.stop(app)
                    "preview" -> host.previewOverlay(intent.getStringExtra("kind") ?: "intention")
                    "latency" -> {
                        val l = host.latencySummary()
                        Log.i(TAG, "LATENCY count=${l.count} median=${l.medianMs} p95=${l.p95Ms} max=${l.maxMs}")
                    }
                }
                Log.i(TAG, "debug command ok: $cmd")
            } catch (e: Exception) {
                Log.w(TAG, "debug command failed: $cmd", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "IntentDebug"
    }
}
