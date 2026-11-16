/*
 * TV Time start page. Used as the home screen of the Android app and of the Raspberry Pi
 * (where it ships inside the TV Time browser extension).
 * Fully usable with just arrow keys + Enter, and with a mouse/pointer.
 */
(function () {
  'use strict';

  var isExtension = !!(window.chrome && chrome.runtime && chrome.runtime.id);
  var isAndroid = /Android/i.test(navigator.userAgent);

  var MAIN_TILES = [
    {
      name: 'Streamex', sub: 'Movies & series', url: 'https://streamex.hn/',
      bg: 'linear-gradient(135deg, #e50914 0%, #8a0710 60%, #3d0307 100%)',
      icon: '<svg viewBox="0 0 48 48"><rect x="6" y="9" width="36" height="30" rx="4" fill="none" stroke="#fff" stroke-width="3.2"/>' +
        '<path d="M6 17h36M6 31h36M15 9v8M24 9v8M33 9v8M15 31v8M24 31v8M33 31v8" stroke="#fff" stroke-width="3.2"/></svg>'
    },
    {
      name: 'YouTube', sub: 'Videos, without the ads', url: 'https://www.youtube.com/',
      bg: 'linear-gradient(135deg, #ff4e45 0%, #c4120a 55%, #520604 100%)',
      icon: '<svg viewBox="0 0 48 48"><rect x="4" y="10" width="40" height="28" rx="9" fill="#fff"/>' +
        '<path d="M20 17v14l12-7z" fill="#c4120a"/></svg>'
    }
  ];

  var STORE_KEY = 'tvtime.customSites';
  var COLORS = ['#2f80ed', '#9b51e0', '#27ae60', '#f2994a', '#eb5757', '#00b8d9', '#d6409f'];

  // ---------- storage ----------
  function loadSites() {
    try { return JSON.parse(localStorage.getItem(STORE_KEY)) || []; } catch (e) { return []; }
  }
  function saveSites(list) {
    try { localStorage.setItem(STORE_KEY, JSON.stringify(list)); } catch (e) {}
    tellExtension(list);
  }
  function siteKey(host) {
    var parts = (host || '').toLowerCase().split('.');
    if (parts.length < 2) return parts[0] || '';
    var two = parts.slice(-2).join('.');
    if (/^(co|com|org|net|ac)\.[a-z]{2}$/.test(two) && parts.length >= 3) return parts[parts.length - 3];
    return parts[parts.length - 2];
  }
  // On the Pi, the extension only lets the page navigate to sites you chose; keep it informed.
  function tellExtension(list) {
    if (!isExtension) return;
    var keys = list.map(function (s) {
      try { return siteKey(new URL(s.url).hostname); } catch (e) { return ''; }
    }).filter(Boolean);
    try { chrome.runtime.sendMessage({ type: 'tvtime-sites', keys: keys }); } catch (e) {}
  }

  // ---------- rendering ----------
  function el(tag, cls, html) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (html != null) e.innerHTML = html;
    return e;
  }
  function text(s) {
    var d = document.createElement('div');
    d.textContent = s;
    return d.innerHTML;
  }

  function render() {
    var main = document.getElementById('main-tiles');
    main.innerHTML = '';
    MAIN_TILES.forEach(function (t) {
      var a = el('a', 'tile tile-big');
      a.href = t.url;
      a.setAttribute('data-nav', '');
      a.style.background = t.bg;
      a.innerHTML = '<div class="tile-icon">' + t.icon + '</div>' +
        '<div class="tile-name">' + text(t.name) + '</div><div class="tile-sub">' + text(t.sub) + '</div>';
      main.appendChild(a);
    });

    var row = document.getElementById('custom-tiles');
    row.innerHTML = '';
    loadSites().forEach(function (s, i) {
      var a = el('a', 'tile tile-small');
      a.href = s.url;
      a.setAttribute('data-nav', '');
      var host = '';
      try { host = new URL(s.url).hostname.replace(/^www\./, ''); } catch (e) {}
      a.innerHTML = '<div class="tile-letter" style="background:' + COLORS[i % COLORS.length] + '">' +
        text((s.name || host || '?').charAt(0).toUpperCase()) + '</div>' +
        '<div class="tile-name">' + text(s.name || host) + '</div><div class="tile-sub">' + text(host) + '</div>';
      row.appendChild(a);
    });

    var add = el('button', 'tile tile-small tile-add');
    add.type = 'button';
    add.id = 'add-tile';
    add.setAttribute('data-nav', '');
    add.innerHTML = '<div class="plus">+</div><div class="tile-name">Add / edit sites</div>';
    add.addEventListener('click', openDialog);
    row.appendChild(add);
  }

  function renderHints() {
    var h = document.getElementById('hints');
    var items = isAndroid
      ? [['&#9664;&#9654;', 'Choose'], ['OK', 'Open'], ['Hold OK', 'Menu'], ['Back', 'Exit']]
      : [['&#9664;&#9654;', 'Move'], ['OK', 'Open'], ['Hold OK', 'Arrow-key mode'], ['Hold Back', 'This screen']];
    h.innerHTML = items.map(function (i) { return '<span><kbd>' + i[0] + '</kbd>' + i[1] + '</span>'; }).join('');
  }

  // ---------- clock ----------
  function tick() {
    var now = new Date();
    document.getElementById('time').textContent =
      now.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    document.getElementById('date').textContent =
      now.toLocaleDateString([], { weekday: 'long', day: 'numeric', month: 'long' });
    var h = now.getHours();
    document.getElementById('greeting').textContent =
      h < 5 ? 'Good night' : h < 12 ? 'Good morning' : h < 18 ? 'Good afternoon' : 'Good evening';
  }

  // ---------- add / manage dialog ----------
  var dialog = document.getElementById('dialog');
  var lastFocus = null;

  function openDialog() {
    lastFocus = document.activeElement;
    document.getElementById('f-name').value = '';
    document.getElementById('f-url').value = '';
    document.getElementById('f-error').hidden = true;
    renderManage();
    dialog.hidden = false;
    document.getElementById('f-name').focus();
  }
  function closeDialog() {
    dialog.hidden = true;
    render();
    var target = document.getElementById('add-tile') || lastFocus;
    if (target) target.focus();
  }
  function renderManage() {
    var box = document.getElementById('manage');
    var list = loadSites();
    box.innerHTML = list.length ? '<h3>Your sites</h3>' : '';
    list.forEach(function (s, i) {
      var row = el('div', 'manage-row');
      row.innerHTML = '<span>' + text(s.name) + ' &middot; ' + text(s.url) + '</span>';
      var del = el('button', 'btn', 'Remove');
      del.type = 'button';
      del.setAttribute('data-nav', '');
      del.addEventListener('click', function () {
        var l = loadSites();
        l.splice(i, 1);
        saveSites(l);
        renderManage();
        var first = box.querySelector('.btn') || document.getElementById('f-cancel');
        first.focus();
      });
      row.appendChild(del);
      box.appendChild(row);
    });
  }
  function saveFromDialog() {
    var name = document.getElementById('f-name').value.trim();
    var raw = document.getElementById('f-url').value.trim();
    var err = document.getElementById('f-error');
    if (!raw) { err.textContent = 'Please type an address.'; err.hidden = false; return; }
    if (!/^https?:\/\//i.test(raw)) raw = 'https://' + raw;
    var url;
    try { url = new URL(raw); } catch (e) { url = null; }
    if (!url || url.hostname.indexOf('.') < 0) { err.textContent = 'That doesn\'t look like a web address.'; err.hidden = false; return; }
    if (!name) {
      var h = url.hostname.replace(/^www\./, '').split('.')[0];
      name = h.charAt(0).toUpperCase() + h.slice(1);
    }
    var list = loadSites();
    list.push({ name: name, url: url.href });
    saveSites(list);
    closeDialog();
  }
  document.getElementById('f-save').addEventListener('click', saveFromDialog);
  document.getElementById('f-cancel').addEventListener('click', closeDialog);
  dialog.addEventListener('click', function (e) { if (e.target === dialog) closeDialog(); });

  // ---------- arrow-key navigation ----------
  function navItems() {
    var scope = dialog.hidden ? document : dialog;
    return Array.prototype.filter.call(scope.querySelectorAll('[data-nav]'), function (n) {
      return n.offsetParent !== null && (dialog.hidden || dialog.contains(n));
    });
  }
  function move(dir) {
    var items = navItems();
    if (!items.length) return;
    var cur = document.activeElement;
    if (items.indexOf(cur) < 0) { items[0].focus(); return; }
    var r = cur.getBoundingClientRect();
    var cx = r.left + r.width / 2, cy = r.top + r.height / 2;
    var best = pick(items, cur, r, cx, cy, dir, true) ||
      (dir === 'up' || dir === 'down' ? pick(items, cur, r, cx, cy, dir, false) : null);
    if (best) best.focus();
  }
  // strict: only the same row/column or a 45-degree cone. Not strict: anything in that direction.
  function pick(items, cur, r, cx, cy, dir, strict) {
    var best = null, bestScore = Infinity;
    items.forEach(function (n) {
      if (n === cur) return;
      var b = n.getBoundingClientRect();
      var x = b.left + b.width / 2, y = b.top + b.height / 2;
      var dx = x - cx, dy = y - cy, main, cross, overlap;
      if (dir === 'left' || dir === 'right') {
        if (dir === 'left' ? dx > -2 : dx < 2) return;
        main = Math.abs(dx); cross = Math.abs(dy);
        overlap = b.top < r.bottom && b.bottom > r.top;          // same row
      } else {
        if (dir === 'up' ? dy > -2 : dy < 2) return;
        main = Math.abs(dy); cross = Math.abs(dx);
        overlap = b.left < r.right && b.right > r.left;          // same column
      }
      if (strict && !overlap && cross > main) return;            // outside a 45-degree cone
      var score = overlap ? main : main + cross * 2.5 + 10000;
      if (score < bestScore) { bestScore = score; best = n; }
    });
    return best;
  }
  var KEYS = { ArrowLeft: 'left', ArrowRight: 'right', ArrowUp: 'up', ArrowDown: 'down' };
  document.addEventListener('keydown', function (e) {
    var dir = KEYS[e.key];
    var inInput = document.activeElement && document.activeElement.tagName === 'INPUT';
    if (dir) {
      if (inInput && (dir === 'left' || dir === 'right')) return; // let the caret move
      e.preventDefault();
      move(dir);
      return;
    }
    if (e.key === 'Enter' && inInput) {
      e.preventDefault();
      if (document.activeElement.id === 'f-name') document.getElementById('f-url').focus();
      else saveFromDialog();
      return;
    }
    if ((e.key === 'Escape' || e.key === 'Backspace' && !inInput) && !dialog.hidden) {
      e.preventDefault();
      closeDialog();
    }
  });
  // Hovering with the pointer moves the focus too, so pointer and arrow keys never disagree.
  document.addEventListener('mouseover', function (e) {
    var t = e.target.closest && e.target.closest('[data-nav]');
    if (t && t !== document.activeElement && t.tagName !== 'INPUT') t.focus({ preventScroll: true });
  });

  // ---------- go ----------
  render();
  renderHints();
  tick();
  setInterval(tick, 10000);
  tellExtension(loadSites());
  var first = document.querySelector('[data-nav]');
  if (first) first.focus();
})();
