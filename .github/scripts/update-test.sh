#!/usr/bin/env bash
# End-to-end test of the self-updater: version 1 of the app finds version 2 on a local server
# (the emulator reaches the runner as 10.0.2.2), downloads it, installs it and ends up on version 2.
set -u
OUT=test-out
PKG=com.tvtime.app
mkdir -p "$OUT"
shot() { sleep "$2"; adb exec-out screencap -p > "$OUT/$1.png"; echo "screenshot: $1"; }
version() { adb shell dumpsys package "$PKG" | grep -o 'versionCode=[0-9]*' | head -1; }

adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 1
adb shell wm user-rotation lock 1 >/dev/null 2>&1 || true
adb install -r upd/old.apk
echo "installed: $(version)"
# Android asks the user once to allow installing apps; grant it the way the user would.
adb shell appops set "$PKG" REQUEST_INSTALL_PACKAGES allow
adb logcat -c
adb shell am start -W -n "$PKG/.MainActivity"
shot u1-update-screen 6
shot u2-downloaded 6
adb shell input keyevent DPAD_CENTER          # "Press OK to install"
shot u3-installer 5

# Press the confirm button on Android's "update this app?" screen.
for attempt in 1 2 3; do
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb pull /sdcard/ui.xml "$OUT/ui-$attempt.xml" >/dev/null 2>&1
  XY=$(python3 - "$OUT/ui-$attempt.xml" <<'PY'
import re, sys
xml = open(sys.argv[1], encoding='utf-8', errors='replace').read()
for node in re.findall(r'<node [^>]*>', xml):
    text = re.search(r'text="([^"]*)"', node)
    if text and text.group(1).strip().lower() in ('update', 'install', 'ok'):
        x1, y1, x2, y2 = map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node).groups())
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
PY
)
  if [ -n "$XY" ]; then
    echo "tapping installer button at $XY"
    adb shell input tap $XY
    break
  fi
  sleep 3
done
shot u4-after-confirm 10

NOW=$(version)
echo "after update: $NOW"
adb logcat -d > "$OUT/logcat.txt"
if [ "$NOW" = "versionCode=2" ]; then
  echo "Update test passed: the app updated itself from version 1 to 2."
  adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
  shot u5-reopened-no-update-screen 8
  exit 0
fi
echo "::error::The app did not update itself (still $NOW)"
exit 1
