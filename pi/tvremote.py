#!/usr/bin/env python3
"""
TV Time remote for Raspberry Pi.

Turns a TV remote (through HDMI-CEC), an IR receiver or a simple USB remote into an on-screen
pointer for the browser - the same controls as the TV Time Android app:

  Arrows        move the pointer (hold to speed up, push against an edge to scroll)
  OK            click
  Hold OK       switch between POINTER mode and ARROW-KEY mode
                (arrow-key mode sends real arrow keys: handy for video players and the start page)
  Back          leave fullscreen video, otherwise go back a page
  Hold Back     open the TV Time start page   (so does Home / Menu)
  Play/Pause... passed straight to the browser
  Fast-fwd/Rew  sent as Right/Left arrows (seek)
  Channel +/-   Page Up / Page Down

Only devices that look like remotes are taken over; normal keyboards and mice keep working.
Runs as a systemd service (installed by pi/install.sh). Needs: python3-evdev, the uinput module.
"""
import select
import sys
import time

try:
    import evdev
    from evdev import AbsInfo, UInput
    from evdev import ecodes as e
except ImportError:
    sys.exit("python3-evdev is missing: sudo apt install python3-evdev")

ABS_MAX = 32767
ASPECT = 16 / 9            # screen width / height, keeps vertical speed the same as horizontal
MIN_SPEED = 0.25           # screen widths per second when a direction is first held
MAX_SPEED = 1.4
ACCEL = 1.6
NUDGE = 0.006              # instant step so short taps are precise
WHEEL_PER_WIDTH = 25       # wheel ticks per screen-width of pushing against an edge
LONG_PRESS = 0.6           # seconds
FPS = 120

DIRS = {e.KEY_UP: (0, -1), e.KEY_DOWN: (0, 1), e.KEY_LEFT: (-1, 0), e.KEY_RIGHT: (1, 0)}
OK_KEYS = {e.KEY_OK, e.KEY_SELECT, e.KEY_ENTER, e.KEY_KPENTER}
BACK_KEYS = {e.KEY_BACK, e.KEY_EXIT, e.KEY_ESC, e.KEY_BACKSPACE}
HOME_KEYS = {e.KEY_HOMEPAGE, e.KEY_HOME, e.KEY_ROOT_MENU, e.KEY_MENU, e.KEY_CONTEXT_MENU, e.KEY_SETUP}
MODE_KEYS = {e.KEY_RED, e.KEY_INFO}
PASS = {
    e.KEY_PLAYPAUSE: e.KEY_PLAYPAUSE, e.KEY_PLAY: e.KEY_PLAY, e.KEY_PLAYCD: e.KEY_PLAY,
    e.KEY_PAUSE: e.KEY_PAUSE, e.KEY_PAUSECD: e.KEY_PAUSE, e.KEY_STOP: e.KEY_STOP, e.KEY_STOPCD: e.KEY_STOP,
    e.KEY_FASTFORWARD: e.KEY_RIGHT, e.KEY_REWIND: e.KEY_LEFT,
    e.KEY_NEXTSONG: e.KEY_NEXTSONG, e.KEY_PREVIOUSSONG: e.KEY_PREVIOUSSONG,
    e.KEY_CHANNELUP: e.KEY_PAGEUP, e.KEY_CHANNELDOWN: e.KEY_PAGEDOWN,
    e.KEY_PAGEUP: e.KEY_PAGEUP, e.KEY_PAGEDOWN: e.KEY_PAGEDOWN,
    e.KEY_VOLUMEUP: e.KEY_VOLUMEUP, e.KEY_VOLUMEDOWN: e.KEY_VOLUMEDOWN, e.KEY_MUTE: e.KEY_MUTE,
    e.KEY_SPACE: e.KEY_SPACE,
}
for _n in range(10):
    _key = getattr(e, f"KEY_{_n}")
    PASS[_key] = _key
    PASS[getattr(e, f"KEY_NUMERIC_{_n}")] = _key

NAME_HINTS = ("cec", "hdmi", "remote", "ir receiver", "ir-receiver", "gpio_ir", "rc-", "flirc", "mce")


def log(*args):
    print("[tvremote]", *args, flush=True)


def looks_like_remote(dev) -> bool:
    if dev.name.startswith("TV Time"):
        return False
    keys = set(dev.capabilities().get(e.EV_KEY, []))
    if not keys & set(DIRS):
        return False
    name = dev.name.lower()
    if any(h in name for h in NAME_HINTS):
        return True
    remote_only = {e.KEY_OK, e.KEY_SELECT, e.KEY_BACK, e.KEY_HOMEPAGE, e.KEY_EXIT}
    return bool(keys & remote_only) and e.KEY_Q not in keys   # keyboards have letters, remotes don't


class Remote:
    def __init__(self):
        self.pointer = UInput(
            {
                e.EV_KEY: [e.BTN_LEFT, e.BTN_RIGHT, e.BTN_MIDDLE],
                e.EV_ABS: [(e.ABS_X, AbsInfo(0, 0, ABS_MAX, 0, 0, 0)), (e.ABS_Y, AbsInfo(0, 0, ABS_MAX, 0, 0, 0))],
                e.EV_REL: [e.REL_WHEEL, e.REL_HWHEEL],
            },
            name="TV Time pointer",
        )
        out_keys = set(PASS.values()) | set(DIRS) | {
            e.KEY_ENTER, e.KEY_LEFTALT, e.KEY_LEFTSHIFT, e.KEY_H, e.KEY_B, e.KEY_ESC}
        self.keys = UInput({e.EV_KEY: sorted(out_keys)}, name="TV Time keys")
        self.devices = {}          # fd -> InputDevice
        self.x, self.y = 0.5, 0.5
        self.held = {}             # key code -> (dx, dy)
        self.speed = 0.0
        self.wheel = 0.0
        self.mode = "pointer"
        self.ok_down = None
        self.ok_long = False
        self.back_down = None
        self.back_long = False

    # ---------- devices ----------
    def scan(self):
        known = {d.path for d in self.devices.values()}
        for path in evdev.list_devices():
            if path in known:
                continue
            try:
                dev = evdev.InputDevice(path)
            except OSError:
                continue
            if looks_like_remote(dev):
                try:
                    dev.grab()
                except OSError:
                    dev.close()
                    continue
                self.devices[dev.fd] = dev
                log(f"using remote: {dev.name} ({path})")
            else:
                dev.close()

    def drop(self, fd):
        dev = self.devices.pop(fd, None)
        if dev:
            log(f"remote gone: {dev.name}")
            try:
                dev.close()
            except OSError:
                pass
        self.held.clear()

    # ---------- output ----------
    def move_to(self, x, y):
        self.x, self.y = x, y
        self.pointer.write(e.EV_ABS, e.ABS_X, int(x * ABS_MAX))
        self.pointer.write(e.EV_ABS, e.ABS_Y, int(y * ABS_MAX))
        self.pointer.syn()

    def click(self):
        self.pointer.write(e.EV_KEY, e.BTN_LEFT, 1)
        self.pointer.syn()
        time.sleep(0.03)
        self.pointer.write(e.EV_KEY, e.BTN_LEFT, 0)
        self.pointer.syn()

    def key(self, code, value):
        self.keys.write(e.EV_KEY, code, value)
        self.keys.syn()

    def tap(self, *codes):
        for c in codes:
            self.key(c, 1)
        for c in reversed(codes):
            self.key(c, 0)

    def set_mode(self, mode):
        self.mode = mode
        self.held.clear()
        log(f"mode: {mode}")
        # Visible feedback: arrow-key mode parks the pointer in the corner, pointer mode brings it back.
        if mode == "keys":
            self.move_to(0.999, 0.999)
        else:
            self.move_to(0.5, 0.5)

    # ---------- input ----------
    def handle(self, ev):
        if ev.type != e.EV_KEY:
            return
        code, value = ev.code, ev.value      # value: 1 down, 0 up, 2 repeat
        now = time.monotonic()

        if code in DIRS:
            if self.mode == "keys":
                self.key(code, value)
            elif value == 1:
                fresh = code not in self.held
                self.held[code] = DIRS[code]
                if fresh and self.speed == 0:
                    dx, dy = DIRS[code]
                    self.nudge(dx * NUDGE, dy * NUDGE * ASPECT)
            elif value == 0:
                self.held.pop(code, None)
            return

        if code in OK_KEYS:
            if value == 1:
                self.ok_down, self.ok_long = now, False
            elif value == 0 and self.ok_down is not None:
                if not self.ok_long:
                    if self.mode == "pointer":
                        self.click()
                    else:
                        self.tap(e.KEY_ENTER)
                self.ok_down = None
            return

        if code in BACK_KEYS:
            if value == 1:
                self.back_down, self.back_long = now, False
            elif value == 0 and self.back_down is not None:
                if not self.back_long:
                    self.tap(e.KEY_LEFTALT, e.KEY_LEFTSHIFT, e.KEY_B)
                self.back_down = None
            return

        if code in HOME_KEYS:
            if value == 1:
                self.tap(e.KEY_LEFTALT, e.KEY_LEFTSHIFT, e.KEY_H)
            return

        if code in MODE_KEYS:
            if value == 1:
                self.set_mode("keys" if self.mode == "pointer" else "pointer")
            return

        if code in PASS:
            self.key(PASS[code], value)

    def check_long_presses(self, now):
        if self.ok_down is not None and not self.ok_long and now - self.ok_down >= LONG_PRESS:
            self.ok_long = True
            self.set_mode("keys" if self.mode == "pointer" else "pointer")
        if self.back_down is not None and not self.back_long and now - self.back_down >= LONG_PRESS:
            self.back_long = True
            self.tap(e.KEY_LEFTALT, e.KEY_LEFTSHIFT, e.KEY_H)

    # ---------- pointer motion ----------
    def nudge(self, dx, dy):
        self.move_to(min(max(self.x + dx, 0.0), 1.0), min(max(self.y + dy, 0.0), 1.0))

    def step(self, dt):
        if self.mode != "pointer" or not self.held:
            self.speed = 0.0
            self.wheel = 0.0
            return
        dx = max(-1, min(1, sum(d[0] for d in self.held.values())))
        dy = max(-1, min(1, sum(d[1] for d in self.held.values())))
        if dx == 0 and dy == 0:
            return
        self.speed = MIN_SPEED if self.speed == 0 else min(self.speed + ACCEL * dt, MAX_SPEED)
        s = self.speed * dt
        nx, ny = self.x + dx * s, self.y + dy * s * ASPECT
        at_edge_x = nx < 0 or nx > 1
        at_edge_y = ny < 0 or ny > 1
        self.move_to(min(max(nx, 0.0), 1.0), min(max(ny, 0.0), 1.0))
        if at_edge_x or at_edge_y:
            self.wheel += s * WHEEL_PER_WIDTH
            ticks = int(self.wheel)
            if ticks:
                self.wheel -= ticks
                if at_edge_y:
                    self.pointer.write(e.EV_REL, e.REL_WHEEL, -ticks if dy > 0 else ticks)
                if at_edge_x:
                    self.pointer.write(e.EV_REL, e.REL_HWHEEL, ticks if dx > 0 else -ticks)
                self.pointer.syn()

    # ---------- main loop ----------
    def run(self):
        log("started; waiting for a remote (TV remote over HDMI-CEC, IR receiver, USB remote)")
        self.move_to(0.5, 0.5)
        last_scan = 0.0
        last = time.monotonic()
        while True:
            now = time.monotonic()
            if now - last_scan > 3:
                self.scan()
                last_scan = now
            busy = self.held or self.ok_down is not None or self.back_down is not None
            ready, _, _ = select.select(list(self.devices), [], [], 1 / FPS if busy else 0.5)
            for fd in ready:
                dev = self.devices.get(fd)
                if dev is None:
                    continue
                try:
                    for ev in dev.read():
                        self.handle(ev)
                except OSError:
                    self.drop(fd)
            now = time.monotonic()
            self.step(min(now - last, 0.05))
            self.check_long_presses(now)
            last = now


if __name__ == "__main__":
    try:
        Remote().run()
    except KeyboardInterrupt:
        pass
