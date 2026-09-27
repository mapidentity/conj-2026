// Dev-only source inspector overlay.
// Toggle with the badge (bottom-left) or Alt+Shift+I; Esc turns it off.
// Hover boxes the nearest source-tagged element and shows the breadcrumb of
// its tagged ancestors; click opens the source in the editor via the dev
// socket. Crumbs are clickable too — open any ancestor directly.
// Point, then hold Alt (Option): the path freezes, so the pointer can travel
// to the crumbs without losing it; the wheel walks it, one step per notch or
// flick (up = outward, down = back in), and a click opens the selected step.
// Let go of Alt and hovering resumes.
(function () {
  'use strict';
  var enabled = false;
  try { enabled = localStorage.getItem('inspector') === '1'; } catch (e) {}

  // --- ui ---
  var style = document.createElement('style');
  style.textContent =
    '.insp-box{position:fixed;z-index:99997;pointer-events:none;display:none;' +
      'background:rgba(99,102,241,.12);border:1.5px solid rgba(99,102,241,.9);border-radius:2px}' +
    '.insp-label{position:fixed;z-index:99999;display:none;max-width:92vw;white-space:nowrap;' +
      'font:16px/1.5 ui-monospace,monospace;background:#1e1b4b;color:#e0e7ff;' +
      'padding:4px 8px;border-radius:5px;box-shadow:0 2px 8px rgba(0,0,0,.35)}' +
    '.insp-crumb{cursor:pointer;opacity:.65}' +
    '.insp-crumb:hover{opacity:1;text-decoration:underline}' +
    '.insp-crumb.sel{opacity:1;font-weight:600;color:#fff}' +
    // frozen: the selected step takes the box's indigo, so crumb and box
    // read as one (a shadow, not padding — the crumbs must not shift)
    '.insp-label.frozen .insp-crumb.sel{background:#6366f1;border-radius:3px;box-shadow:0 0 0 2px #6366f1}' +
    '.insp-cname{opacity:.8;margin-right:2px}' +
    '.insp-glyph{margin-left:4px;font-weight:600}' +
    '.insp-sep{opacity:.4;margin:0 4px}' +
    '.insp-loc{opacity:.55;margin-left:10px}' +
    '.insp-badge{position:fixed;left:10px;bottom:10px;z-index:99999;cursor:pointer;user-select:none;' +
      'font:14px/1 ui-monospace,monospace;background:#312e81;color:#c7d2fe;' +
      'padding:6px 9px;border-radius:6px;opacity:.6}' +
    '.insp-badge:hover{opacity:.9}' +
    '.insp-badge.on{background:#6366f1;color:#fff;opacity:1}' +
    '.insp-toast{position:fixed;left:10px;bottom:44px;z-index:99999;pointer-events:none;opacity:0;' +
      'font:14px/1.4 ui-monospace,monospace;background:#065f46;color:#fff;' +
      'padding:4px 8px;border-radius:4px;transition:opacity .15s}' +
    '.insp-toast.err{background:#7f1d1d}' +
    '.insp-toast.show{opacity:1}' +
    'html.insp-on,html.insp-on *{cursor:crosshair!important}' +
    'html.insp-on .insp-badge,html.insp-on .insp-crumb{cursor:pointer!important}' +
    // reverse direction: a soft frame per component instance, a strong
    // pulsing box on the exact element the editor cursor is on
    '.insp-frame{position:fixed;z-index:99996;pointer-events:none;' +
      'border:2px solid rgba(99,102,241,.45);border-radius:3px}' +
    '.insp-hl{position:fixed;z-index:99998;pointer-events:none;' +
      'background:rgba(16,185,129,.15);border:2px solid rgba(16,185,129,.95);border-radius:2px;' +
      'animation:insp-pulse .5s ease-out}' +
    '@keyframes insp-pulse{0%{box-shadow:0 0 0 0 rgba(16,185,129,.5)}100%{box-shadow:0 0 0 9px rgba(16,185,129,0)}}';
  document.head.appendChild(style);

  function mk(cls) {
    var n = document.createElement('div');
    n.className = cls;
    document.body.appendChild(n);
    return n;
  }
  var box = mk('insp-box'), label = mk('insp-label'),
      badge = mk('insp-badge'), toast = mk('insp-toast');
  function ours(t) { return t === badge || t === box || label.contains(t); }

  var toastTimer = null;
  function flash(msg, ok) {
    toast.textContent = msg;
    toast.classList.toggle('err', !ok);
    toast.classList.add('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { toast.classList.remove('show'); }, 1600);
  }

  // --- dev socket (its own connection to /dev/ws) ---
  var ws = null;
  function connect() {
    ws = new WebSocket((location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/dev/ws');
    ws.onmessage = function (e) {
      var m = JSON.parse(e.data);
      if (m.type === 'open-result') {
        if (!m.ok) flash('open failed: ' + (m.error || ''), false);
      } else if (m.type === 'highlight') {
        handleHighlight(m);
      }
    };
    ws.onclose = function () { ws = null; setTimeout(connect, 1000); };
  }
  connect();

  function sendOpen(src) {
    // src is "file:line:col" — peel the numbers off the right
    var p = src.split(':'), col = +p.pop(), line = +p.pop();
    if (ws && ws.readyState === 1) {
      ws.send(JSON.stringify({ type: 'open', src: p.join(':'), line: line, col: col }));
      flash('→ ' + src, true);
    } else {
      flash('dev socket not connected', false);
    }
  }

  // --- hover: box + breadcrumb of tagged ancestors ---
  // A component instance carries TWO source locations on one node — its
  // definition (data-src) and its call site (data-callsite) — so it expands
  // into two steps; the breadcrumb folds them into name + λ/() glyphs.
  function chain(node) {
    var out = [], cur = node;
    while (cur) {
      if (cur.getAttribute && cur.getAttribute('data-src')) {
        var name = cur.getAttribute('data-name') || '?';
        var call = cur.getAttribute('data-callsite');
        out.push({ node: cur, src: cur.getAttribute('data-src'),
                   name: name, kind: call ? 'defn' : 'element' });
        if (call) out.push({ node: cur, src: call, name: name, kind: 'callsite' });
      }
      cur = cur.parentElement;
    }
    return out; // innermost first
  }
  function shortName(name) {
    var slash = name.indexOf('/');
    return slash >= 0 ? name.slice(slash + 1) : name;
  }

  function drawBox(node) {
    var r = node.getBoundingClientRect();
    box.style.left = r.left + 'px';
    box.style.top = r.top + 'px';
    box.style.width = r.width + 'px';
    box.style.height = r.height + 'px';
    box.style.display = 'block';
  }

  function crumb(text, step, glyph) {
    var c = document.createElement('span');
    c.className = 'insp-crumb' + (glyph ? ' insp-glyph' : '');
    c.textContent = text;
    c.title = (step.kind === 'callsite' ? 'call site → '
             : step.kind === 'defn' ? 'definition → ' : '') + step.src;
    c.addEventListener('click', function (ev) {
      ev.preventDefault(); ev.stopPropagation();
      sendOpen(step.src);
    });
    // hovering a crumb previews its node; leaving it puts the box back
    c.addEventListener('mouseenter', function () { drawBox(step.node); });
    c.addEventListener('mouseleave', function () {
      if (current[sel]) drawBox(current[sel].node);
    });
    crumbs.push({ el: c, step: step });
    return c;
  }

  // The label is rebuilt only for a new chain; a walk just moves the mark,
  // so the crumb under a resting pointer is never swapped out beneath it.
  // It stays anchored on the innermost node, holding still as the box grows.
  var labelSteps = null, crumbs = [], loc = null;
  function renderLabel(steps, selStep) {
    if (steps !== labelSteps) buildLabel(steps);
    crumbs.forEach(function (c) { c.el.classList.toggle('sel', c.step === selStep); });
    loc.textContent = selStep.src;

    var r = steps[0].node.getBoundingClientRect();
    label.style.display = 'block';
    var top = r.top - label.offsetHeight - 6;
    if (top < 4) top = r.bottom + 6;
    // a frozen path can be scrolled away: keep its crumbs on screen, clear
    // of the badge
    if (frozen) top = Math.max(4, Math.min(top, badge.getBoundingClientRect().top - label.offsetHeight - 4));
    label.style.left = Math.max(4, Math.min(r.left, innerWidth - label.offsetWidth - 4)) + 'px';
    label.style.top = top + 'px';
  }
  function buildLabel(steps) {
    label.innerHTML = '';
    labelSteps = steps;
    crumbs = [];
    var disp = steps.slice().reverse(); // outermost → innermost
    function sep() {
      var s = document.createElement('span');
      s.className = 'insp-sep';
      s.textContent = '▸';
      label.appendChild(s);
    }
    for (var i = 0; i < disp.length; i++) {
      var step = disp[i], next = disp[i + 1];
      if (i) sep();
      if (next && next.node === step.node) {
        // one component instance = two steps sharing a node: fold them into
        // the name plus two clickable glyphs — () the call site, λ the defn
        var callStep = step.kind === 'callsite' ? step : next;
        var defnStep = step.kind === 'defn' ? step : next;
        var nm = document.createElement('span');
        nm.className = 'insp-cname';
        nm.textContent = shortName(step.name);
        label.appendChild(nm);
        label.appendChild(crumb('()', callStep, true));
        label.appendChild(crumb('λ', defnStep, true));
        i++; // consumed `next`
      } else {
        label.appendChild(crumb(shortName(step.name), step));
      }
    }
    loc = document.createElement('span');
    loc.className = 'insp-loc';
    label.appendChild(loc);
  }

  function hide() { box.style.display = 'none'; label.style.display = 'none'; }

  var current = []; // the hovered chain, innermost first
  var sel = 0;      // index of the selected step; only a walk moves it off 0
  var ptrX = -1, ptrY = -1; // where the pointer last was
  function render() {
    drawBox(current[sel].node);
    renderLabel(current, current[sel]);
  }
  function hover(t) {
    var leaf = t.closest ? t.closest('[data-src]') : null;
    current = leaf ? chain(leaf) : [];
    sel = 0;
    if (!current.length) { hide(); return; }
    render();
  }

  // --- walk: Alt freezes the path, the wheel walks it ---
  // Pointer moves no longer touch it, so the crumbs can be reached across
  // the gap. One wheel gesture is one step: its first event steps, the rest
  // is swallowed until WALK_GAP ms of quiet — a mouse notch, a trackpad
  // flick of any length and a single huge delta all move exactly one step.
  var WALK_GAP = 150;
  var frozen = false, walkAt = -Infinity, altUpAt = -Infinity;
  function isAlt(e) { // not AltGraph; X11 calls Shift+Alt "Meta"
    return e.key === 'Alt' || (e.key !== 'AltGraph' && (e.code === 'AltLeft' || e.code === 'AltRight'));
  }
  function freeze() {
    if (frozen || !enabled) return;
    if (label.style.display !== 'block' && ptrX >= 0) { // e.g. hidden by a scroll
      var t = document.elementFromPoint(ptrX, ptrY);
      if (t && !ours(t)) hover(t);
    }
    if (!current.length || label.style.display !== 'block') return;
    frozen = true;
    walkAt = -Infinity;
    label.classList.add('frozen');
    render();
  }
  function unfreeze(rehover) {
    if (!frozen) return;
    frozen = false;
    sel = 0;
    label.classList.remove('frozen');
    if (!rehover || !enabled) return;
    // live again: take up whatever is under the pointer now (on the label
    // itself, keep its path, back on the innermost step)
    var t = document.elementFromPoint(ptrX, ptrY);
    if (t && !ours(t)) hover(t);
    else if (current.length) render();
  }
  function walk(e) {
    // (most systems turn a Shift+wheel sideways: it is still the wheel)
    var dy = e.deltaY || (e.shiftKey ? e.deltaX : 0);
    if (!dy) return;       // sideways: swallowed, no step
    var fresh = e.timeStamp - walkAt > WALK_GAP;
    walkAt = e.timeStamp;
    if (!fresh) return;    // the rest of this gesture
    var to = Math.max(0, Math.min(current.length - 1, sel + (dy < 0 ? 1 : -1))); // up: outward
    if (to !== sel) { sel = to; render(); }
  }
  function onWheel(e) {
    if (frozen && !e.altKey) unfreeze(true); // Alt went up out of our sight
    if (!e.altKey) return;
    // Alt+wheel belongs to the inspector while it is on: no scroll, no zoom,
    // no history swipe
    e.preventDefault();
    e.stopPropagation();
    // Firefox can deliver a wheel queued before Alt went up after its keyup:
    // it must not freeze again, with no keyup left to come
    if (e.timeStamp <= altUpAt) return;
    freeze(); // Alt was down before there was a path to hold
    if (frozen) walk(e);
  }

  function onMove(e) {
    ptrX = e.clientX; ptrY = e.clientY;
    if (!enabled) return;
    if (frozen && !e.altKey) unfreeze(false); // Alt went up out of our sight
    if (frozen || ours(e.target)) return;
    hover(e.target);
  }
  function onClick(e) {
    if (!enabled || ours(e.target)) return;
    e.preventDefault();
    e.stopPropagation(); // inspect mode swallows the app's click
    if (current.length) sendOpen(current[sel].src);
  }

  // --- reverse: editor cursor → on-screen element ---
  var hlBoxes = [], hlNodes = []; // hlNodes is the truth; boxes are redrawn
  function drawHl() {
    hlBoxes.forEach(function (b) { b.remove(); });
    hlBoxes = [];
    hlNodes.forEach(function (x) {
      var r = x.node.getBoundingClientRect();
      if (!r.width && !r.height) return;
      var b = mk(x.cls);
      b.style.left = r.left + 'px';
      b.style.top = r.top + 'px';
      b.style.width = r.width + 'px';
      b.style.height = r.height + 'px';
      hlBoxes.push(b);
    });
  }
  function clearHl() { hlNodes = []; drawHl(); }
  function byAttr(attr, val) {
    return val ? [].slice.call(document.querySelectorAll('[' + attr + '="' + val + '"]')) : [];
  }
  function handleHighlight(m) {
    if (!enabled) return;         // one toggle governs both directions
    if (!m.component) { clearHl(); return; } // cursor left the views
    var comps = byAttr('data-name', m.component);
    // DOM-as-truth precedence: this call site → the element literal →
    // every instance of the component
    var els = byAttr('data-callsite', m.callsite);
    if (!els.length) els = byAttr('data-src', m.element);
    if (!els.length) els = comps;
    hlNodes = comps.map(function (n) { return { node: n, cls: 'insp-frame' }; })
      .concat(els.map(function (n) { return { node: n, cls: 'insp-hl' }; }));
    drawHl();
    var r = els[0] && els[0].getBoundingClientRect();
    if (r && (r.bottom < 0 || r.top > innerHeight)) {
      els[0].scrollIntoView({ block: 'center', behavior: 'smooth' });
    }
  }

  var WHEEL_OPTS = { capture: true, passive: false };
  function apply() {
    badge.classList.toggle('on', enabled);
    badge.textContent = enabled ? '⌖ inspecting' : '⌖ inspect';
    document.documentElement.classList.toggle('insp-on', enabled);
    if (!enabled) { unfreeze(false); hide(); current = []; sel = 0; clearHl(); }
    // a non-passive wheel listener costs the page its threaded scrolling:
    // hold it only while inspecting
    window[enabled ? 'addEventListener' : 'removeEventListener']('wheel', onWheel, WHEEL_OPTS);
  }
  function setEnabled(v) {
    enabled = v;
    try { localStorage.setItem('inspector', v ? '1' : '0'); } catch (e) {}
    apply();
  }

  badge.addEventListener('click', function (e) { e.stopPropagation(); setEnabled(!enabled); });
  document.addEventListener('mousemove', onMove, true);
  document.addEventListener('click', onClick, true);
  document.addEventListener('keydown', function (e) {
    if (e.altKey && e.shiftKey && (e.key === 'I' || e.key === 'i' || e.code === 'KeyI')) { e.preventDefault(); setEnabled(!enabled); }
    else if (e.key === 'Escape' && enabled) setEnabled(false);
    // Alt itself: freeze, and keep the browser's own Alt (menu bar,
    // menu focus) out of it while inspecting
    // (a held key repeats its keydown on some systems: only the press counts)
    else if (isAlt(e) && enabled) { e.preventDefault(); if (!e.repeat) freeze(); }
  });
  document.addEventListener('keyup', function (e) {
    if (isAlt(e) && enabled) { e.preventDefault(); altUpAt = e.timeStamp; unfreeze(true); }
  });
  // Alt-Tab (or any leaving) must not strand a frozen path
  window.addEventListener('blur', function () { unfreeze(true); });
  document.addEventListener('visibilitychange', function () { unfreeze(true); });
  function reflow() { if (frozen) render(); else hide(); drawHl(); }
  window.addEventListener('scroll', reflow, true);
  window.addEventListener('resize', reflow);

  apply();
})();
