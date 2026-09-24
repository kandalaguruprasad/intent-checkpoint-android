package dev.intent.checkpoint.engine

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File

/**
 * The monitoring engine runs in its own `:engine` process (manifest `android:process`) so that
 * it does not share a heap with Hermes/React, keeps running when the RN process is killed or
 * crashes, and a JS crash can never take monitoring down (PRD §24, §41, §46).
 */
object AppProcess {
    const val ENGINE_SUFFIX = ":engine"

    fun isEngineProcess(context: Context): Boolean =
        currentProcessName(context)?.endsWith(ENGINE_SUFFIX) == true

    private fun currentProcessName(context: Context): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return Application.getProcessName()
        return runCatching {
            File("/proc/self/cmdline").readText().trim { it <= ' ' || it == '\u0000' }
        }.getOrNull() ?: context.packageName
    }
}
