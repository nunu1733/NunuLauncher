#!/bin/bash
# Issue 563 bug oracle — fail-closed driver v2 (review round 2).
# v2: (1) abort as EXCLUDED unless the gesture-entry dump is the launcher overview
#         (overview_panel present AND task_view_single >= 1, node counting);
#     (2) abort as EXCLUDED unless the card tap completed the session
#         (logcat shows "fromState: Overview, toState: Normal" after the tap).
# Dumps at cumulative 0/0.5/1/3/6/8s after APP_SWITCH. Node counting: grep -o | wc -l.
set -u
export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"
SER=emulator-5556
RUN="${1:?run-id required}"
DIR="/tmp/issue563-qa/runs-final/$RUN"
mkdir -p "$DIR"
A() { adb -s $SER shell "$@"; }
log() { echo "$@" | tee -a "$DIR/events.log"; }

log "== setup: force-stop launcher, fresh Settings"
A am force-stop app.lawnchair.debug; sleep 3
A input keyevent KEYCODE_HOME; sleep 8
A am force-stop com.android.settings; sleep 2
A am start -n com.android.settings/.homepage.SettingsHomepageActivity; sleep 6
log "== gesture UP to overview"
A input touchscreen motionevent DOWN 540 2388
A input touchscreen motionevent MOVE 540 2340
A input touchscreen motionevent MOVE 540 1920
A input touchscreen motionevent MOVE 540 1440
A input touchscreen motionevent UP 540 1440
sleep 3
A "uiautomator dump /data/local/tmp/x.xml && cat /data/local/tmp/x.xml" > "$DIR/gesture-entry.xml" 2>/dev/null
G=$(grep -o task_view_single "$DIR/gesture-entry.xml" | wc -l | tr -d ' ')
P=$(grep -c overview_panel "$DIR/gesture-entry.xml" | tr -d ' ')
log "gesture-entry task_view_single=$G overview_panel_lines=$P"
if [ "$G" -lt 1 ] || [ "$P" -lt 1 ]; then
  log "EXCLUDED: gesture entry did not reach the launcher overview (nodes=$G panel=$P)"
  exit 10
fi

A logcat -c
log "== card tap (540,1200)"
A input tap 540 1200; sleep 4
TAP_DONE=$(A logcat -d 2>/dev/null | grep -c "fromState: Overview, toState: Normal")
log "card-tap Overview->Normal transitions=$TAP_DONE"
if [ "$TAP_DONE" -lt 1 ]; then
  log "EXCLUDED: card tap did not complete the overview session (no Overview->Normal)"
  A logcat -d -v time > "$DIR/logcat-full.txt"
  exit 11
fi

log "== HOME + 10s dwell + APP_SWITCH"
A input keyevent KEYCODE_HOME; sleep 10
A logcat -c
A input keyevent KEYCODE_APP_SWITCH
PREV=0
for T in 0 0.5 1 3 6 8; do
  DELTA=$(echo "$T - $PREV" | bc); sleep "$DELTA"; PREV="$T"
  A "uiautomator dump /data/local/tmp/x.xml && cat /data/local/tmp/x.xml" > "$DIR/dump-${T}s.xml" 2>/dev/null
  C=$(grep -o task_view_single "$DIR/dump-${T}s.xml" | wc -l | tr -d ' ')
  log "dump@${T}s task_view_single=$C"
done
adb -s $SER exec-out screencap -p > "$DIR/stuck-8s.png"
A logcat -d -v time > "$DIR/logcat-full.txt"
log "== done $RUN"
