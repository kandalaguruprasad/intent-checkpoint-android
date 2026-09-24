# ADR-011 — Monitoring engine runs in a separate `:engine` process

**Status:** accepted for Phase 0. Revisit only if the GO/NO-GO run shows IPC problems.

## Context

The PRD requires that:

- RN process death or a JS crash never affects monitoring or session correctness (§15.6, §41, §42).
- The long-lived monitoring footprint stays minimal, with no JS engine kept alive just for
  background work (§46).
- P0-009 verifies that the service survives an RN process kill.

In a single-process app, "the RN process" and "the service process" are the same thing. `am kill`
or a Hermes crash takes the service down with it, and the foreground-service process (which the
OS keeps at high priority) also holds the whole React runtime in memory.

## Decision

`MonitoringService`, `EngineProvider`, `BootReceiver` and `ExpiryAlarmReceiver` declare
`android:process=":engine"`. `MainApplication.onCreate` skips `loadReactNative` in that process.

- **RN → engine:** `ContentResolver.call()` on a non-exported provider (synchronous binder RPC,
  same UID only, no AIDL). The RN module runs these calls on its own executor, never on the JS or
  UI thread.
- **Engine → RN:** a package-scoped broadcast, registered with `RECEIVER_NOT_EXPORTED`.
- **Persistence:** only the engine process opens the SQLite file. That avoids the multi-process
  SQLite hazards ADR-005 warns about.

## Consequences

- Two processes means two `Application.onCreate` runs. The engine one is kept trivial.
- Settings the engine needs (e.g. "monitoring enabled") live in the engine's SQLite
  `user_settings` table, not SharedPreferences, which isn't multi-process safe.
- MMKV for RN-side UI prefs (PRD §31) is still fine later: those are RN-process-only.

## PRD deviations made in Phase 0 (each small and reversible)

| PRD | Phase 0 | Why |
|---|---|---|
| Room (ADR-005) | Framework `SQLiteOpenHelper` behind the core `SessionStore` interface, same schema | No annotation-processing toolchain in the POC. Room swaps in behind the same interface in Phase 4. |
| `sessions.package_name REFERENCES monitored_apps` | No FK | §64 says removing an app keeps its history. A RESTRICT FK would block that. |
| Session columns | Added `backgrounded_at` | §16.3 says BACKGROUND_GRACE persists it; the schema sketch omitted it. |
| §50 "abandoned at checkpoint = completion_reason IS NULL" | `state='SESSION_ABANDONED' AND started_at IS NULL` | §16.3 sets `completionReason='abandoned'` on every abandon, so the §50 formula would always return 0. |
| Exact alarms (§29) | `SCHEDULE_EXACT_ALARM` requested with inexact fallback | Denied by default on 14+. `USE_EXACT_ALARM` is Play-restricted to alarm/calendar apps. The PRD permission table missed this. |
| Back on the expiry checkpoint (§15.8) | 60 s snooze, not counted as an extension | Implements "need more time, no request" without silently removing the limit. |
| Leaving during the intention checkpoint | Abandon + hide overlay | Pressing Home is the same answer as "Not now". |
| Leaving at the expiry checkpoint | Complete as `left` | The user did the "Leave {app}" action themselves. |
| Grace-expiry actual time (ADR-004) | `endedAt = backgroundedAt` | Actual time stops when the user left, not when the grace window lapsed. That removes most of the inflation ADR-004 accepts. |
