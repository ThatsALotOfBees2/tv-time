#!/usr/bin/env bash
# TV Time for Raspberry Pi - one-time setup.
#
# Run on Raspberry Pi OS (Desktop) as your normal user, from inside the cloned repo:
#     ./pi/install.sh
#
# What it does:
#   1. installs Chromium, python3-evdev (remote) and v4l-utils (HDMI-CEC)
#   2. makes the Pi boot straight to the desktop, logged in, with screen blanking off
#   3. starts TV Time (Chromium full screen on the start page) automatically at login
#   4. installs the "tvtime-remote" service so your TV remote works through HDMI-CEC
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
ME="$(id -un)"

if [ "$(id -u)" -eq 0 ]; then
  echo "Run this as your normal user (not with sudo); it will ask for sudo when needed." >&2
  exit 1
fi

echo "==> Installing packages"
sudo apt-get update
CHROMIUM_PKG=chromium
apt-cache show chromium >/dev/null 2>&1 || CHROMIUM_PKG=chromium-browser
if ! command -v chromium >/dev/null 2>&1 && ! command -v chromium-browser >/dev/null 2>&1; then
  sudo apt-get install -y "$CHROMIUM_PKG"
fi
sudo apt-get install -y python3-evdev v4l-utils

echo "==> Building the TV Time extension"
python3 "$DIR/build-extension.py"

echo "==> Boot to desktop with auto-login, no screen blanking"
if command -v raspi-config >/dev/null 2>&1; then
  sudo raspi-config nonint do_boot_behaviour B4 || true
  sudo raspi-config nonint do_blanking 1 || true
fi

echo "==> Start TV Time at login"
mkdir -p "$HOME/.config/autostart"
cat > "$HOME/.config/autostart/tv-time.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=TV Time
Comment=TV Time start page with ad blocker
Exec=$DIR/launch.sh
X-GNOME-Autostart-enabled=true
EOF

echo "==> Remote control service (HDMI-CEC / IR remotes)"
sudo tee /etc/systemd/system/tvtime-remote.service >/dev/null <<EOF
[Unit]
Description=TV Time remote control (TV remote via HDMI-CEC / IR -> pointer and keys)
After=multi-user.target

[Service]
ExecStartPre=-/sbin/modprobe uinput
ExecStartPre=-/bin/sh $DIR/cec-setup.sh
ExecStart=/usr/bin/python3 $DIR/tvremote.py
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
EOF
echo uinput | sudo tee /etc/modules-load.d/tvtime-uinput.conf >/dev/null
sudo systemctl daemon-reload
sudo systemctl enable --now tvtime-remote.service

echo
echo "Done! Reboot to start TV Time:  sudo reboot"
echo "  Remote service log:  journalctl -u tvtime-remote -f"
echo "  Stop the TV screen:  touch ~/.config/tv-time-browser/STOP && pkill -f chromium"
echo "  Start it again:      $DIR/launch.sh &"
echo "  Installed for user:  $ME"
