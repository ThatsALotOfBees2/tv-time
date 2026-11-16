/*
 * TV Time startup intro (about 5 seconds, skippable with any button):
 *   old TV switching on -> static -> test card + beep -> game-show title card
 *   "IT'S TV TIME!" with marquee lights, spotlights, confetti, fanfare and applause.
 *
 * Without a clip, all sound is synthesised here. With a file named intro-sound.mp3 (or .ogg /
 * .wav / .m4a) next to this file, that clip plays instead of the fanfare, and the sign pops in
 * word by word in time with it ("IT'S" / "T" / "V" / "TIME!"), followed by a cymbal and applause.
 * intro-sound-timing.js gives the exact moment of each word in the clip (in seconds); without it
 * the timing is estimated from the clip's length.
 *
 * Plays once per start-up. Can be switched off or replayed from "Add / edit sites".
 */
(function () {
  'use strict';

  var PREF = 'tvtime.intro';
  var SHOWN = 'tvtime.introShown';
  var CLIP_NAMES = ['intro-sound.mp3', 'intro-sound.ogg', 'intro-sound.wav', 'intro-sound.m4a'];
  var running = false;

  function enabled() {
    try { return localStorage.getItem(PREF) !== 'off'; } catch (e) { return true; }
  }
  function setEnabled(on) {
    try { localStorage.setItem(PREF, on ? 'on' : 'off'); } catch (e) {}
  }

  // ---------- sound ----------
  function makeSound() {
    var AC = window.AudioContext || window.webkitAudioContext;
    if (!AC) return null;
    var ctx;
    try { ctx = new AC(); } catch (e) { return null; }
    if (ctx.state === 'suspended' && ctx.resume) ctx.resume().catch(function () {});
    var comp = ctx.createDynamicsCompressor();
    var master = ctx.createGain();
    master.gain.value = 0.8;
    comp.connect(master);
    master.connect(ctx.destination);
    var out = comp;

    function now() { return ctx.currentTime; }
    function midi(m) { return 440 * Math.pow(2, (m - 69) / 12); }
    function noise(sec) {
      var b = ctx.createBuffer(1, Math.max(1, Math.floor(ctx.sampleRate * sec)), ctx.sampleRate);
      var d = b.getChannelData(0);
      for (var i = 0; i < d.length; i++) d[i] = Math.random() * 2 - 1;
      return b;
    }
    function play(node, t, dur) { node.start(t); node.stop(t + dur); }

    function brass(t, f, dur, vol) {
      var lp = ctx.createBiquadFilter();
      lp.type = 'lowpass';
      lp.Q.value = 3;
      lp.frequency.setValueAtTime(400, t);
      lp.frequency.linearRampToValueAtTime(3400, t + 0.06);
      lp.frequency.exponentialRampToValueAtTime(1300, t + dur);
      var g = ctx.createGain();
      g.gain.setValueAtTime(0.0001, t);
      g.gain.linearRampToValueAtTime(vol, t + 0.03);
      g.gain.setValueAtTime(vol * 0.8, t + dur * 0.75);
      g.gain.exponentialRampToValueAtTime(0.0001, t + dur + 0.1);
      lp.connect(g);
      g.connect(out);
      [-8, 0, 8].forEach(function (det) {
        var o = ctx.createOscillator();
        o.type = 'sawtooth';
        o.frequency.value = f;
        o.detune.value = det;
        if (dur > 0.4) {                    // a little vibrato on held notes
          var lfo = ctx.createOscillator();
          var lg = ctx.createGain();
          lfo.frequency.value = 5.5;
          lg.gain.value = 9;
          lfo.connect(lg);
          lg.connect(o.detune);
          play(lfo, t + 0.2, dur);
        }
        o.connect(lp);
        play(o, t, dur + 0.15);
      });
    }
    function noiseHit(t, dur, type, freq, vol, q) {
      var s = ctx.createBufferSource();
      s.buffer = noise(dur);
      var f = ctx.createBiquadFilter();
      f.type = type;
      f.frequency.value = freq;
      if (q) f.Q.value = q;
      var g = ctx.createGain();
      g.gain.setValueAtTime(vol, t);
      g.gain.exponentialRampToValueAtTime(0.0001, t + dur);
      s.connect(f);
      f.connect(g);
      g.connect(out);
      play(s, t, dur);
    }

    return {
      // the "thunk" of an old TV switching on
      powerOn: function () {
        var t = now();
        var o = ctx.createOscillator();
        o.frequency.setValueAtTime(160, t);
        o.frequency.exponentialRampToValueAtTime(38, t + 0.35);
        var g = ctx.createGain();
        g.gain.setValueAtTime(0.9, t);
        g.gain.exponentialRampToValueAtTime(0.0001, t + 0.4);
        o.connect(g);
        g.connect(out);
        play(o, t, 0.45);
        noiseHit(t, 0.08, 'highpass', 2000, 0.5);
      },
      hiss: function (dur) {
        var t = now();
        var s = ctx.createBufferSource();
        s.buffer = noise(dur);
        var f = ctx.createBiquadFilter();
        f.type = 'highpass';
        f.frequency.value = 900;
        var g = ctx.createGain();
        g.gain.setValueAtTime(0.0001, t);
        g.gain.linearRampToValueAtTime(0.32, t + 0.05);
        g.gain.setValueAtTime(0.32, t + dur - 0.08);
        g.gain.linearRampToValueAtTime(0.0001, t + dur);
        s.connect(f);
        f.connect(g);
        g.connect(out);
        play(s, t, dur);
      },
      // the 1 kHz test-card tone
      beep: function (dur) {
        var t = now();
        var o = ctx.createOscillator();
        o.frequency.value = 1000;
        var g = ctx.createGain();
        g.gain.setValueAtTime(0.16, t);
        g.gain.setValueAtTime(0.16, t + dur - 0.02);
        g.gain.linearRampToValueAtTime(0.0001, t + dur);
        o.connect(g);
        g.connect(out);
        play(o, t, dur);
      },
      // drum roll + "ta-da-da-DAAA" + big chord + cymbal
      fanfare: function () {
        var t = now() + 0.05;
        for (var i = 0; i < 22; i++) noiseHit(t + i * 0.03, 0.05, 'bandpass', 1900, 0.08 + i * 0.012, 0.8);
        var t2 = t + 0.62;
        brass(t2, midi(67), 0.13, 0.2);
        brass(t2 + 0.15, midi(67), 0.13, 0.2);
        brass(t2 + 0.3, midi(72), 0.13, 0.22);
        brass(t2 + 0.45, midi(76), 0.42, 0.24);
        var t3 = t2 + 0.9;
        [72, 76, 79, 84].forEach(function (m) { brass(t3, midi(m), 1.7, 0.13); });
        brass(t3, midi(48), 1.7, 0.16);
        noiseHit(t3, 1.9, 'highpass', 5500, 0.45);
        noiseHit(t3, 0.25, 'lowpass', 180, 0.9);
      },
      // a studio audience
      applause: function (delay, dur) {
        var t = now() + delay;
        var rate = ctx.sampleRate;
        var b = ctx.createBuffer(1, Math.floor(rate * dur), rate);
        var d = b.getChannelData(0);
        var claps = Math.floor(dur * 60);
        for (var c = 0; c < claps; c++) {
          var start = Math.floor(Math.random() * (d.length - rate * 0.02));
          var len = Math.floor(rate * (0.008 + Math.random() * 0.01));
          var amp = 0.25 + Math.random() * 0.6;
          for (var k = 0; k < len; k++) d[start + k] += (Math.random() * 2 - 1) * amp * Math.exp(-k / (len * 0.25));
        }
        for (var j = 0; j < d.length; j++) d[j] = d[j] * 0.5 + (Math.random() * 2 - 1) * 0.04;
        var s = ctx.createBufferSource();
        s.buffer = b;
        var f = ctx.createBiquadFilter();
        f.type = 'bandpass';
        f.frequency.value = 1500;
        f.Q.value = 0.5;
        var g = ctx.createGain();
        g.gain.setValueAtTime(0.0001, t);
        g.gain.linearRampToValueAtTime(0.55, t + 0.35);
        g.gain.setValueAtTime(0.55, t + dur * 0.6);
        g.gain.linearRampToValueAtTime(0.0001, t + dur);
        s.connect(f);
        f.connect(g);
        g.connect(out);
        play(s, t, dur);
      },
      cymbal: function () {
        var t = now();
        noiseHit(t, 1.7, 'highpass', 5500, 0.4);
        noiseHit(t, 0.25, 'lowpass', 180, 0.8);
      },
      stop: function () {
        try {
          master.gain.setTargetAtTime(0, now(), 0.08);
          setTimeout(function () { ctx.close(); }, 500);
        } catch (e) {}
      }
    };
  }

  // Looks for your own sound clip next to this page; resolves to an <audio> or null.
  function findClip() {
    return new Promise(function (resolve) {
      var left = CLIP_NAMES.length, done = false;
      CLIP_NAMES.forEach(function (name) {
        var a = new Audio();
        a.preload = 'auto';
        a.addEventListener('canplaythrough', function () {
          if (!done) { done = true; resolve(a); }
        }, { once: true });
        a.addEventListener('error', function () {
          if (--left === 0 && !done) { done = true; resolve(null); }
        }, { once: true });
        a.src = name;
      });
      setTimeout(function () { if (!done) { done = true; resolve(null); } }, 1300);
    });
  }

  // ---------- picture ----------
  function el(tag, cls, parent) {
    var n = document.createElement(tag);
    if (cls) n.className = cls;
    if (parent) parent.appendChild(n);
    return n;
  }

  function buildBars(root) {
    var bars = el('div', 'layer bars', root);
    var rows = [
      ['bar-top', ['#c0c0c0', '#c0c000', '#00c0c0', '#00c000', '#c000c0', '#c00000', '#0000c0']],
      ['bar-mid', ['#0000c0', '#131313', '#c000c0', '#131313', '#00c0c0', '#131313', '#c0c0c0']],
      ['bar-bot', ['#00214c', '#ffffff', '#32006a', '#131313', '#090909', '#131313', '#1d1d1d']]
    ];
    rows.forEach(function (r) {
      var row = el('div', r[0], bars);
      r[1].forEach(function (c) { el('span', null, row).style.background = c; });
    });
    el('div', 'standby', bars).textContent = 'PLEASE STAND BY';
    return bars;
  }

  function buildStage(root) {
    var stage = el('div', 'layer stage', root);
    el('div', 'spot a', stage);
    el('div', 'spot b', stage);
    el('div', 'curtain l', stage);
    el('div', 'curtain r', stage);
    var sign = el('div', 'marquee', stage);
    var bulbs = el('div', 'bulbs', sign);
    var across = 16, down = 4, i;
    function bulb(x, y) { var b = el('span', 'bulb', bulbs); b.style.left = x + '%'; b.style.top = y + '%'; }
    for (i = 0; i < across; i++) { bulb(i / (across - 1) * 100, 0); }
    for (i = 1; i <= down; i++) { bulb(100, i / (down + 1) * 100); }
    for (i = across - 1; i >= 0; i--) { bulb(i / (across - 1) * 100, 100); }
    for (i = down; i >= 1; i--) { bulb(0, i / (down + 1) * 100); }

    var its = el('div', 'its', sign);
    its.innerHTML = '<span class="ch">IT’S</span>';
    var title = el('div', 'title', sign);
    var html = '';
    'TV TIME!'.split('').forEach(function (c, k) {
      html += '<span class="ch' + (k < 2 ? ' tv' : '') + '">' + (c === ' ' ? '&nbsp;' : c) + '</span>';
    });
    title.innerHTML = html;
    var tag = el('div', 'tagline', stage);
    tag.textContent = 'Now showing on your TV';
    var confetti = el('canvas', 'confetti', stage);
    return { stage: stage, confetti: confetti, letters: sign.querySelectorAll('.ch'), tagline: tag };
  }

  function runStatic(canvas) {
    var w = 320, h = 180;
    canvas.width = w;
    canvas.height = h;
    canvas.style.imageRendering = 'pixelated';
    var c = canvas.getContext('2d');
    var img = c.createImageData(w, h);
    var px = new Uint32Array(img.data.buffer);
    var alive = true;
    (function frame() {
      if (!alive) return;
      var tear = Math.random() < 0.3 ? Math.floor(Math.random() * h) : -1;
      for (var y = 0; y < h; y++) {
        var bright = (y === tear || y === tear + 1) ? 90 : 0;
        for (var x = 0; x < w; x++) {
          var v = Math.min(255, ((Math.random() * 230) | 0) + bright);
          px[y * w + x] = 0xff000000 | (v << 16) | (v << 8) | v;
        }
      }
      c.putImageData(img, 0, 0);
      requestAnimationFrame(frame);
    })();
    return function () { alive = false; };
  }

  function runConfetti(canvas) {
    var c = canvas.getContext('2d');
    var dpr = Math.min(window.devicePixelRatio || 1, 2);
    var W = canvas.width = innerWidth * dpr;
    var H = canvas.height = innerHeight * dpr;
    var colors = ['#ffcf3a', '#ff2b3a', '#ffffff', '#3ad1ff', '#7dff6b', '#ff7ad9'];
    var parts = [];
    for (var i = 0; i < 160; i++) {
      var left = i % 2 === 0;
      parts.push({
        x: left ? W * 0.1 : W * 0.9, y: H * 0.95,
        vx: (left ? 1 : -1) * (W * (0.004 + Math.random() * 0.009)),
        vy: -H * (0.02 + Math.random() * 0.018),
        s: (6 + Math.random() * 8) * dpr, r: Math.random() * 6, vr: (Math.random() - 0.5) * 0.4,
        col: colors[i % colors.length]
      });
    }
    var alive = true;
    (function frame() {
      if (!alive) return;
      c.clearRect(0, 0, W, H);
      parts.forEach(function (p) {
        p.vy += H * 0.0006;
        p.vx *= 0.99;
        p.x += p.vx;
        p.y += p.vy;
        p.r += p.vr;
        c.save();
        c.translate(p.x, p.y);
        c.rotate(p.r);
        c.fillStyle = p.col;
        c.fillRect(-p.s / 2, -p.s / 4, p.s, p.s / 2 * Math.abs(Math.cos(p.r * 2)) + 1);
        c.restore();
      });
      requestAnimationFrame(frame);
    })();
    return function () { alive = false; };
  }

  // ---------- the show ----------
  function play(opts) {
    if (running) return;
    running = true;
    opts = opts || {};
    var timers = [];
    var stops = [];
    var finished = false;
    function at(ms, fn) { timers.push(setTimeout(fn, ms)); }

    var root = el('div', 'intro');
    root.setAttribute('role', 'presentation');
    var staticCanvas = el('canvas', 'layer static', root);
    var roll = el('div', 'roll', root);
    roll.style.display = 'none';
    var bars = buildBars(root);
    var show = buildStage(root);
    var crt = el('div', 'crt-line', root);
    var skip = el('div', 'skip', root);
    skip.textContent = 'Press any button to skip';
    document.body.appendChild(root);

    var sound = opts.sound === false ? null : makeSound();
    var clipPromise = opts.sound === false ? Promise.resolve(null) : findClip();
    var clip = null;

    function finish(fast) {
      if (finished) return;
      finished = true;
      timers.forEach(clearTimeout);
      window.removeEventListener('keydown', onKey, true);
      if (clip) {
        if (fast) clip.pause();
        else { var fade = setInterval(function () { clip.volume = Math.max(0, clip.volume - 0.1); if (clip.volume <= 0) { clip.pause(); clearInterval(fade); } }, 50); }
      }
      if (sound) sound.stop();
      root.classList.add('out');
      setTimeout(function () {
        stops.forEach(function (s) { s(); });
        root.remove();
        running = false;
      }, 560);
    }
    function onKey(e) {
      e.preventDefault();
      e.stopImmediatePropagation();
      if (e.type === 'keydown') finish(true);
    }
    window.addEventListener('keydown', onKey, true);
    root.addEventListener('click', function () { finish(true); });
    root.addEventListener('touchstart', function () { finish(true); }, { passive: true });

    // 1. switch on
    at(30, function () {
      crt.classList.add('go');
      if (sound) sound.powerOn();
    });
    // 2. static
    at(300, function () {
      staticCanvas.classList.add('on');
      roll.style.display = '';
      stops.push(runStatic(staticCanvas));
      if (sound) sound.hiss(0.8);
    });
    // 3. test card
    at(1100, function () {
      staticCanvas.classList.remove('on');
      roll.style.display = 'none';
      stops.forEach(function (s) { s(); });
      stops = [];
      bars.classList.add('on');
      if (sound) sound.beep(0.4);
    });
    // 4. the title card
    at(1550, function () {
      bars.classList.remove('on');
      clipPromise.then(function (found) {
        if (finished) return;
        clip = found;
        show.stage.classList.add('on');
        var letters = show.letters;      // 0 = IT'S, 1 = T, 2 = V, 3 = space, 4-7 = TIME, 8 = !
        function pop(k) {
          var n = letters[k];
          if (!n || n.classList.contains('pop')) return;
          n.classList.add('pop');
          if (k > 0) at(500, function () { n.classList.add('wobble'); });
        }

        if (clip) {
          // Your own clip: the sign pops in word by word, in time with it.
          var d = isFinite(clip.duration) && clip.duration > 0 ? clip.duration : 3;
          var tm = window.TVTIME_INTRO_TIMING ||
            { its: d * 0.02, t: d * 0.25, v: d * 0.41, time: d * 0.58, bang: d * 0.87 };
          var cues = [
            [tm.its, function () { pop(0); }],
            [tm.t, function () { pop(1); }],
            [tm.v, function () { pop(2); pop(3); }],
            [tm.time, function () { [4, 5, 6, 7].forEach(function (k, i) { at(i * 90, function () { pop(k); }); }); }],
            [tm.bang, function () {
              for (var k = 0; k < letters.length; k++) pop(k);
              stops.push(runConfetti(show.confetti));
              if (sound) sound.cymbal();
            }],
            [d, function () {
              show.tagline.classList.add('on');
              if (sound) sound.applause(0, 2.2);
            }]
          ];
          var started = performance.now();
          clip.play().catch(function () {});
          (function follow() {
            if (finished) return;
            // Follow the clip's own position so the sign stays in sync; use the clock if it can't play.
            var pos = clip.ended ? Infinity
              : (!clip.paused || clip.currentTime > 0) ? clip.currentTime
              : Math.max(0, (performance.now() - started) / 1000 - 0.4);
            while (cues.length && pos >= cues[0][0]) cues.shift()[1]();
            if (cues.length) requestAnimationFrame(follow);
          })();
          at(Math.min(9500, d * 1000 + 1800), function () { finish(false); });
          return;
        }

        // Built-in show: drum roll, fanfare, applause.
        if (sound) {
          sound.fanfare();
          sound.applause(1.4, 2.4);
        }
        var first = 650;
        for (var i = 0; i < letters.length; i++) {
          (function (k) {
            // "IT'S" first, then T-V-T-I-M-E-! one letter at a time
            at(first + (k === 0 ? 0 : 260 + k * 85), function () { pop(k); });
          })(i);
        }
        at(first + 900, function () { stops.push(runConfetti(show.confetti)); });
        at(first + 1300, function () { show.tagline.classList.add('on'); });
        at(first + 3250, function () { finish(false); });
      });
    });
  }

  window.TVTimeIntro = { play: play, enabled: enabled, setEnabled: setEnabled };

  // Play once per start-up (the app or the Pi's browser starting), not every time you come back here.
  var already = false;
  try { already = sessionStorage.getItem(SHOWN) === '1'; sessionStorage.setItem(SHOWN, '1'); } catch (e) {}
  if (window.TVTIME_INTRO_AUTOPLAY !== false && enabled() && !already) play();
})();
