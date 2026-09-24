# ADR-012 — Core-loop UI: launcher-scoped app picker, foreground time, preview mode

**Status:** accepted. Covers the six-screen design pass (checkpoint, reminder, time's up,
session complete, Today, Choose Apps).

## 1. App picker visibility: `<queries>` on LAUNCHER, still no QUERY_ALL_PACKAGES

ADR-009 planned a curated `<queries>` list of about 150–200 package names. The design brief asks for
"installed, launchable apps", and a manifest `<queries><intent>MAIN/LAUNCHER</intent></queries>`
gives exactly that: every app the user can open from the launcher, without the broad
`QUERY_ALL_PACKAGES` permission that Play gates behind a declaration form. It also removes the
"type a package name" fallback for most users.

- **Not visible:** services, libraries, apps without a launcher icon. None of those can be opened
  in a way we'd intercept, so nothing is lost.
- **Play:** a LAUNCHER intent query is standard package-visibility usage and needs no permission
  declaration. Re-check at submission time (PRD §53).

## 2. "Actual" = foreground time, not wall-clock

Session Complete and Today now show time the target app was actually on screen
(`Session.foregroundMs`, accrued on every enter/leave, closed on end/restore/boot).
`wallClockSeconds` is unchanged and still drives the timer (ADR-004): the planned end is
wall-clock, but what we show as "actual" no longer includes time spent in other apps during the
grace window. Schema v2 adds `foreground_ms` and `active_since` via an explicit migration.

## 3. Design preview uses the real native overlays

The brief asked for an RN preview of all six layouts with fixture data. For the four overlay
screens, an RN copy would be a second implementation that drifts from the native one.
`previewOverlay(kind)` instead renders the **actual native overlay** with fixture data through a
separate controller that never touches sessions (Settings → Preview). Its buttons walk the real
flow: checkpoint → reminder, time's up → extension, done → complete.
Today and Choose Apps have a "sample data" switch instead.

## 4. Pill: rebuild on state change only

The reminder pill is rebuilt only when its state changes (collapsed ↔ expanded, warning
threshold). The 1 s tick only updates text. Rebuilding every second would swap views under the
user's finger and drop taps on Done/End.

## 5. "Stay without a session" re-entry rule

"Stay without a session" completes the session as `done`, removes the pill and shows nothing
further while the user stays. The next time the app comes to the foreground after leaving
(grace does **not** apply to completed sessions), a fresh checkpoint appears.
