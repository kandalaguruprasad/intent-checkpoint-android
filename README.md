# Intent — intentional app-use checkpoint (Android)

Local-first Android app that asks *why* before you open an app you've flagged, keeps that
intention visible while you're in it, and re-asks when your planned time runs out. No account,
no backend, no telemetry.

**Status:** Phase 0 POC works on a physical device (vivo X200 FE). The core-loop UI is built:
- **Native overlays:** checkpoint, reminder, time's up, session complete
- **RN screens:** Today, Choose Apps

See [`docs/design/core-loop-implementation.md`](docs/design/core-loop-implementation.md) for
differences from the design reference. GO/NO-GO numbers still go in
[`docs/phase-0/POC_FINDINGS.md`](docs/phase-0/POC_FINDINGS.md).

## What's in the POC

| Ticket | What | Where |
|---|---|---|
| P0-001 | RN 0.87 + TypeScript, New Architecture | root |
| P0-002 | TurboModule (codegen spec + Kotlin impl) | `src/native/NativeIntentCheckpoint.ts`, `android/.../bridge/` |
| P0-003 / P0-006 | Usage Access + Overlay checks and Settings deep links | `android/.../permissions/` |
| P0-004 | UsageEvents polling (1 s screen-on, 10 s screen-off), dedup, cursor | `android/intent-core/.../ForegroundTracker.kt`, `android/.../engine/EngineHost.kt` |
| P0-005 | Native → RN events | `android/.../engine/EngineEvents.kt`, `src/services/intentCheckpoint.ts` |
| P0-007 / P0-008 | Native overlay: pause → intention form → floating reminder → expiry → extension → completion; Instagram hardcoded | `android/.../overlay/`, `IntentDatabase.POC_MONITORED_APPS` |
| P0-009 | `specialUse` foreground service, separate `:engine` process | `android/.../service/MonitoringService.kt` |
| P0-010 | AlarmManager expiry backstop (a "2m" test chip is on the checkpoint) | `android/.../service/ExpiryAlarm*.kt` |
| P0-011 | Detection-latency sampling, median/p95 on the POC screen | `LatencySummary`, `latency_samples` table |
| P0-012 | Findings template, **to be filled from device runs** | `docs/phase-0/POC_FINDINGS.md` |

## Architecture in one screen

```
RN process (dev.intent.checkpoint)          :engine process (dev.intent.checkpoint:engine)
┌───────────────────────────────┐           ┌─────────────────────────────────────────────┐
│ PocScreen / hooks             │           │ MonitoringService (FGS, specialUse)          │
│ services/intentCheckpoint.ts  │           │   └─ EngineHost (one engine thread)          │
│ IntentCheckpointModule (TM)   │──call()──▶│       ├─ UsageEventsSource → ForegroundTracker│
│                               │ Content-  │       ├─ SessionEngine (intent-core, pure)    │
│                               │ Provider  │       ├─ SQLite (single writer)               │
│                               │◀─bcast────│       ├─ OverlayController (WindowManager)    │
└───────────────────────────────┘ (same UID)│       └─ ExpiryAlarmScheduler                 │
                                            │ BootReceiver · ExpiryAlarmReceiver           │
                                            └─────────────────────────────────────────────┘
```

- **`android/intent-core`** is plain Kotlin/JVM: the session state machine (PRD §16), foreground
  dedup, latency stats. It has no Android imports, so the logic that decides every transition is
  unit-tested on the JVM.
- **The engine lives in its own process** and never loads React/Hermes. Killing or crashing the RN
  process can't stop monitoring, and the long-lived process stays small. See
  [ADR-011](docs/adr/ADR-011-engine-process.md).
- **SQLite in the engine process is the only source of truth.** RN reads and writes through
  `ContentProvider.call()`; nothing about a session lives only in JS memory.

## Running it

```sh
npm install
npm run android            # device/emulator with USB debugging
```

Grant the special accesses from the POC screen, or with adb for test runs:

```sh
PKG=dev.intent.checkpoint
adb shell appops set $PKG GET_USAGE_STATS allow
adb shell appops set $PKG SYSTEM_ALERT_WINDOW allow
adb shell appops set $PKG SCHEDULE_EXACT_ALARM allow     # optional, makes the expiry backstop exact
```

Checks:

```sh
npm run lint && npm run typecheck && npm test            # JS
(cd android && ./gradlew -p intent-core test)            # engine state machine, JVM only
(cd android && ./gradlew assembleDebug)                  # full app (needs Android SDK)
```

CI (`.github/workflows/ci.yml`) runs all three on every push, plus an **emulator job**: a real
debug build that drives the overlay flow and uploads screenshots (`emulator-screenshots`).
To preview the overlays on your own phone: Settings → Preview.

## Known limits (by design, per the PRD)

- Detection is polling, not push. Latency is roughly half the poll interval plus log flush
  time. We don't claim "instant".
- The app never closes or locks another app. "Leave" brings the launcher to the front.
- Without `SCHEDULE_EXACT_ALARM` (denied by default on Android 14+), the expiry backstop is
  inexact. While the service is alive, the 1 s poll loop catches expiry anyway.
- OEM background killers (Xiaomi, Vivo, Oppo, Realme…) can still stop the service. This is the
  PRD §45 risk and still an open question.
