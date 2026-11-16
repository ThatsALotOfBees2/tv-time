#!/usr/bin/env bash
# Smoke test of the TV Time APK on an Android emulator, driven only with remote-control keys.
# Takes a screenshot after every step (test-out/*.png) and fails if the app crashes.
set -u
OUT=test-out
PKG=com.tvtime.app
mkdir -p "$OUT"

shot() { sleep "$2"; adb exec-out screencap -p > "$OUT/$1.png"; echo "screenshot: $1"; }
key() { adb shell input keyevent "$@"; }

# Landscape, like a TV
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 1
adb shell wm user-rotation lock 1 >/dev/null 2>&1 || true

adb install -r out/tv-time-x86_64.apk
adb logcat -c
adb shell am start -W -n "$PKG/.MainActivity"

shot 01-intro-switch-on 1.0
shot 02-intro-letters 3.0
shot 03-intro-title 2.5
shot 04-start-page 7.0                      # intro is over by now

key DPAD_RIGHT;             shot 05-youtube-selected 1
key DPAD_DOWN;              shot 06-add-tile-selected 1
key DPAD_UP DPAD_LEFT;      shot 07-streamex-selected 1
key DPAD_CENTER;            shot 08-streamex-opened 15
key --longpress DPAD_DOWN;  shot 09-pointer-moved 1
key --longpress DPAD_CENTER; shot 10-menu-open 1.5
key BACK;                   shot 11-menu-closed 1
key BACK;                   shot 12-back-on-start-page 3
key DPAD_RIGHT DPAD_CENTER; shot 13-youtube-opened 15
key BACK;                   shot 14-back-again 3

adb logcat -d > "$OUT/logcat.txt"
echo "--- app-related log lines (crashes, kills, renderer) ---"
grep -E "Fatal signal|tombstone|AndroidRuntime|FATAL|com\.tvtime\.app|Render process|renderer|lowmemorykiller|Killing|has died|ANR in" "$OUT/logcat.txt" \
  | grep -vE "Crashpad: No such file" | tail -60
echo "--- end ---"
STATUS=0
if grep -q "FATAL EXCEPTION" "$OUT/logcat.txt"; then
  echo "::error::The app crashed - see logcat.txt"
  grep -A 20 "FATAL EXCEPTION" "$OUT/logcat.txt" | head -40
  STATUS=1
fi
if grep -q "uBlock Origin ready" "$OUT/logcat.txt"; then
  echo "uBlock Origin: $(grep -m1 -o 'uBlock Origin ready.*' "$OUT/logcat.txt")"
else
  echo "::error::uBlock Origin did not start"
  grep -iE "ublock|webextension|Extension is invalid" "$OUT/logcat.txt" | head -20
  STATUS=1
fi
if [ -z "$(adb shell pidof "$PKG" | tr -d '\r')" ]; then
  echo "::error::The app is no longer running"
  STATUS=1
fi
grep -iE "chromium|TvTime" "$OUT/logcat.txt" | grep -iE "error|uncaught" | head -20 > "$OUT/web-errors.txt" || true
echo "Web console errors (first 20):"; cat "$OUT/web-errors.txt"
[ "$STATUS" = 0 ] && echo "Smoke test passed: no crash, app still running."
exit "$STATUS"
