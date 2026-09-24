package dev.intent.checkpoint.engine

import android.util.Log
import dev.intent.checkpoint.BuildConfig

/**
 * Engine logging. Never pass intention or extension-reason text here, in any build type (PRD
 * §66); Session.toString() already redacts it, so logging a Session object is safe.
 */
object EngineLog {
    private const val TAG = "IntentEngine"

    fun d(msg: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, msg)
    }

    fun w(msg: String, t: Throwable? = null) {
        Log.w(TAG, msg, t)
    }
}
