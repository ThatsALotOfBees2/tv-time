#!/bin/sh
# Registers the Pi as a "playback device" on HDMI-CEC so the TV forwards its remote's
# buttons to it (they then show up as a normal input device that tvremote.py reads).
# Also asks the TV to switch on and to switch its input to the Pi.
command -v cec-ctl >/dev/null 2>&1 || exit 0
for dev in /dev/cec*; do
  [ -e "$dev" ] || continue
  cec-ctl -d "$dev" --playback --osd-name "TV Time" >/dev/null 2>&1 || continue
  phys=$(cec-ctl -d "$dev" 2>/dev/null | awk '/Physical Address/ {print $NF; exit}')
  cec-ctl -d "$dev" --to 0 --image-view-on >/dev/null 2>&1
  [ -n "$phys" ] && [ "$phys" != "f.f.f.f" ] && \
    cec-ctl -d "$dev" --to 15 --active-source phys-addr="$phys" >/dev/null 2>&1
done
exit 0
