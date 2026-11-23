// TV Time for PC (Windows / macOS / Linux).
//
// A full-screen window on the TV Time start page, with:
//  - Ghostery's ad blocker built in (EasyList, EasyPrivacy, uBlock filters, cosmetic filtering and
//    scriptlets, the same kind of lists Brave Shields and uBlock Origin use), refreshed on start;
//  - pop-ups and new windows refused, and a redirect guard: pages may only navigate to sites you
//    chose (the start page tiles), so hijacked clicks can't send you to ad sites;
//  - the click-trap remover from the TV apps in every frame (web/inject.js).
//
// Keys: F11 full screen - Home or Ctrl+H start page - Alt+Left / Backspace / mouse back button: back
//       F5 reload - Ctrl+Q quit.
const { app, BrowserWindow, Menu, session } = require('electron');
const fs = require('fs');
const path = require('path');
const { pathToFileURL } = require('url');
const { ElectronBlocker } = require('@ghostery/adblocker-electron');

const WEB = path.join(__dirname, 'web');
const START = pathToFileURL(path.join(WEB, 'start', 'index.html')).href;
const START_DIR = START.slice(0, START.lastIndexOf('/') + 1);
const INJECT = fs.readFileSync(path.join(WEB, 'inject.js'), 'utf8');
const ALLOWED = new Set(['streamex', 'youtube', 'youtu', 'youtube-nocookie', 'google']);

app.commandLine.appendSwitch('autoplay-policy', 'no-user-gesture-required');

function siteKey(host) {
  const parts = (host || '').toLowerCase().split('.');
  if (parts.length < 2) return parts[0] || '';
  const two = parts.slice(-2).join('.');
  if (/^(co|com|org|net|ac)\.[a-z]{2}$/.test(two) && parts.length >= 3) return parts[parts.length - 3];
  return parts[parts.length - 2];
}

async function enableAdBlocker(ses) {
  const cache = path.join(app.getPath('userData'), 'adblock-engine.bin');
  try {
    const blocker = await ElectronBlocker.fromPrebuiltAdsAndTracking(fetch, {
      path: cache,
      read: fs.promises.readFile,
      write: fs.promises.writeFile,
    });
    blocker.enableBlockingInSession(ses);
    console.log('Ad blocker on');
  } catch (e) {
    console.error('Ad blocker could not start:', e);
  }
}

function createWindow() {
  const win = new BrowserWindow({
    width: 1600,
    height: 900,
    fullscreen: true,
    backgroundColor: '#0b0b0d',
    title: 'TV Time',
    icon: path.join(__dirname, 'build', 'icon.png'),
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      sandbox: true,
    },
  });
  const wc = win.webContents;

  // Pop-ups / new windows: never.
  wc.setWindowOpenHandler(() => ({ action: 'deny' }));

  // Redirect guard.
  function blocked(url) {
    let u;
    try { u = new URL(url); } catch (e) { return true; }
    if (u.protocol === 'file:') return !url.startsWith(START_DIR);
    if (u.protocol === 'about:' || u.protocol === 'data:' || u.protocol === 'blob:') return false;
    if (u.protocol !== 'http:' && u.protocol !== 'https:') return true;
    if (wc.getURL().startsWith(START_DIR)) {   // tiles on the start page are sites you chose
      ALLOWED.add(siteKey(u.hostname));
      return false;
    }
    return !ALLOWED.has(siteKey(u.hostname));
  }
  wc.on('will-navigate', (e, url) => { if (blocked(url)) e.preventDefault(); });
  wc.on('will-redirect', (e, url, isInPlace, isMainFrame) => { if (isMainFrame && blocked(url)) e.preventDefault(); });

  // The click-trap remover etc. in the page and every frame (video players included).
  function injectAll() {
    try {
      for (const frame of wc.mainFrame.framesInSubtree) frame.executeJavaScript(INJECT).catch(() => {});
    } catch (e) { /* page went away */ }
  }
  wc.on('dom-ready', injectAll);
  wc.on('did-frame-finish-load', injectAll);

  // Keyboard and mouse shortcuts.
  const home = () => wc.loadURL(START);
  const back = () => { if (wc.navigationHistory.canGoBack()) wc.navigationHistory.goBack(); };
  wc.on('before-input-event', (e, input) => {
    if (input.type !== 'keyDown') return;
    const k = input.key;
    if (k === 'F11') { win.setFullScreen(!win.isFullScreen()); e.preventDefault(); }
    else if (k === 'Home' || (input.control && k.toLowerCase() === 'h')) { home(); e.preventDefault(); }
    else if (input.alt && k === 'ArrowLeft') { back(); e.preventDefault(); }
    else if (k === 'F5') { wc.reload(); e.preventDefault(); }
    else if (input.control && k.toLowerCase() === 'q') { app.quit(); }
    else if (k === 'Backspace') {
      // Backspace = back, unless you're typing in a field.
      wc.executeJavaScript('(function(a){return !!a && (a.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(a.tagName));})(document.activeElement)')
        .then((typing) => { if (!typing) back(); }).catch(() => {});
    }
  });
  win.on('app-command', (e, cmd) => {
    if (cmd === 'browser-backward') back();
    if (cmd === 'browser-forward' && wc.navigationHistory.canGoForward()) wc.navigationHistory.goForward();
  });

  wc.loadURL(START);
}

app.whenReady().then(async () => {
  Menu.setApplicationMenu(null);
  await enableAdBlocker(session.defaultSession);
  createWindow();
});

app.on('window-all-closed', () => app.quit());
