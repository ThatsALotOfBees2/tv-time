/*
 * TV Time helper script.
 * Injected at document start into the page AND into every iframe (so it also runs inside
 * the video players hosted on other servers). It:
 *   1. stops pop-ups / pop-unders (window.open, target=_blank hijacks)
 *   2. removes invisible full-screen "click trap" overlays used by ad scripts
 *   3. hides common ad boxes
 *   4. lets the remote control the video (play/pause/seek) in whatever frame it lives
 *   5. scrolls whatever is under the pointer when the pointer is pushed against an edge
 */
(function () {
  'use strict';
  if (window.__tvtimeLoaded) return;
  window.__tvtimeLoaded = true;

  var isTop = (window === window.top);

  // ---------- 1. Pop-up blocking ----------
  function fakeWindow() {
    var noop = function () {};
    var fake = {
      closed: false, close: function () { fake.closed = true; }, focus: noop, blur: noop,
      postMessage: noop, addEventListener: noop, removeEventListener: noop,
      document: { write: noop, writeln: noop, open: noop, close: noop, body: null },
      location: { href: '', replace: noop, assign: noop }, opener: null
    };
    return fake;
  }
  try {
    Object.defineProperty(window, 'open', {
      value: function () { return fakeWindow(); },
      writable: false, configurable: false
    });
  } catch (e) {
    try { window.open = function () { return fakeWindow(); }; } catch (e2) {}
  }

  // Links that want a new tab: open same-site ones in place, drop the rest.
  function siteKey(host) {
    var parts = (host || '').toLowerCase().split('.');
    if (parts.length < 2) return parts[0] || '';
    var two = parts.slice(-2).join('.');
    if (/^(co|com|org|net|ac)\.[a-z]{2}$/.test(two) && parts.length >= 3) return parts[parts.length - 3];
    return parts[parts.length - 2];
  }
  document.addEventListener('click', function (ev) {
    var a = ev.target && ev.target.closest ? ev.target.closest('a[target]') : null;
    if (!a) return;
    var t = (a.getAttribute('target') || '').toLowerCase();
    if (t === '_self' || t === '_parent' || t === '_top' || t === '') return;
    var same = false;
    try { same = siteKey(new URL(a.href, location.href).hostname) === siteKey(location.hostname); } catch (e) {}
    if (same && isTop) {
      a.setAttribute('target', '_self');
    } else if (!same) {
      ev.preventDefault();
      ev.stopImmediatePropagation();
    }
  }, true);

  // ---------- 2. Click-trap overlay removal ----------
  function isTrap(el) {
    try {
      if (!el || el === document.body || el === document.documentElement) return false;
      var cs = getComputedStyle(el);
      if (cs.position !== 'fixed' && cs.position !== 'absolute') return false;
      var z = parseInt(cs.zIndex, 10) || 0;
      if (z < 1000) return false;
      var r = el.getBoundingClientRect();
      if (r.width < innerWidth * 0.7 || r.height < innerHeight * 0.7) return false;
      if (el.querySelector('video, img, iframe, button, input, select, textarea, svg, canvas')) return false;
      var invisible = parseFloat(cs.opacity) < 0.05 ||
        ((cs.backgroundColor === 'rgba(0, 0, 0, 0)' || cs.backgroundColor === 'transparent') &&
         cs.backgroundImage === 'none' && !(el.innerText || '').trim());
      return invisible;
    } catch (e) { return false; }
  }
  function sweep() {
    if (!document.body) return;
    var kids = document.body.children;
    for (var i = kids.length - 1; i >= 0; i--) {
      if (isTrap(kids[i])) kids[i].remove();
    }
  }
  setInterval(sweep, 1500);
  document.addEventListener('DOMContentLoaded', sweep);

  // ---------- 3. Cosmetic ad hiding ----------
  var css = [
    'ins.adsbygoogle', '[id^="google_ads"]', '[id^="div-gpt-ad"]', 'iframe[src*="doubleclick"]',
    'iframe[src*="googlesyndication"]', '[class*="adsbox"]', '.ad-banner', '.ad-container',
    '.banner-ads', '[id*="ScriptRoot"]', 'div[data-ad]', 'div[data-adunit]', '.mgbox',
    'a[href*="popads"]', 'a[href*="adsterra"]', 'a[href*="clickadu"]'
  ].join(',') + '{display:none !important;visibility:hidden !important;}';
  function addStyle() {
    var root = document.head || document.documentElement;
    if (!root) return;
    var s = document.createElement('style');
    s.textContent = css;
    root.appendChild(s);
  }
  if (document.documentElement) addStyle();
  else document.addEventListener('DOMContentLoaded', addStyle);

  // ---------- 4. Media control ----------
  function area(v) { var r = v.getBoundingClientRect(); return r.width * r.height; }
  function bestVideo() {
    var vids = Array.prototype.slice.call(document.querySelectorAll('video'));
    if (!vids.length) return null;
    var playing = vids.filter(function (v) { return !v.paused && !v.ended; });
    if (playing.length) return playing.sort(function (a, b) { return area(b) - area(a); })[0];
    var withSrc = vids.filter(function (v) { return v.currentSrc || v.src || v.readyState > 0; });
    var pool = withSrc.length ? withSrc : vids;
    return pool.sort(function (a, b) { return area(b) - area(a); })[0];
  }
  function act(cmd) {
    var v = bestVideo();
    if (!v) return false;
    try {
      if (cmd === 'toggle') { if (v.paused) v.play(); else v.pause(); }
      else if (cmd === 'play') v.play();
      else if (cmd === 'pause') v.pause();
      else if (cmd.indexOf('seek:') === 0) {
        var d = parseFloat(cmd.substring(5)) || 0;
        var t = v.currentTime + d;
        if (isFinite(v.duration)) t = Math.min(t, v.duration - 1);
        v.currentTime = Math.max(0, t);
      }
      else if (cmd === 'mute') v.muted = !v.muted;
    } catch (e) {}
    return true;
  }
  function broadcast(cmd) {
    for (var i = 0; i < window.frames.length; i++) {
      try { window.frames[i].postMessage({ __tvtime: cmd }, '*'); } catch (e) {}
    }
  }
  function handle(cmd) {
    var handled = act(cmd);
    // Only pass the command down if this frame had no video, so play/pause doesn't double-toggle.
    if (!handled) broadcast(cmd);
  }
  window.addEventListener('message', function (ev) {
    if (ev.data && typeof ev.data.__tvtime === 'string') handle(ev.data.__tvtime);
  });
  if (isTop) window.__tvtimeMedia = handle;
  window.__tvtimeAct = act;   // used by the Android app's bridge.js (each frame acts on its own video)

  // ---------- 5. Scroll under pointer ----------
  if (isTop) {
    window.__tvtimeScroll = function (x, y, dx, dy) {
      var el = document.elementFromPoint(x, y);
      while (el && el !== document.body && el !== document.documentElement) {
        var cs = getComputedStyle(el);
        var canY = dy !== 0 && /(auto|scroll)/.test(cs.overflowY) && el.scrollHeight > el.clientHeight + 1;
        var canX = dx !== 0 && /(auto|scroll)/.test(cs.overflowX) && el.scrollWidth > el.clientWidth + 1;
        if (canY || canX) {
          var bt = el.scrollTop, bl = el.scrollLeft;
          el.scrollBy(dx, dy);
          if (el.scrollTop !== bt || el.scrollLeft !== bl) return true;
        }
        el = el.parentElement;
      }
      window.scrollBy(dx, dy);
      return true;
    };
  }
})();
