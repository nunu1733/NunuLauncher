#!/bin/bash
# Issue 563 bug oracle driver (bisect session). Dumps at cumulative 0/0.5/1/3/6/8s after APP_SWITCH.
set -u
export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"
SER=emulator-5556
RUN="${1:?run-id required}"
DIR="/tmp/issue563-qa/runs-bisect/$RUN"
mkdir -p "$DIR"
A() { adb -s $SER shell "$@"; }

echo "== setup: force-stop launcher, launch Settings" | tee "$DIR/events.log"
A am force-stop app.lawnchair.debug
sleep 2
A input keyevent KEYCODE_HOME
sleep 2
A am start -n com.android.settings/.homepage.SettingsHomepageActivity >> "$DIR/events.log" 2>&1
sleep 4

echo "== gesture UP to overview" | tee -a "$DIR/events.log"
A input touchscreen motionevent DOWN 540 2388
A input touchscreen motionevent MOVE 540 2340
A input touchscreen motionevent MOVE 540 1920
A input touchscreen motionevent MOVE 540 1440
A input touchscreen motionevent UP 540 1440
sleep 3
A uiautomator dump /data/local/tmp/x.xml >/dev/null 2>&1
A cat /data/local/tmp/x.xml > "$DIR/gesture-entry.xml"
CARDS=$(grep -c 'task_view_single' "$DIR/gesture-entry.xml" || true)
echo "gesture-entry task_view_single=$CARDS" | tee -a "$DIR/events.log"

echo "== card tap (540,1200) -> return to Settings" | tee -a "$DIR/events.log"
A input tap 540 1200
sleep 3

echo "== HOME + 10s dwell" | tee -a "$DIR/events.log"
A input keyevent KEYCODE_HOME
sleep 10

echo "== APP_SWITCH (stuck entry candidate)" | tee -a "$DIR/events.log"
A logcat -c
A input keyevent KEYCODE_APP_SWITCH
PREV=0
for T in 0 0.5 1 3 6 8; do
  DELTA=$(echo "$T - $PREV" | bc)
  sleep "$DELTA"
  PREV="$T"
  A uiautomator dump /data/local/tmp/x.xml >/dev/null 2>&1
  A cat /data/local/tmp/x.xml > "$DIR/dump-${T}s.xml"
  C=$(grep -c 'task_view_single' "$DIR/dump-${T}s.xml" || true)
  echo "dump@${T}s task_view_single=$C" | tee -a "$DIR/events.log"
done
A logcat -d -v time > "$DIR/logcat-full.txt"
echo "== done $RUN" | tee -a "$DIR/events.log"
