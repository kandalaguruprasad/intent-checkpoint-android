package dev.intent.core

/** Per-app configuration (PRD §15.1). Defaults are the shipped V1 defaults. */
data class AppRule(
    val packageName: String,
    val appName: String,
    val monitoringEnabled: Boolean = true,
    val askIntention: Boolean = true,
    val pauseDurationMs: Long = DEFAULT_PAUSE_MS,
    val timerEnabled: Boolean = true,
    val defaultTimerSeconds: Long = DEFAULT_TIMER_SECONDS,
    val customTimerAllowed: Boolean = true,
    val floatingReminderEnabled: Boolean = true,
    val warningsEnabled: Boolean = true,
    val extensionAllowed: Boolean = true,
    val reentryGraceSeconds: Long = DEFAULT_REENTRY_GRACE_SECONDS,
) {
    companion object {
        const val DEFAULT_PAUSE_MS = 1_200L
        const val DEFAULT_TIMER_SECONDS = 600L
        const val DEFAULT_REENTRY_GRACE_SECONDS = 120L
    }
}

fun interface RuleProvider {
    /** Returns the active rule for [packageName], or null when the package is not monitored. */
    fun ruleFor(packageName: String): AppRule?
}

/**
 * Packages that must never be intercepted regardless of user configuration (PRD §40): an overlay
 * must never be able to delay access to emergency calling.
 */
object NeverMonitor {
    val packages: Set<String> = setOf(
        "com.android.server.telecom",
        "com.android.phone",
        "com.android.dialer",
        "com.google.android.dialer",
        "com.samsung.android.dialer",
        "com.android.emergency",
        "com.google.android.apps.safetyhub",
        "com.android.settings",
        "com.android.systemui",
    )

    fun contains(packageName: String): Boolean = packageName in packages
}
