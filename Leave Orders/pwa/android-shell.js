/*  android-shell.js — gives the phone web app the Karur SDO Android app's chrome.
 *  ────────────────────────────────────────────────────────────────────────────
 *  Loaded on every page. Below 900px it:
 *    • marks <html class="ksd"> so android-shell.css takes effect
 *    • injects the M3 top app bar and the five-tab bottom navigation from KsdApp.kt
 *    • owns theme mode + palette (the Android ThemeController, in localStorage)
 *    • adds Material ripples, registers the service worker, and manages Web Push
 *
 *  Pages talk to it through window.KSD. Desktop is left completely alone.
 */

(function () {
  'use strict';

  var PHONE = 900;
  var LS_MODE = 'ksd.theme.mode';       // system | light | dark
  var LS_PALETTE = 'ksd.theme.palette'; // royal_navy | emerald | amethyst | sunset | ocean
  var LS_PUSH = 'ksd.push.enabled';
  var LS_INSTALL_DISMISSED = 'ksd.install.dismissed';

  // The Android app's five appearance palettes, each mapped onto the design
  // system's accent ramp (Apple system colours) rather than its own hexes.
  var PALETTES = [
    { id: 'royal_navy', label: 'Indigo', swatch: '#6b6ef9' },
    { id: 'emerald', label: 'Emerald', swatch: '#30d158' },
    { id: 'amethyst', label: 'Amethyst', swatch: '#bf5af2' },
    { id: 'sunset', label: 'Sunset', swatch: '#ff9f0a' },
    { id: 'ocean', label: 'Ocean', swatch: '#40c8e0' },
  ];

  // 24px Material glyphs, inlined so the chrome still draws with no network.
  var ICONS = {
    home: '<path d="M12 5.69l5 4.5V18h-2v-6H9v6H7v-7.81l5-4.5M12 3L2 12h3v8h6v-6h2v6h6v-8h3L12 3z"/>',
    chat:
      '<path d="M20 2H4c-1.1 0-2 .9-2 2v18l4-4h14c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zm0 14H5.17L4 17.17V4h16v12z"/>',
    offices:
      '<path d="M17 7V3H7v2H3v16h8v-4h2v4h8V7h-4zM7 19H5v-2h2v2zm0-4H5v-2h2v2zm0-4H5V9h2v2zm4 4H9v-2h2v2zm0-4H9V9h2v2zm0-4H9V5h2v2zm4 8h-2v-2h2v2zm0-4h-2V9h2v2zm0-4h-2V5h2v2zm4 12h-2v-2h2v2zm0-4h-2v-2h2v2zm0-4h-2V9h2v2z"/>',
    route:
      '<g fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">' +
      '<circle cx="6" cy="18.5" r="2.5"/><circle cx="18" cy="5.5" r="2.5"/>' +
      '<path d="M8.5 18.5h5a3.5 3.5 0 0 0 0-7h-3a3.5 3.5 0 0 1 0-7h4"/></g>',
    profile:
      '<path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 3c1.66 0 3 1.34 3 3s-1.34 3-3 3-3-1.34-3-3 1.34-3 3-3zm0 14.2c-2.5 0-4.71-1.28-6-3.22.03-1.99 4-3.08 6-3.08 1.99 0 5.97 1.09 6 3.08-1.29 1.94-3.5 3.22-6 3.22z"/>',
    back: '<path d="M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z"/>',
  };

  // The five bottom destinations, in the Android app's order (KsdApp.kt).
  var TABS = [
    { href: '/', label: 'Home', icon: 'home' },
    { href: '/messages', label: 'Message', icon: 'chat' },
    { href: '/office-management', label: 'Offices', icon: 'offices' },
    { href: '/mail-overseers', label: 'MO', icon: 'route' },
    { href: '/profile', label: 'Profile', icon: 'profile' },
  ];

  // App-bar titles for the pages that have no tab of their own.
  var TITLES = {
    '/': 'Karur SDO',
    '/messages': 'Message',
    '/office-management': 'Offices',
    '/mail-overseers': 'Mail Overseer',
    '/profile': 'Profile',
    '/employees': 'Staff Directory',
    '/phone-directory': 'Phone Directory',
    '/arrangements': 'Arrangements',
    '/birthdays': 'Birthdays',
    '/retirements': 'Retirements',
    '/performance': 'Delivery Performance',
    '/sanctioned-posts': 'Sanctioned Posts',
    '/cent-percent-verification': 'Cent % Verification',
    '/verification': 'Verification',
    '/high-value-memos': 'High Value Memos',
    '/leave-orders': 'Leave Orders',
    '/ir-library': 'IR Library',
    '/ir-builder': 'IR Builder',
    '/address-labels': 'Address Labels',
    '/test-letters': 'Test Letters',
    '/format-corrector': 'Format Corrector',
    '/update-mobile': 'Update Mobile',
    '/user-management': 'User Management',
    '/pli': 'PLI',
  };

  function svg(name) {
    return '<svg viewBox="0 0 24 24" aria-hidden="true">' + ICONS[name] + '</svg>';
  }

  function path() {
    var p = location.pathname.replace(/\/+$/, '');
    return p === '' ? '/' : p;
  }

  function isPhone() {
    return window.innerWidth <= PHONE;
  }

  function standalone() {
    return (
      window.matchMedia('(display-mode: standalone)').matches ||
      window.navigator.standalone === true
    );
  }

  function isIOS() {
    return (
      /iPad|iPhone|iPod/.test(navigator.userAgent) ||
      (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1)
    );
  }

  // ── Theme (the Android ThemeController, over the Liquid Glass runtime) ──
  //
  // liquid-glass.js already owns the portal's light/dark state: it reads
  // localStorage['theme'] before first paint and publishes data-theme plus the
  // html.light / html.dark classes that both page families read. The shell adds
  // the Android app's two extra ideas — a "follow the system" mode, and the five
  // accent palettes — and otherwise writes *through* that runtime rather than
  // keeping a competing source of truth.
  var LG_THEME_KEY = 'theme';

  var theme = {
    mode: localStorage.getItem(LS_MODE) || 'system',
    palette: localStorage.getItem(LS_PALETTE) || 'royal_navy',

    effectiveDark: function () {
      if (this.mode === 'dark') return true;
      if (this.mode === 'light') return false;
      // In system mode whatever liquid-glass.js resolved is the answer, so a
      // theme toggle on the page stays agreed with.
      var published = document.documentElement.getAttribute('data-theme');
      if (published) return published === 'dark';
      return window.matchMedia('(prefers-color-scheme: dark)').matches;
    },

    apply: function () {
      var root = document.documentElement;
      root.setAttribute('data-ksd-palette', this.palette);

      // Only an explicit choice overrides the runtime; in system mode the shell
      // reads the theme and never writes it, so the page's own toggle still works.
      if (this.mode === 'light' || this.mode === 'dark') {
        var dark = this.mode === 'dark';
        try { localStorage.setItem(LG_THEME_KEY, dark ? 'dark' : 'light'); } catch (e) {}
        root.classList.toggle('dark', dark);
        root.classList.toggle('light', !dark);
        root.setAttribute('data-theme', dark ? 'dark' : 'light');
      }

      // iOS tints the status bar from theme-color; match the canvas the glass
      // floats over so the bar and the app read as one surface.
      var canvas = getComputedStyle(root).getPropertyValue('--lg-canvas').trim();
      var meta = document.querySelector('meta[name="theme-color"]');
      if (!meta) {
        meta = document.createElement('meta');
        meta.name = 'theme-color';
        document.head.appendChild(meta);
      }
      if (isPhone() && canvas) meta.content = canvas;

      document.dispatchEvent(new CustomEvent('ksd:themechange'));
    },

    setMode: function (m) {
      this.mode = m;
      localStorage.setItem(LS_MODE, m);
      this.apply();
    },

    setPalette: function (p) {
      this.palette = p;
      localStorage.setItem(LS_PALETTE, p);
      this.apply();
    },
  };

  // ── Chrome ─────────────────────────────────────────────────────────────
  function titleFor(p) {
    if (document.body && document.body.dataset.ksdTitle) return document.body.dataset.ksdTitle;
    if (TITLES[p]) return TITLES[p];
    var t = (document.title || 'Karur SDO').split('—')[0].split('|')[0].trim();
    return t || 'Karur SDO';
  }

  function buildAppBar() {
    var p = path();
    var isTab = TABS.some(function (t) {
      return t.href === p;
    });
    // A <div>, not <header>/<nav>: the host pages style those element names for
    // their own mastheads, and those rules would repaint the shell's chrome.
    var bar = document.createElement('div');
    bar.setAttribute('role', 'banner');
    bar.className = 'ksd-appbar';
    bar.innerHTML =
      '<div class="ksd-appbar-row">' +
      (isTab
        ? ''
        : '<button class="ksd-iconbtn ksd-ripple" id="ksdBack" aria-label="Back">' + svg('back') + '</button>') +
      '<div class="ksd-appbar-title">' +
      titleFor(p).replace(/[<>&]/g, '') +
      '</div>' +
      '<span id="ksdAppbarActions"></span>' +
      '</div>';
    document.body.appendChild(bar);

    var back = document.getElementById('ksdBack');
    if (back) {
      back.addEventListener('click', function () {
        // Within the app, step back; arriving cold (a push tap, a shared link)
        // there is nothing to step back to, so go to the home tab.
        if (history.length > 1 && document.referrer.indexOf(location.origin) === 0) history.back();
        else location.href = '/';
      });
    }
  }

  function buildNav() {
    var p = path();
    var nav = document.createElement('div');
    nav.className = 'ksd-nav';
    nav.setAttribute('role', 'navigation');
    nav.setAttribute('aria-label', 'Main');
    nav.innerHTML = TABS.map(function (t) {
      var current = t.href === p || (t.href !== '/' && p.indexOf(t.href) === 0);
      return (
        '<a href="' + t.href + '" class="ksd-ripple"' + (current ? ' aria-current="page"' : '') + '>' +
        '<span class="ksd-nav-icon">' + svg(t.icon) + '</span>' +
        '<span>' + t.label + '</span>' +
        (t.href === '/messages' ? '<span class="ksd-nav-dot" id="ksdUnread" hidden></span>' : '') +
        '</a>'
      );
    }).join('');
    document.body.appendChild(nav);
  }

  function ripples() {
    document.addEventListener(
      'pointerdown',
      function (e) {
        var el = e.target.closest ? e.target.closest('.ksd-ripple') : null;
        if (!el) return;
        var rect = el.getBoundingClientRect();
        var size = Math.max(rect.width, rect.height);
        var ink = document.createElement('span');
        ink.className = 'ksd-ripple-ink';
        ink.style.width = ink.style.height = size + 'px';
        ink.style.left = e.clientX - rect.left - size / 2 + 'px';
        ink.style.top = e.clientY - rect.top - size / 2 + 'px';
        el.appendChild(ink);
        setTimeout(function () {
          ink.remove();
        }, 520);
      },
      { passive: true }
    );
  }

  function snack(text, ms) {
    var old = document.querySelector('.ksd-snack');
    if (old) old.remove();
    var el = document.createElement('div');
    el.className = 'ksd-snack';
    el.setAttribute('role', 'status');
    el.textContent = text;
    document.body.appendChild(el);
    setTimeout(function () {
      el.remove();
    }, ms || 3600);
  }

  // ── Service worker ─────────────────────────────────────────────────────
  function registerSW() {
    if (!('serviceWorker' in navigator)) return Promise.resolve(null);
    return navigator.serviceWorker
      .register('/sw.js', { scope: '/' })
      .then(function (reg) {
        reg.addEventListener('updatefound', function () {
          var sw = reg.installing;
          if (!sw) return;
          sw.addEventListener('statechange', function () {
            // Only prompt when this is an update, not the very first install.
            if (sw.state === 'installed' && navigator.serviceWorker.controller) {
              snack('A new version is ready — reopen the app to update.', 5000);
            }
          });
        });
        return reg;
      })
      .catch(function () {
        return null;
      });
  }

  // ── Web Push ───────────────────────────────────────────────────────────
  // iOS only exposes Notification/PushManager to a PWA that has been added to
  // the Home Screen (16.4+), so the UI has to explain that rather than fail.
  var push = {
    supported: function () {
      return 'serviceWorker' in navigator && 'PushManager' in window && 'Notification' in window;
    },

    blockedByIOS: function () {
      return isIOS() && !standalone();
    },

    state: function () {
      if (this.blockedByIOS()) return 'needs-install';
      if (!this.supported()) return 'unsupported';
      if (Notification.permission === 'denied') return 'denied';
      if (Notification.permission === 'granted' && localStorage.getItem(LS_PUSH) === '1') return 'on';
      return 'off';
    },

    /** Ask permission, subscribe, and register the subscription with the server. */
    enable: function () {
      var self = this;
      if (this.blockedByIOS()) {
        return Promise.reject(new Error('Add Karur SDO to your Home Screen first.'));
      }
      if (!this.supported()) return Promise.reject(new Error('This browser cannot show notifications.'));

      return Notification.requestPermission()
        .then(function (perm) {
          if (perm !== 'granted') throw new Error('Notifications were not allowed.');
          return fetch('/api/push?action=public-key').then(function (r) {
            return r.json();
          });
        })
        .then(function (cfg) {
          if (!cfg || !cfg.publicKey) throw new Error('Push is not configured on the server yet.');
          return navigator.serviceWorker.ready.then(function (reg) {
            return reg.pushManager.getSubscription().then(function (existing) {
              if (existing) return existing;
              return reg.pushManager.subscribe({
                userVisibleOnly: true,
                applicationServerKey: cfg.publicKey,
              });
            });
          });
        })
        .then(function (sub) {
          return fetch('/api/push?action=subscribe', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ subscription: sub.toJSON(), username: self.username() }),
          }).then(function (r) {
            if (!r.ok) throw new Error('Could not register for notifications.');
            localStorage.setItem(LS_PUSH, '1');
            return true;
          });
        });
    },

    disable: function () {
      localStorage.removeItem(LS_PUSH);
      if (!('serviceWorker' in navigator)) return Promise.resolve();
      return navigator.serviceWorker.ready
        .then(function (reg) {
          return reg.pushManager.getSubscription();
        })
        .then(function (sub) {
          if (!sub) return;
          var endpoint = sub.endpoint;
          return sub.unsubscribe().then(function () {
            return fetch('/api/push?action=unsubscribe', {
              method: 'POST',
              headers: { 'Content-Type': 'application/json' },
              body: JSON.stringify({ endpoint: endpoint }),
            }).catch(function () {});
          });
        });
    },

    /** Who is signed in, so a push can skip the person who caused it. */
    username: function () {
      try {
        if (window.AuthSession) {
          var u = window.AuthSession.user();
          if (u) return u.username;
        }
        var raw = localStorage.getItem('sdo_session');
        return raw ? JSON.parse(raw).username || null : null;
      } catch (e) {
        return null;
      }
    },

    /** Ask the server to notify everyone else about a row that was just written. */
    notify: function (kind, id) {
      return fetch('/api/push?action=notify', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ kind: kind, id: id, from: this.username() }),
      }).catch(function () {});
    },
  };

  // ── "Add to Home Screen" hint ──────────────────────────────────────────
  function installHint() {
    if (standalone() || !isIOS() || localStorage.getItem(LS_INSTALL_DISMISSED)) return;
    var el = document.createElement('div');
    el.className = 'ksd-banner';
    el.innerHTML =
      '<b>Install Karur SDO</b>' +
      'Tap the Share button in Safari, then <b style="display:inline">Add to Home Screen</b>. ' +
      'The app then opens full screen and can send you notifications.' +
      '<div class="acts"><button class="ghost" id="ksdInstallNo">Not now</button></div>';
    var anchor = document.querySelector('.ksd-home') || document.body;
    anchor.insertBefore(el, anchor.firstChild);
    document.getElementById('ksdInstallNo').addEventListener('click', function () {
      localStorage.setItem(LS_INSTALL_DISMISSED, '1');
      el.remove();
    });
  }

  // ── Boot ───────────────────────────────────────────────────────────────
  function ensureViewportFit() {
    var vp = document.querySelector('meta[name="viewport"]');
    if (!vp) {
      vp = document.createElement('meta');
      vp.name = 'viewport';
      document.head.appendChild(vp);
    }
    if (vp.content.indexOf('viewport-fit') === -1) {
      vp.content = 'width=device-width,initial-scale=1,viewport-fit=cover';
    }
  }

  function boot() {
    ensureViewportFit();
    theme.apply();
    registerSW();

    if (!isPhone()) return;

    document.documentElement.classList.add('ksd');

    // Pages that are not part of the signed-in app — the sign-in screen, the
    // offline card — get the theme but no app bar or tabs to navigate with.
    if (document.body.dataset.ksdChrome === 'none') {
      document.documentElement.classList.add('ksd-bare');
      return;
    }

    buildAppBar();
    buildNav();
    ripples();
    installHint();
  }

  window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', function () {
    if (theme.mode === 'system') theme.apply();
  });

  window.KSD = {
    theme: theme,
    push: push,
    palettes: PALETTES,
    snack: snack,
    icon: svg,
    isPhone: isPhone,
    standalone: standalone,
    isIOS: isIOS,
    /** Show a count on the Message tab. */
    setUnread: function (n) {
      var dot = document.getElementById('ksdUnread');
      if (!dot) return;
      dot.hidden = !n;
      dot.textContent = n > 99 ? '99+' : String(n);
    },
  };

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
  else boot();
})();
