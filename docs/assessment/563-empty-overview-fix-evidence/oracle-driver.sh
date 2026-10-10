#!/bin/bash
# Issue 563 bug oracle — standard driver (dumps at cumulative 0/0.5/1/3/6/8s after APP_SWITCH).
# Node counting: grep -o | wc -l (XML is single-line; grep -c counts lines, not nodes).
set -u
export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"
SER=emulator-5556
RUN="${1:?run-id required}"
DIR="/tmp/issue563-qa/runs-final/$RUN"
mkdir -p "$DIR"
A() { adb -s $SER shell "$@"; }

{
echo "== setup: force-stop launcher, fresh Settings"
A am force-stop app.lawnchair.debug; sleep 3
A input keyevent KEYCODE_HOME; sleep 8
A am force-stop com.android.settings; sleep 2
A am start -n com.android.settings/.homepage.SettingsHomepageActivity; sleep 6
echo "== gesture UP to overview"
A input touchscreen motionevent DOWN 540 2388
A input touchscreen motionevent MOVE 540 2340
A input touchscreen motionevent MOVE 540 1920
A input touchscreen motionevent MOVE 540 1440
A input touchscreen motionevent UP 540 1440
sleep 3
A "uiautomator dump /data/local/tmp/x.xml && cat /data/local/tmp/x.xml" > "$DIR/gesture-entry.xml" 2>/dev/null
G=$(grep -o task_view_single "$DIR/gesture-entry.xml" | wc -l | tr -d ' ')
echo "gesture-entry task_view_single=$G"
} | tee "$DIR/events.log"

A logcat -c
A input tap 540 1200; sleep 4
echo "== HOME + 10s dwell + APP_SWITCH" | tee -a "$DIR/events.log"
A input keyevent KEYCODE_HOME; sleep 10
A input keyevent KEYCODE_APP_SWITCH
PREV=0
for T in 0 0.5 1 3 6 8; do
  DELTA=$(echo "$T - $PREV" | bc); sleep "$DELTA"; PREV="$T"
  A "uiautomator dump /data/local/tmp/x.xml && cat /data/local/tmp/x.xml" > "$DIR/dump-${T}s.xml" 2>/dev/null
  C=$(grep -o task_view_single "$DIR/dump-${T}s.xml" | wc -l | tr -d ' ')
  echo "dump@${T}s task_view_single=$C" | tee -a "$DIR/events.log"
done
adb -s $SER exec-out screencap -p > "$DIR/stuck-8s.png"
A logcat -d -v time > "$DIR/logcat-full.txt"
echo "== done $RUN" | tee -a "$DIR/events.log"
