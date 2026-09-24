package dev.intent.checkpoint.sessions

import dev.intent.core.AppRule
import dev.intent.core.RuleProvider

/**
 * Reads monitored apps + rules. Cached because the poll loop consults it on every foreground
 * change; call [invalidate] after any rule write (Phase 9's rule editor).
 */
class SqliteRuleProvider(private val db: IntentDatabase) : RuleProvider {
    @Volatile private var cache: Map<String, AppRule>? = null

    override fun ruleFor(packageName: String): AppRule? = all()[packageName]

    fun monitoredPackages(): List<String> = all().values.filter { it.monitoringEnabled }.map { it.packageName }

    fun invalidate() {
        cache = null
    }

    private fun all(): Map<String, AppRule> = cache ?: load().also { cache = it }

    private fun load(): Map<String, AppRule> =
        db.readableDatabase.rawQuery(
            """
            SELECT m.package_name, m.app_name, m.monitoring_enabled, r.ask_intention,
                   r.pause_duration_ms, r.timer_enabled, r.default_timer_seconds,
                   r.custom_timer_allowed, r.floating_reminder_enabled, r.warnings_enabled,
                   r.extension_allowed, r.reentry_grace_seconds
            FROM monitored_apps m JOIN app_rules r ON r.package_name = m.package_name
            """,
            null,
        ).use { c ->
            buildMap {
                while (c.moveToNext()) {
                    val rule = AppRule(
                        packageName = c.getString(0),
                        appName = c.getString(1),
                        monitoringEnabled = c.getInt(2) == 1,
                        askIntention = c.getInt(3) == 1,
                        pauseDurationMs = c.getLong(4),
                        timerEnabled = c.getInt(5) == 1,
                        defaultTimerSeconds = c.getLong(6),
                        customTimerAllowed = c.getInt(7) == 1,
                        floatingReminderEnabled = c.getInt(8) == 1,
                        warningsEnabled = c.getInt(9) == 1,
                        extensionAllowed = c.getInt(10) == 1,
                        reentryGraceSeconds = c.getLong(11),
                    )
                    put(rule.packageName, rule)
                }
            }
        }
}
