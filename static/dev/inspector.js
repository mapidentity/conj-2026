// Dev-only source inspector overlay.
// Toggle with the badge (bottom-left) or Alt+Shift+I; Esc turns it off.
// Hover boxes the nearest source-tagged element and shows the breadcrumb of
// its tagged ancestors; click opens the source in the editor via the dev
// socket. Crumbs are clickable too — open any ancestor directly.
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
    '.insp-crumb.leaf{opacity:1;font-weight:600;color:#fff}' +
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
    'html.insp-on .insp-badge,html.insp-on .insp-crumb{cursor:pointer!important}';
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
  function chain(node) {
    var out = [], cur = node;
    while (cur) {
      if (cur.getAttribute && cur.getAttribute('data-src')) {
        out.push({ node: cur,
                   src: cur.getAttribute('data-src'),
                   name: cur.getAttribute('data-name') || '?' });
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

  function crumb(text, step, leaf) {
    var c = document.createElement('span');
    c.className = 'insp-crumb' + (leaf ? ' leaf' : '');
    c.textContent = text;
    c.title = step.src;
    c.addEventListener('click', function (ev) {
      ev.preventDefault(); ev.stopPropagation();
      sendOpen(step.src);
    });
    c.addEventListener('mouseenter', function () { drawBox(step.node); });
    return c;
  }

  function renderLabel(steps) {
    label.innerHTML = '';
    var disp = steps.slice().reverse(); // outermost → innermost
    disp.forEach(function (step, i) {
      if (i) {
        var s = document.createElement('span');
        s.className = 'insp-sep';
        s.textContent = '▸';
        label.appendChild(s);
      }
      label.appendChild(crumb(shortName(step.name), step, i === disp.length - 1));
    });
    var loc = document.createElement('span');
    loc.className = 'insp-loc';
    loc.textContent = steps[0].src;
    label.appendChild(loc);

    var r = steps[0].node.getBoundingClientRect();
    label.style.display = 'block';
    var top = r.top - label.offsetHeight - 6;
    label.style.left = Math.max(4, Math.min(r.left, innerWidth - label.offsetWidth - 4)) + 'px';
    label.style.top = (top < 4 ? r.bottom + 6 : top) + 'px';
  }

  function hide() { box.style.display = 'none'; label.style.display = 'none'; }

  var current = []; // the hovered chain, innermost first
  function onMove(e) {
    if (!enabled || ours(e.target)) return;
    var leaf = e.target.closest ? e.target.closest('[data-src]') : null;
    current = leaf ? chain(leaf) : [];
    if (!current.length) { hide(); return; }
    drawBox(current[0].node);
    renderLabel(current);
  }
  function onClick(e) {
    if (!enabled || ours(e.target)) return;
    e.preventDefault();
    e.stopPropagation(); // inspect mode swallows the app's click
    if (current.length) sendOpen(current[0].src);
  }

  function apply() {
    badge.classList.toggle('on', enabled);
    badge.textContent = enabled ? '⌖ inspecting' : '⌖ inspect';
    document.documentElement.classList.toggle('insp-on', enabled);
    if (!enabled) { hide(); current = []; }
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
  });
  window.addEventListener('scroll', hide, true);
  window.addEventListener('resize', hide);

  apply();
})();
