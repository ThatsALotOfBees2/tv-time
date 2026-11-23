/*
 * TV Time start page. Used as the home screen of the Android app and of the Raspberry Pi
 * (where it ships inside the TV Time browser extension).
 * Fully usable with just arrow keys + Enter, and with a mouse/pointer.
 */
(function () {
  'use strict';

  var isExtension = !!(window.chrome && chrome.runtime && chrome.runtime.id);
  var isAndroid = /Android/i.test(navigator.userAgent);

  // ---------- pixel art ----------
  // Each icon is a grid: '#' = main colour, 'o' = second colour, anything else = empty.
  var ICONS = {
    film: [
      '################',
      '#.##.##.##.##.##',
      '################',
      '#..............#',
      '#.....#........#',
      '#.....##.......#',
      '#.....###......#',
      '#.....##.......#',
      '#.....#........#',
      '################',
      '#.##.##.##.##.##',
      '################'
    ],
    play: [
      '..############..',
      '.##############.',
      '################',
      '######o#########',
      '######oo########',
      '######ooo#######',
      '######ooo#######',
      '######oo########',
      '######o#########',
      '################',
      '.##############.',
      '..############..'
    ],
    tv: [
      '...o.......o...',
      '....o.....o....',
      '.....o...o.....',
      '###############',
      '#.............#',
      '#.....#.......#',
      '#.....##......#',
      '#.....###.....#',
      '#.....##......#',
      '#.....#.......#',
      '#.............#',
      '###############'
    ],
    shield: [
      '#########',
      '#########',
      '#########',
      '#########',
      '.#######.',
      '.#######.',
      '..#####..',
      '...###...',
      '....#....'
    ]
  };
  function pixelSvg(rows, main, second) {
    var h = rows.length, w = rows[0].length, rects = '';
    rows.forEach(function (row, y) {
      for (var x = 0; x < w;) {
        var ch = row.charAt(x);
        if (ch !== '#' && ch !== 'o') { x++; continue; }
        var run = 1;
        while (row.charAt(x + run) === ch) run++;
        rects += '<rect x="' + x + '" y="' + y + '" width="' + run + '" height="1" fill="' + (ch === '#' ? main : second) + '"/>';
        x += run;
      }
    });
    return '<svg class="px-icon" viewBox="0 0 ' + w + ' ' + h + '" shape-rendering="crispEdges" preserveAspectRatio="xMidYMid meet">' + rects + '</svg>';
  }

  // ---------- look: "retro" (pixel art) or "sleek" (dark monochrome, royal purple) ----------
  var THEME_KEY = 'tvtime.theme';
  function loadTheme() {
    var t = null;
    try { t = localStorage.getItem(THEME_KEY); } catch (e) {}
    return t === 'sleek' || t === 'retro' ? t : (window.TVTIME_THEME || 'retro');
  }
  var THEME = loadTheme();
  document.documentElement.classList.add('theme-' + THEME);
  var SLEEK = THEME === 'sleek';

  // Clean line icons for the sleek look (24x24, stroked).
  function lineSvg(paths) {
    return '<svg class="px-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" ' +
      'stroke-linecap="round" stroke-linejoin="round">' + paths + '</svg>';
  }
  var LINE = {
    film: lineSvg('<rect x="3" y="5" width="18" height="14" rx="2"/><path d="M3 9h18M3 15h18M8 5v4M16 5v4M8 15v4M16 15v4"/>'),
    play: lineSvg('<rect x="2.5" y="5" width="19" height="14" rx="4"/><path d="M10 9.2v5.6l4.8-2.8z" fill="currentColor"/>'),
    tv: lineSvg('<rect x="3" y="7" width="18" height="13" rx="2"/><path d="M8 3l4 4 4-4"/>'),
    shield: lineSvg('<path d="M12 3l7 3v5c0 4.4-3 8.2-7 9.5C8 19.2 5 15.4 5 11V6z"/><path d="M9 12l2 2 4-4"/>')
  };

  var MAIN_TILES = [
    { name: 'Streamex', sub: 'Movies & series', url: 'https://streamex.hn/', accent: '#ff3d8b',
      icon: SLEEK ? LINE.film : pixelSvg(ICONS.film, '#ffffff') },
    { name: 'YouTube', sub: SLEEK ? 'Videos, without the ads' : 'Videos, no ads', url: 'https://www.youtube.com/', accent: '#ff2b2b',
      icon: SLEEK ? LINE.play : pixelSvg(ICONS.play, '#ffffff', '#07030f') }
  ];

  var STORE_KEY = 'tvtime.customSites';
  var COLORS = ['#4df3ff', '#ffd23f', '#9dff5c', '#c38bff', '#ff9d3a', '#ff3d8b', '#ffffff'];

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
      a.style.setProperty('--accent', t.accent);
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
      a.style.setProperty('--accent', COLORS[i % COLORS.length]);
      a.innerHTML = '<div class="tile-letter">' +
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
    var items = window.tvtimeDesktop
      ? [['Mouse / &#9664;&#9654;', 'Choose'], ['Enter', 'Open'], ['Alt+&#8592;', 'Back'], ['Home', 'This screen'], ['F11', 'Full screen']]
      : isAndroid
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
    renderIntroToggle();
    renderThemeToggle();
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
  // ---------- startup intro settings (intro.js) ----------
  function renderThemeToggle() {
    document.getElementById('theme-toggle').textContent = 'Look: ' + (SLEEK ? 'Sleek' : 'Retro');
  }
  document.getElementById('theme-toggle').addEventListener('click', function () {
    try { localStorage.setItem(THEME_KEY, SLEEK ? 'retro' : 'sleek'); } catch (e) {}
    try { sessionStorage.setItem('tvtime.introShown', '1'); } catch (e) {}   // don't replay the intro
    location.reload();
  });

  function renderIntroToggle() {
    var on = !window.TVTimeIntro || window.TVTimeIntro.enabled();
    document.getElementById('intro-toggle').textContent = 'Intro at start-up: ' + (on ? 'on' : 'off');
  }
  document.getElementById('intro-toggle').addEventListener('click', function () {
    if (!window.TVTimeIntro) return;
    window.TVTimeIntro.setEnabled(!window.TVTimeIntro.enabled());
    renderIntroToggle();
  });
  document.getElementById('intro-play').addEventListener('click', function () {
    closeDialog();
    if (window.TVTimeIntro) window.TVTimeIntro.play();
  });

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
    if (best) { best.focus(); sound('move'); }
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

  // ---------- menu sounds (little chiptune blips) ----------
  var audio = null;
  function sound(kind) {
    try {
      if (!audio) {
        var AC = window.AudioContext || window.webkitAudioContext;
        if (!AC) return;
        audio = new AC();
      }
      if (audio.state === 'suspended') audio.resume();
      var t = audio.currentTime, o = audio.createOscillator(), g = audio.createGain();
      o.type = 'square';
      if (kind === 'move') {
        o.frequency.setValueAtTime(880, t);
      } else {
        o.frequency.setValueAtTime(660, t);
        o.frequency.setValueAtTime(990, t + 0.06);
      }
      var len = kind === 'move' ? 0.05 : 0.16;
      g.gain.setValueAtTime(0.04, t);
      g.gain.exponentialRampToValueAtTime(0.0001, t + len);
      o.connect(g);
      g.connect(audio.destination);
      o.start(t);
      o.stop(t + len + 0.02);
    } catch (e) {}
  }
  // Opening a tile: play the "select" blip, then go.
  document.addEventListener('click', function (e) {
    var a = e.target.closest && e.target.closest('a.tile');
    if (!a || e.defaultPrevented) return;
    e.preventDefault();
    sound('select');
    setTimeout(function () { location.href = a.href; }, 140);
  });

  // ---------- go ----------
  document.getElementById('brand-icon').innerHTML = SLEEK ? LINE.tv : pixelSvg(ICONS.tv, '#ff3d8b', '#ffd23f');
  document.getElementById('shield-icon').innerHTML = SLEEK ? LINE.shield : pixelSvg(ICONS.shield, '#5dff8a');
  render();
  renderHints();
  tick();
  setInterval(tick, 10000);
  tellExtension(loadSites());
  var first = document.querySelector('[data-nav]');
  if (first) first.focus();
})();
