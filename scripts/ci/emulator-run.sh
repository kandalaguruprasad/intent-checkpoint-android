#!/usr/bin/env bash
# Drives the real app on a CI emulator: onboarding, the full native overlay loop through the UI,
# rotation, dark mode, process death and permission revocation. Saves screenshots + logcat to
# $OUT. Steps are best-effort (a failed step is logged, the run continues) so one flaky tap
# doesn't hide every other screenshot; the step log records what passed.
set -u
PKG=dev.intent.checkpoint
APK=${APK:-android/app/build/outputs/apk/debug/app-debug.apk}
OUT=${OUT:-emulator-output}
HERE=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$OUT"
STEPS="$OUT/steps.txt"
: > "$STEPS"

log() { echo "[$(date +%T)] $*" | tee -a "$STEPS"; }
shot() { adb exec-out screencap -p > "$OUT/$1.png" && log "screenshot $1"; }
dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml "$OUT/ui.xml" >/dev/null 2>&1; }
# tap_node <filters...>: dump the focused window, tap the first matching node.
tap_node() {
  dump
  local xy
  if xy=$(python3 "$HERE/ui.py" "$OUT/ui.xml" "$@"); then
    adb shell input tap $xy
    log "tap $* at $xy"
    return 0
  fi
  log "NOT FOUND: $*"
  return 1
}
has_window() { adb shell dumpsys window windows | grep -q "$1"; }
wait_window() { # wait_window <title> <timeout_s>
  local end=$((SECONDS + $2))
  while [ $SECONDS -lt $end ]; do has_window "$1" && return 0; sleep 0.2; done
  return 1
}
debug_cmd() { adb shell am broadcast -a dev.intent.checkpoint.DEBUG -p $PKG --es cmd "$@" >/dev/null; }
dp() { echo $(( $1 * DENSITY / 160 )); }

adb wait-for-device
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 1   # keep the pause animation real
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 0
adb shell cmd uimode night no
DENSITY=$(adb shell wm density | grep -o '[0-9]*' | tail -1)
SIZE=$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1)
W=${SIZE%x*}
log "device: $(adb shell getprop ro.build.version.release) api $(adb shell getprop ro.build.version.sdk) ${SIZE} ${DENSITY}dpi"

adb install -r -g "$APK" | tail -1 | tee -a "$STEPS"
adb shell appops set $PKG GET_USAGE_STATS allow
adb shell appops set $PKG SYSTEM_ALERT_WINDOW allow
adb shell appops set $PKG SCHEDULE_EXACT_ALARM allow
adb logcat -c

# Target app to monitor: first one installed.
TARGET=""
for p in com.android.chrome com.google.android.youtube com.google.android.deskclock com.android.contacts com.google.android.contacts com.android.calculator2 com.google.android.calculator; do
  if adb shell pm path "$p" >/dev/null 2>&1; then TARGET=$p; break; fi
done
log "target app: ${TARGET:-none}"

# --- 6. Choose Apps (real installed apps) -----------------------------------------------------
adb shell am start -n $PKG/.MainActivity >/dev/null
sleep 10
shot 01-choose-apps-light
adb shell cmd uimode night yes; sleep 3
shot 02-choose-apps-dark
adb shell cmd uimode night no; sleep 2
adb shell input keyevent KEYCODE_HOME

# Seed: monitor TARGET, finish onboarding, start the service.
debug_cmd monitor --es pkg "$TARGET" --es label Target --es category other; sleep 1
debug_cmd onboarded; sleep 1
debug_cmd start; sleep 3
adb shell dumpsys activity services $PKG | grep -q MonitoringService && log "service running" || log "SERVICE NOT RUNNING"

# --- 5. Today, empty day ----------------------------------------------------------------------
adb shell am start -n $PKG/.MainActivity >/dev/null; sleep 6
shot 03-today-empty
adb shell input keyevent KEYCODE_HOME; sleep 2

launch_target() { adb shell monkey -p "$TARGET" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; }

# --- 1. Intention checkpoint -------------------------------------------------------------------
t0=$(date +%s%3N)
launch_target
if wait_window IntentCheckpoint 10; then
  t1=$(date +%s%3N); log "checkpoint window visible after $((t1 - t0)) ms (host-measured, launch→overlay)"
  shot 04-pause
else
  log "CHECKPOINT DID NOT APPEAR within 10 s"
fi
sleep 2.5
shot 05-intention-form
tap_node text=Custom && sleep 1 && adb shell input text 3
tap_node "content-desc~=What do you want" && sleep 1 && adb shell input text "Reply%sto%sa%smessage"
sleep 1
shot 06-intention-keyboard-open
tap_node text=Continue || true
sleep 2

# --- 2. Floating reminder ----------------------------------------------------------------------
wait_window IntentReminder 5 && log "reminder visible" || log "REMINDER NOT VISIBLE"
shot 07-reminder-collapsed
adb shell input tap $((W / 2)) $(dp 104)
sleep 1.5
shot 08-reminder-expanded
adb shell input tap $((W / 2)) $(dp 96)
sleep 1

# Leave and come back within grace: same session, no new checkpoint.
adb shell input keyevent KEYCODE_HOME; sleep 4
has_window IntentReminder && log "REMINDER STILL VISIBLE ON HOME" || log "reminder hidden on home"
launch_target; sleep 4
has_window IntentCheckpoint && log "UNEXPECTED new checkpoint within grace" || log "grace re-entry: no new checkpoint"
has_window IntentReminder && log "reminder back after re-entry" || log "REMINDER MISSING after re-entry"

# 3-minute timer: warning at <= 2:00 left.
sleep 60
shot 09-reminder-warning

# --- 3. Time's up (+ extension) ----------------------------------------------------------------
wait_window IntentCheckpoint 150 && log "time's up shown" || log "TIME'S UP NOT SHOWN"
sleep 1
shot 10-times-up
tap_node text="Need more time" && sleep 1.5 && shot 11-extension
tap_node text=Back && sleep 1.5

# --- 4. Session complete -----------------------------------------------------------------------
tap_node "text=I'm done" && sleep 1.5 && shot 12-session-complete
tap_node text="Go home"; sleep 3

# Back on the checkpoint = "Not now" → home.
launch_target; wait_window IntentCheckpoint 10; sleep 2.5
adb shell input keyevent KEYCODE_BACK; sleep 2
has_window IntentCheckpoint && log "BACK DID NOT CLOSE CHECKPOINT" || log "back on checkpoint closed it (not now)"

# Rotation + dark mode on the checkpoint.
adb shell cmd uimode night yes; sleep 2
launch_target; wait_window IntentCheckpoint 10; sleep 2.5
shot 13-intention-dark
adb shell settings put system user_rotation 1; sleep 3
shot 14-intention-landscape
adb shell settings put system user_rotation 0; sleep 2
tap_node text="Not now"; sleep 2
adb shell cmd uimode night no; sleep 2

# Process death: start a session, kill the engine process, expect restore.
launch_target; wait_window IntentCheckpoint 10; sleep 2.5
tap_node text=Continue; sleep 3
ENGINE_PID=$(adb shell pidof $PKG:engine | tr -d '\r')
adb shell run-as $PKG kill -9 "$ENGINE_PID" && log "killed engine pid $ENGINE_PID"
sleep 15
NEW_PID=$(adb shell pidof $PKG:engine | tr -d '\r')
log "engine pid after kill: ${NEW_PID:-none}"
has_window IntentReminder && log "reminder restored after engine death" || log "REMINDER NOT RESTORED after engine death"
shot 15-after-engine-kill

# --- 5. Today with data ------------------------------------------------------------------------
adb shell am start -n $PKG/.MainActivity >/dev/null; sleep 5
shot 16-today-data
adb shell cmd uimode night yes; sleep 3
shot 17-today-dark
adb shell cmd uimode night no; sleep 2

# Permission revoked: no crash, Today flags it.
adb shell appops set $PKG GET_USAGE_STATS ignore
sleep 12
adb shell am start -n $PKG/.MainActivity >/dev/null; sleep 4
shot 18-today-permission-revoked
adb shell appops set $PKG GET_USAGE_STATS allow

debug_cmd latency; sleep 2
adb logcat -d -s IntentEngine IntentDebug AndroidRuntime > "$OUT/logcat.txt"
grep -h "LATENCY" "$OUT/logcat.txt" | tail -1 | tee -a "$STEPS"
grep -c "FATAL EXCEPTION" "$OUT/logcat.txt" | xargs -I{} log "fatal exceptions in logcat: {}"
log "done"
