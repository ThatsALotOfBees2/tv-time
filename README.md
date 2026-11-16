# TV Time

A TV-friendly Android app that wraps a streaming website (default: **streamex.hn**) in a
full-screen browser with a **built-in ad blocker** and **TV-remote control**, including the
video players from the different server providers that the site embeds.

Works on Android TV, Google TV, Fire TV sticks, Android TV boxes, phones and tablets
(Android 5.0+). Raspberry Pi is planned for later (see bottom).

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

**Menu:** Home, Back, Forward, Reload, Go to address/search, Player controls mode, Zoom,
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

Steps 2 and 4 run inside cross-origin video-player iframes too (needs a reasonably recent
Android System WebView; update it from the Play Store if a player misbehaves).

## Changing the website

Default home page: `app/src/main/res/values/strings.xml` -> `default_home_url`.
Or, in the app: *Menu -> Go to address*, then *Menu -> Make this page my Home*.

## Building locally

Needs the Android SDK (Android Studio, or command-line tools with `ANDROID_HOME` set) and JDK 17+:

```
./gradlew assembleRelease
# -> app/build/outputs/apk/release/app-release.apk
```

## Project layout

```
app/src/main/java/com/tvtime/app/
  MainActivity.kt   WebView, remote-control handling, menu, fullscreen video, redirect blocking
  CursorView.kt     the on-screen pointer (smooth acceleration, edge scrolling)
  AdBlocker.kt      host-list based request blocking
app/src/main/assets/
  inject.js         runs in the page + every iframe: pop-ups, click traps, media keys, scrolling
  adblock_hosts.txt built-in block list
```

## Raspberry Pi (later)

Two routes, both reuse this work:
* Run Android on the Pi (e.g. LineageOS / KonstaKANG Android TV builds for Pi 4/5) and install
  this same APK.
* A Linux version: Chromium in kiosk mode with the same `inject.js` + block list packaged as an
  extension, and a small key-mapping layer for the remote (HDMI-CEC via `cec-client`).
