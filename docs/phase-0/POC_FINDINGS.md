# Phase 0 — POC findings (P0-012)

> **Not filled in yet.** Fill it in from real-device runs only. Don't estimate or copy numbers
> from the PRD. A criterion without a measured result is **not met**.

| | Device A (Pixel / stock) | Device B (Samsung or Xiaomi class) |
|---|---|---|
| Model / Android / OEM skin | | |
| Build (git SHA) | | |
| Date | | |

## GO/NO-GO (PRD §61)

| Criterion | GO threshold | Device A | Device B | Met? |
|---|---|---|---|---|
| Detection latency | median < 2 s, p95 < 4 s | | | |
| Duplicate interventions | 0 duplicate checkpoints in 20 opens | | | |
| Overlay reliability | survives rotation + IME focus, no crash | | | |
| Battery | no red flag in Settings → Battery over a multi-hour run | | | |
| Process-death recovery | session resumes after RN kill, 10/10 | | | |
| Play-policy viability | `specialUse` + Usage Access + Overlay consistent with live policy text on the test date | | | |

## How to run each check

Setup: `PKG=dev.intent.checkpoint`. Grant access (see README). Tap **Start monitoring**.

1. **Latency (P0-011).** Tap *Clear* on the latency card. Open Instagram from the launcher 20
   times, going home in between. Read median/p95 from the card. Log the per-event lines with
   `adb logcat -s IntentEngine` (debug builds).
2. **Duplicates.** During the same 20 opens, count checkpoints. Exactly one per open is
   expected, and none on a return within the 2 min grace window.
3. **Overlay.** On the intention form, focus the text field (keyboard up), rotate twice, type,
   and submit. Then check the pill drag, drag-off-edge hide, expand, Done and End.
4. **Process death (RN).** Start a session with the **2m** chip. Go home. Run
   `adb shell am kill $PKG` (kills only the RN process, `$PKG:engine` keeps running). Reopen
   Instagram: the pill should show the same intention and remaining time. Repeat 10 times.
5. **Process death (engine).** Mid-session:
   `adb shell run-as $PKG kill $(adb shell pidof $PKG:engine)`. Expect a START_STICKY restart
   and the same session restored from SQLite (grace, then active on the next poll).
6. **Timer with RN dead (P0-010).** Start a **2m** session, then `adb shell am kill $PKG`. Stay
   in Instagram. The expiry checkpoint must appear at the planned time. For the backstop itself,
   also kill the engine as in 5 just before expiry: whichever comes first (the START_STICKY
   restart or the alarm) must show it. Record whether exact alarms were granted. Without them the
   alarm can slip by minutes under Doze (`adb shell dumpsys deviceidle force-idle`). Note that
   *Stop monitoring* cancels the alarm on purpose: monitoring off means no interventions.
7. **Reboot.** Start a session, `adb reboot`. After boot, the POC screen should show no open
   session (`device_restarted`) and monitoring running again.
8. **Permission revoke.** `adb shell appops set $PKG GET_USAGE_STATS ignore` mid-session.
   Expect no crash, no checkpoints, and the Permission health card flags it.
9. **Battery.** Leave monitoring on for 3+ hours of normal use. Record the app's share in
   Settings → Battery.

## Observations

<!-- What broke, OEM-specific behaviour, anything that changes the plan for Phase 1. -->

## Decision

<!-- GO / NO-GO, and for each failed criterion the fallback chosen (PRD §61). -->
