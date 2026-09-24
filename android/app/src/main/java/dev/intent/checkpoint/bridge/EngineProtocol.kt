package dev.intent.checkpoint.bridge

/** Method names and Bundle keys for the RN process <-> `:engine` process call channel. */
internal object EngineProtocol {
    const val AUTHORITY_SUFFIX = ".engine"

    const val GET_ACTIVE_SESSION = "getActiveSession"
    const val SUBMIT_INTENTION = "submitIntention"
    const val REQUEST_EXTENSION = "requestExtension"
    const val COMPLETE_SESSION = "completeSession"
    const val GET_LATENCY_STATS = "getLatencyStats"
    const val CLEAR_LATENCY_STATS = "clearLatencyStats"
    const val GET_MONITORING_STATUS = "getMonitoringStatus"
    const val GET_SUMMARY = "getSummary"
    const val LIST_SESSIONS = "listSessions"
    const val GET_MONITORED_APPS = "getMonitoredApps"
    const val SET_MONITORED_APPS = "setMonitoredApps"
    const val GET_ONBOARDING = "getOnboarding"
    const val SET_ONBOARDING = "setOnboarding"
    const val PREVIEW_OVERLAY = "previewOverlay"

    const val KEY_SESSION_ID = "sessionId"
    const val KEY_INTENTION = "intention"
    const val KEY_PLANNED_SECONDS = "plannedSeconds"
    const val KEY_REASON = "reason"
    const val KEY_EXTRA_SECONDS = "extraSeconds"
    const val KEY_JSON = "json"
    const val KEY_ENABLED = "enabled"
    const val KEY_RUNNING = "running"
    const val KEY_PACKAGES = "packages"
    const val KEY_FROM = "from"
    const val KEY_TO = "to"
    const val KEY_LIMIT = "limit"
    const val KEY_KIND = "kind"
    const val KEY_DONE = "done"
    const val KEY_ERROR_CODE = "errorCode"
    const val KEY_ERROR_MESSAGE = "errorMessage"

    const val E_ENGINE_FAILURE = "E_ENGINE_FAILURE"
    const val E_UNKNOWN_METHOD = "E_UNKNOWN_METHOD"
}
