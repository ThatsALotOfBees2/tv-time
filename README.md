# TV Time

A TV home screen with **Streamex** and **YouTube**, a **built-in ad blocker** (YouTube ads
included) and **TV-remote control** - including the video players from the different server
providers that Streamex embeds.

Two versions share the same start page and blocking code:

* **Android app** - Android TV, Google TV, Fire TV, TV boxes, phones, tablets (Android 5.0+).
* **Raspberry Pi** - boots straight into the start page in full-screen Chromium; your normal
  TV remote works through HDMI-CEC. See [Raspberry Pi](#raspberry-pi).

## The start page

![TV Time start page](docs/start-page.png)

Big tiles for Streamex and YouTube, a clock, and **Your sites**: pick *Add / edit sites* to add
any other website as a tile (or remove one). Move around with the arrow keys, OK opens a tile.
Source: `web/start/`.

## Get the APK

Every push is built automatically by GitHub Actions (`.github/workflows/build.yml`):

* **Any branch:** open the repo's **Actions** tab, pick the latest "Build APK" run and download
  `tv-time-apk` from the **Artifacts** section (it's a zip with `tv-time.apk` inside).
* **`main` branch:** the APK is also published as a release, so a TV can fetch it directly:
  `https://github.com/ThatsALotOfBees2/tv-time/releases/latest/download/tv-time.apk`

### Installing on a TV
1. On the TV, enable *Settings -> Device Preferences -> Security -> Unknown sources*
   (Fire TV: *My Fire TV -> Developer options -> Install unknown apps*).
2. Install the free **Downloader** app, type the release URL above, and install.
   Or copy the APK with a USB stick and open it with a file manager.
3. TV Time appears on the home screen with its banner.

Every build is signed with the same key (`app/tvtime.keystore`), so new versions install
over old ones without losing your settings.

## Using it with a remote

| Button | Pointer mode (default) | Player mode (fullscreen video) |
|---|---|---|
| D-pad arrows | move the on-screen pointer (hold to speed up; push against an edge to scroll) | Left/Right: seek -/+10 s, Up/Down: back to pointer |
| OK | click where the pointer is | play / pause |
| **Hold OK** or **MENU** | open the TV Time menu | open the menu |
| Back | go back a page (twice on the first page to exit) | leave fullscreen |
| **Hold Back** | open the menu | open the menu |
| Play/Pause, FF, Rewind | control the video | control the video |
| Channel +/- | scroll a full page | |

The pointer sends real taps, so it works inside every embedded video player (server 1,
server 2, ...) no matter which provider hosts it. When a video goes fullscreen the app
switches to Player mode automatically; you can also turn it on from the menu.

On the start page the arrows move the highlight between tiles instead of the pointer.

**Menu:** Home, Start page, Back, Forward, Reload, Go to address/search, Player controls mode, Zoom,
Ad blocker on/off (with blocked count), Block redirects on/off, Desktop version of sites,
Make this page my Home, Clear cookies & cache, Exit.

## How the ad blocking works

1. **Network blocking** - every request from the page and every iframe is checked against a
   built-in list (`app/src/main/assets/adblock_hosts.txt`) plus the StevenBlack hosts list,
   downloaded in the background and refreshed weekly. Matches get an empty response.
2. **Pop-up / pop-under blocking** - new windows are always refused; `window.open` is
   neutralised inside every frame.
3. **Redirect blocking** - the main page is only allowed to navigate within the site you're
   on (mirrors like `streamex.to` count as the same site). Hijacked clicks that try to send
   you to another site are stopped and shown as "Blocked redirect to ...". Links to other
   apps (`intent://`, `market://`) are always blocked. Turn this off in the menu if a site
   legitimately needs to leave its domain; any address you type yourself is always allowed.
4. **Click-trap removal** - invisible full-screen overlays that ad scripts put over the player
   are removed, and common ad boxes are hidden.
5. **YouTube** (`web/shield/youtube.js`) - the ad schedule is removed from the video data before
   YouTube's player sees it, so ads aren't scheduled at all. If one plays anyway it is muted,
   fast-forwarded and skipped; feed/search ads and the "ad blockers are not allowed" pop-up are
   removed. YouTube changes this regularly, so an occasional ad may slip through until the
   script is updated.

Steps 2 and 4 run inside cross-origin video-player iframes too (needs a reasonably recent
Android System WebView; update it from the Play Store if a player misbehaves).

## Changing the home screen

The app opens the start page by default (`default_home_url` in `app/src/main/res/values/strings.xml`).
To open a site directly instead: *Menu -> Go to address*, then *Menu -> Make this page my Home*.
The big tiles are defined at the top of `web/start/start.js`.

## Building locally

Needs the Android SDK (Android Studio, or command-line tools with `ANDROID_HOME` set) and JDK 17+:

```
./gradlew assembleRelease
# -> app/build/outputs/apk/release/app-release.apk
```

## Raspberry Pi

Turns a Raspberry Pi (4 or 5 recommended) into a TV box: it boots straight to the TV Time start
page in full-screen Chromium, with the TV Time extension doing the ad / pop-up / redirect
blocking, and your TV's own remote driving it over HDMI-CEC.

### Setup
On a fresh **Raspberry Pi OS (with desktop)**, open a terminal and run:

```
git clone https://github.com/ThatsALotOfBees2/tv-time.git
cd tv-time
./pi/install.sh
sudo reboot
```

Make sure HDMI-CEC is switched on in your TV's settings (it has a brand name: Samsung *Anynet+*,
LG *SimpLink*, Sony *Bravia Sync*, Philips *EasyLink*, Panasonic *VIERA Link*...).

### Remote control on the Pi

| Button | Pointer mode (default) | Arrow-key mode |
|---|---|---|
| Arrows | move the pointer (hold = faster, push against an edge = scroll) | real arrow keys (start page, video players, YouTube seeking) |
| OK | click | Enter |
| **Hold OK** | switch to arrow-key mode (pointer jumps to the corner) | switch back to the pointer (pointer jumps to the middle) |
| Back | leave fullscreen video, otherwise go back a page | same |
| **Hold Back** / Home / Menu | TV Time start page | same |
| Play/Pause, Stop | control the video | same |
| Fast-forward / Rewind | seek (Right / Left arrow) | same |
| Channel +/- | Page Up / Page Down | same |

A keyboard and mouse, or a cheap "air mouse" remote, work too. The pointer service only takes
over devices that look like remotes; keyboards and mice are left alone. With a keyboard,
**Alt+Shift+H** opens the start page and **Alt+Shift+B** goes back.

### What gets installed
* `~/.config/autostart/tv-time.desktop` runs `pi/launch.sh` at login: Chromium in kiosk mode on
  the start page, with `pi/extension` loaded and its own profile in `~/.config/tv-time-browser`.
* The `tvtime-remote` systemd service runs `pi/tvremote.py`. It sets up HDMI-CEC
  (`pi/cec-setup.sh`) and turns remote button presses into pointer moves, clicks and keys.
* The Pi boots to the desktop with auto-login and screen blanking turned off.

Useful commands (e.g. over SSH):
```
journalctl -u tvtime-remote -f                                  # see which remote was found / button presses
touch ~/.config/tv-time-browser/STOP && pkill -f chromium       # close the TV screen
~/tv-time/pi/launch.sh &                                        # start it again
TVTIME_SCALE=1.25 ~/tv-time/pi/launch.sh &                      # bigger text on a large TV
```
To update: `cd ~/tv-time && git pull && ./pi/install.sh`.

### The Pi extension
`pi/extension` is generated from the shared sources by `python3 pi/build-extension.py` (the CI
checks it's up to date). Besides the start page and the blocking scripts, its background worker:
* closes any tab that another tab opened (pop-ups / pop-unders);
* only lets pages navigate to sites on your start page (Streamex, YouTube, your own tiles) and
  bounces everything else back via a "Blocked a redirect" screen;
* downloads the StevenBlack hosts list weekly (~77,000 ad/tracker domains) on top of the built-in list.

## Project layout

```
web/                    shared by the Android app and the Raspberry Pi
  start/                the start page (index.html, start.css, start.js)
  shield/youtube.js     YouTube ad remover
app/src/main/java/com/tvtime/app/
  MainActivity.kt       WebView, remote-control handling, menu, fullscreen video, redirect blocking
  CursorView.kt         the on-screen pointer (smooth acceleration, edge scrolling)
  AdBlocker.kt          host-list based request blocking
app/src/main/assets/
  inject.js             runs in the page + every iframe: pop-ups, click traps, media keys, scrolling
  adblock_hosts.txt     built-in block list
pi/
  install.sh            one-time Raspberry Pi setup
  launch.sh             starts Chromium full screen on the start page
  tvremote.py           TV remote (HDMI-CEC / IR) -> pointer + keys
  cec-setup.sh          registers the Pi on HDMI-CEC
  build-extension.py    builds pi/extension from the shared sources
  extension/            the Chromium extension (generated + background.js, blocked page)
```
