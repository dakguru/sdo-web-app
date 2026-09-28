/* ============================================================================
   LIQUID GLASS — runtime
   Companion to liquid-glass.css. Everything here is additive: it adds classes,
   CSS custom properties and one backdrop element. No page markup is rewritten,
   so every module keeps working exactly as before if this file fails to load.

   Runs in two phases:
     - head, synchronously: resolve the theme before first paint (no flash)
     - DOMContentLoaded:    backdrop, specular pointer, ripples, reveal
   ========================================================================== */
(function () {
  'use strict';

  var root = document.documentElement;
  var THEME_KEY = 'theme';
  var reduced = false;
  try {
    reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  } catch (e) { /* matchMedia unavailable */ }

  /* ---------------------------------------------------------------- theme */
  /* The portal's two module families disagree on convention: the glass pages
     key off `html.light`, the card pages off `html.dark`. Both read the same
     localStorage key, so keep whichever class each page expects present and
     publish one `data-theme` attribute for the design system to target.      */

  function storedTheme() {
    try { return localStorage.getItem(THEME_KEY); } catch (e) { return null; }
  }

  function systemTheme() {
    try {
      return window.matchMedia('(prefers-color-scheme: light)').matches ? 'light' : 'dark';
    } catch (e) { return 'dark'; }
  }

  function applyTheme(theme) {
    var dark = theme !== 'light';
    root.classList.toggle('dark', dark);
    root.classList.toggle('light', !dark);
    root.setAttribute('data-theme', dark ? 'dark' : 'light');
  }

  var initial = storedTheme();
  if (initial !== 'light' && initial !== 'dark') {
    initial = systemTheme();
    // Persist so both module families agree from the very first navigation.
    try { localStorage.setItem(THEME_KEY, initial); } catch (e) { /* private mode */ }
  }
  applyTheme(initial);

  /* Each module ships its own toggle button that flips exactly one class —
     `light` on the glass pages, `dark` on the card pages — and then writes the
     result to localStorage. Because both classes are present here, a toggle
     leaves the element in a state neither convention can be read from, so
     localStorage is the only reliable source of truth. Re-apply from it once
     the page's own handler has run.                                          */
  function resync() {
    var theme = storedTheme();
    if (theme !== 'light' && theme !== 'dark') return;
    if (root.getAttribute('data-theme') === theme) return;
    applyTheme(theme);
  }

  window.addEventListener('storage', function (e) {
    if (!e || e.key === THEME_KEY) resync();
  });

  /* A theme change repaints a lot of translucent material at once. Suppress
     transitions for one frame so the swap lands instantly instead of
     smearing through every intermediate tint.                               */
  function damp() {
    var style = document.createElement('style');
    style.textContent = '*,*::before,*::after{transition:none !important}';
    (document.head || root).appendChild(style);
    requestAnimationFrame(function () {
      requestAnimationFrame(function () {
        if (style.parentNode) style.parentNode.removeChild(style);
      });
    });
  }

  var TOGGLES = '.theme-toggle, .theme-btn, #themeBtn, #themeToggle, [data-theme-toggle]';

  document.addEventListener('click', function (e) {
    var t = e.target;
    if (!t || !t.closest || !t.closest(TOGGLES)) return;
    damp();
    // Let the module's own handler write localStorage first, then mirror it.
    setTimeout(resync, 0);
    setTimeout(resync, 80);
  }, true);

  /* ------------------------------------------------------------ DOM phase */

  function ready(fn) {
    if (document.readyState === 'loading') {
      document.addEventListener('DOMContentLoaded', fn, { once: true });
    } else {
      fn();
    }
  }

  ready(function () {
    var body = document.body;
    if (!body || body.getAttribute('data-lg-ready') === '1') return;
    body.setAttribute('data-lg-ready', '1');

    buildBackdrop();
    markSurfaces();
    bindSpecular();
    bindRipples();
    bindReveal();
    root.classList.add('lg-boot');
  });

  /* ------------------------------------------------------------- backdrop */

  function buildBackdrop() {
    if (document.querySelector('.lg-backdrop')) return;
    var back = document.createElement('div');
    back.className = 'lg-backdrop';
    back.setAttribute('aria-hidden', 'true');
    for (var i = 1; i <= 4; i++) {
      var blob = document.createElement('span');
      blob.className = 'lg-blob lg-blob-' + i;
      back.appendChild(blob);
    }
    document.body.insertBefore(back, document.body.firstChild);
  }

  /* ------------------------------------------- specular pointer highlight */

  var LIT = '.nav-card, .overview-panel, .card, .panel, .tile, .stat, ' +
            '.glass-card, .hero, .gov-header, .modal, .lg-glass';

  function markSurfaces() {
    // `.lg-lit` only enables the highlight; the gradient stays neutral until
    // the pointer supplies --lg-px / --lg-py, so nothing moves without one.
    var nodes = document.querySelectorAll(LIT);
    for (var i = 0; i < nodes.length && i < 400; i++) {
      nodes[i].classList.add('lg-lit');
    }
  }

  function bindSpecular() {
    if (reduced) return;
    try {
      if (!window.matchMedia('(hover: hover) and (pointer: fine)').matches) return;
    } catch (e) { /* assume a mouse */ }

    var current = null;
    var pending = null;
    var frame = 0;

    function paint() {
      frame = 0;
      if (!pending) return;
      var el = pending.el, rect = pending.rect;
      if (!rect.width || !rect.height) return;
      el.style.setProperty('--lg-px', (((pending.x - rect.left) / rect.width) * 100).toFixed(2) + '%');
      el.style.setProperty('--lg-py', (((pending.y - rect.top) / rect.height) * 100).toFixed(2) + '%');
    }

    function release(el) {
      if (!el) return;
      el.style.removeProperty('--lg-px');
      el.style.removeProperty('--lg-py');
      el.style.willChange = '';
    }

    document.addEventListener('pointermove', function (e) {
      if (e.pointerType && e.pointerType !== 'mouse') return;
      var el = e.target && e.target.closest ? e.target.closest('.lg-lit') : null;

      if (el !== current) {
        release(current);
        current = el;
        if (current) current.style.willChange = 'transform';
      }
      if (!current) { pending = null; return; }

      pending = {
        el: current,
        rect: current.getBoundingClientRect(),
        x: e.clientX,
        y: e.clientY
      };
      if (!frame) frame = requestAnimationFrame(paint);
    }, { passive: true });

    document.addEventListener('pointerleave', function () {
      release(current);
      current = null;
      pending = null;
    }, { passive: true });
  }

  /* --------------------------------------------------------- press ripple */

  function bindRipples() {
    if (reduced) return;
    document.addEventListener('pointerdown', function (e) {
      if (e.button !== undefined && e.button !== 0) return;
      var btn = e.target && e.target.closest ? e.target.closest('.btn, .button') : null;
      if (!btn || btn.disabled || btn.getAttribute('data-lg-no-ripple') === '1') return;

      var rect = btn.getBoundingClientRect();
      if (!rect.width) return;

      var ripple = document.createElement('span');
      var size = Math.max(rect.width, rect.height) * 2.2;
      ripple.className = 'lg-ripple';
      ripple.style.width = size + 'px';
      ripple.style.height = size + 'px';
      ripple.style.left = (e.clientX - rect.left) + 'px';
      ripple.style.top = (e.clientY - rect.top) + 'px';
      btn.appendChild(ripple);
      setTimeout(function () {
        if (ripple.parentNode) ripple.parentNode.removeChild(ripple);
      }, 620);
    }, { passive: true });
  }

  /* -------------------------------------------------------- scroll reveal */

  var REVEAL = '.nav-card, .overview-panel, .section-title, .hero, .postal-hero, ' +
               '.card, .panel, .tile, .stat, .datetime-bar';

  function bindReveal() {
    if (reduced || !window.IntersectionObserver) return;

    var candidates = [];
    var nodes = document.querySelectorAll(REVEAL);
    for (var i = 0; i < nodes.length; i++) {
      var n = nodes[i];
      // Rows inside data views animate badly and cost the most to composite.
      if (n.closest('table, thead, tbody, .modal, .modal-overlay, .toast')) continue;
      candidates.push(n);
      if (candidates.length >= 80) break;
    }
    if (!candidates.length) return;

    var io = new IntersectionObserver(function (entries) {
      // Stagger only within a single batch, so a long page never queues a
      // visible delay on content the reader has already scrolled to.
      var shown = 0;
      entries.forEach(function (entry) {
        if (!entry.isIntersecting) return;
        var el = entry.target;
        el.style.setProperty('--lg-delay', Math.min(shown * 55, 330) + 'ms');
        el.classList.add('lg-in');
        shown++;
        io.unobserve(el);
        el.addEventListener('transitionend', function () {
          el.classList.add('lg-settled');
          el.style.removeProperty('--lg-delay');
        }, { once: true });
      });
    }, { rootMargin: '0px 0px -8% 0px', threshold: 0.04 });

    candidates.forEach(function (el) {
      el.classList.add('lg-reveal');
      io.observe(el);
    });

    // Safety net: anything still hidden after two seconds is shown regardless.
    setTimeout(function () {
      candidates.forEach(function (el) {
        if (!el.classList.contains('lg-in')) el.classList.add('lg-in');
      });
    }, 2000);
  }
})();
