# Core loop — implementation vs. visual reference

Reference: the six-screen design image (intention checkpoint, floating reminder, time's up,
session complete, Today, Choose Apps). Tokens: `src/theme/tokens.ts` and
`android/.../overlay/OverlayUi.kt` (`OverlayPalette`), kept in sync.

| Screen | Where | Matches reference | Deliberate differences |
|---|---|---|---|
| 1. Intention checkpoint | native overlay, `OverlayController.showIntentionForm` | Icon + app name, title, intention field with clear, chips, duration row, green Continue, "Not now" | Title is **"Why are you here?"** (per the written brief; the image says "Why are you opening Instagram?"). Adds **Recent** (≤3 of the user's own past intentions for that app), the full quick-pick set, and **Custom** + **No timer**. Continue and Not now are **pinned above the keyboard**. Follows system light/dark. Debug builds also show a "2m" test chip. |
| 2. Floating reminder | native overlay | Dark pill: icon · intention · mm:ss · chevron. Expanded: intention, time, planned, Done / End. Amber warning with "Time's almost up" | One state at a time (the image shows three states side by side for documentation). Warnings at 2 min **and** 30 s (30 s adds a gentle alpha pulse, skipped with reduced motion). No timer shows `+mm:ss` elapsed. Drag off a side edge hides it for this visit only. |
| 3. Time's up | native overlay, always dark | Icon, "Time's up", quoted intention, planned line, question, three actions | Actions are **I'm done / Need more time / Go home** (brief), not "Leave Instagram". Back = 60 s snooze (PRD §15.8). After an extension the planned line becomes "You planned X. You've been here for Y." |
| 3b. Need more time | native overlay, dark | — (not in the image) | Optional reason, 5/10/15 chips, Continue, Back. |
| 4. Session complete | native overlay, always dark | Icon, "Done for now?", intention, "N min actual · M min planned", two bars, Go home / Stay without a session | "Actual" is **foreground** time (ADR-012). No timer: "N min actual · no timer", no bars. |
| 5. Today | RN | "Intent" + status pill, three stat tiles, planned vs actual bars, monitored apps with opens, bottom tabs | All numbers come from SQLite via `getSummary`; the image's 8 / 5 / 3 / 50 / 28 are **never** hardcoded (a test asserts this). Adds an active-session card, a permission banner, and first-day / no-apps empty states. Planned vs actual compares **timed sessions only**, so a no-timer session can't make "actual" look over plan. |
| 6. Choose Apps | RN | Back, progress dots, title, subtitle, search, grouped list with toggles, pinned "N apps selected" + Continue | Subtitle: "Start with one or two apps. You can change this anytime in Apps." Banking/payment/auth/password apps go into a **Not recommended** group, with a confirm dialog when added. Two onboarding steps (Choose Apps → permissions), so two dots. |

## Not built yet (on purpose)

History, Apps and Settings are **minimal** tabs so the core loop can be used end to end:
- **History:** last 7 days
- **Apps:** list + edit
- **Settings:** monitoring, permission health, preview, diagnostics

The remaining screens from the 24-screen plan wait until the loop is verified on a physical
device.

## Verification

- **Kotlin core (JVM):** 67 tests, including foreground-time accrual.
- **JS (Jest):** 19 tests. Today renders engine numbers; first launch lands on Choose Apps.
- **Emulator (CI job `emulator`):** real debug build with JS bundled; drives the overlay flow
  through UI Automator. Screenshots and logcat are in the `emulator-screenshots` artifact.
- **Physical device: to do.** Detection latency on the vivo X200 FE, OEM background behaviour,
  and the other `POC_FINDINGS.md` checks.
