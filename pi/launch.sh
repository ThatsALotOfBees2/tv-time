#!/usr/bin/env bash
# Starts Chromium full screen on the TV Time start page, with the TV Time extension
# (ad blocker, pop-up/redirect blocker, remote shortcuts). Restarts it if it ever closes.
# To stop it (e.g. over SSH):  touch ~/.config/tv-time-browser/STOP && pkill -f chromium
DIR="$(cd "$(dirname "$0")" && pwd)"
PROFILE="$HOME/.config/tv-time-browser"
START="chrome-extension://johjcnbgekmlamllgcjfciglaaklblcp/start/index.html"
BROWSER="$(command -v chromium || command -v chromium-browser)"
if [ -z "$BROWSER" ]; then
  echo "Chromium is not installed (sudo apt install chromium)" >&2
  exit 1
fi
mkdir -p "$PROFILE"
rm -f "$PROFILE/STOP"
xset s off -dpms s noblank 2>/dev/null || true

while [ ! -f "$PROFILE/STOP" ]; do
  # Pretend the last session ended cleanly so Chromium never shows a "Restore pages?" bar.
  PREFS="$PROFILE/Default/Preferences"
  [ -f "$PREFS" ] && sed -i 's/"exited_cleanly":false/"exited_cleanly":true/; s/"exit_type":"[^"]*"/"exit_type":"Normal"/' "$PREFS"

  "$BROWSER" \
    --kiosk \
    --user-data-dir="$PROFILE" \
    --load-extension="$DIR/extension" \
    --disable-features=DisableLoadExtensionCommandLineSwitch,Translate,MediaRouter \
    --no-first-run --noerrdialogs --disable-infobars \
    --disable-session-crashed-bubble --hide-crash-restore-bubble \
    --password-store=basic \
    --autoplay-policy=no-user-gesture-required \
    --force-device-scale-factor="${TVTIME_SCALE:-1}" \
    "$START"
  sleep 2
done
