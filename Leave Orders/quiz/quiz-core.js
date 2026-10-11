/*  quiz-core.js — shared client runtime for the Quiz module
 *  ─────────────────────────────────────────────────────────────────────────
 *  National Postal Week 2026 · Department of Posts · Karur Division, Karur
 *
 *  Deliberately dependency-free and small: these pages are opened on ordinary
 *  mobile data, often on modest Android phones, and one of them is an
 *  examination screen that must not stall.
 *
 *  Provides
 *    QuizAPI      — fetch wrapper for /api/quiz-*  (cookies, CSRF header, errors)
 *    QuizUI       — header, footer, theme, toast, modal, skeletons
 *    QuizTime     — IST formatting and server-anchored clock
 *
 *  NOTE ON TIME. Every countdown on these pages is drawn from a server
 *  timestamp, with the local clock used only to interpolate between polls.
 *  Changing the device clock does not move a deadline — the server decides,
 *  and re-states the remaining time on every request.
 */

(function (global) {
  'use strict';

  var AUTH_URL = '/api/quiz-auth';
  var PART_URL = '/api/quiz-participant';
  var ADMIN_URL = '/api/quiz-admin';

  /* ═══════════════════════════════════════════════════════════════
     API
     ═══════════════════════════════════════════════════════════════ */
  function qs(params) {
    if (!params) return '';
    var out = [];
    Object.keys(params).forEach(function (k) {
      var v = params[k];
      if (v === undefined || v === null || v === '') return;
      out.push(encodeURIComponent(k) + '=' + encodeURIComponent(v));
    });
    return out.length ? '?' + out.join('&') : '';
  }

  function call(url, action, params, body, method) {
    var m = method || (body ? 'POST' : 'GET');
    var all = Object.assign({ action: action }, params || {});
    var opts = {
      method: m,
      credentials: 'same-origin',           // the session cookie is HttpOnly
      cache: 'no-store',
      headers: {
        // A cross-site form post cannot set a custom header; the server
        // requires this on every state change, which is the CSRF guard.
        'X-Quiz-Request': '1',
        'Accept': 'application/json',
      },
    };
    if (m !== 'GET' && m !== 'HEAD') {
      opts.headers['Content-Type'] = 'application/json';
      opts.body = JSON.stringify(body || {});
    }

    return fetch(url + qs(all), opts).then(function (res) {
      return res.text().then(function (text) {
        var data = null;
        try { data = text ? JSON.parse(text) : null; } catch (_) { /* non-JSON */ }
        if (!res.ok || !data || data.ok === false) {
          var err = new Error((data && data.error) ||
            (res.status === 0 ? 'No connection.' : 'Request failed (' + res.status + ').'));
          err.status = res.status;
          err.code = data && data.code;
          err.payload = data;
          throw err;
        }
        return data;
      });
    }, function (netErr) {
      var err = new Error('No internet connection. Please check your network and try again.');
      err.status = 0;
      err.offline = true;
      err.cause = netErr;
      throw err;
    });
  }

  var QuizAPI = {
    auth:  function (a, p, b, m) { return call(AUTH_URL, a, p, b, m); },
    part:  function (a, p, b, m) { return call(PART_URL, a, p, b, m); },
    admin: function (a, p, b, m) { return call(ADMIN_URL, a, p, b, m); },

    config:      function () { return call(AUTH_URL, 'config'); },
    me:          function () { return call(AUTH_URL, 'me'); },
    adminMe:     function () { return call(AUTH_URL, 'admin-me'); },
    logout:      function () { return call(AUTH_URL, 'logout', null, {}); },
    adminLogout: function () { return call(AUTH_URL, 'admin-logout', null, {}); },
  };

  /* ═══════════════════════════════════════════════════════════════
     TIME — IST display, server-anchored countdowns
     ═══════════════════════════════════════════════════════════════ */
  var skewMs = 0;             // serverNow - clientNow, refreshed on each reply

  var QuizTime = {
    // Call with any server timestamp to keep the local clock honest.
    anchor: function (serverTimeMs) {
      if (typeof serverTimeMs === 'number' && serverTimeMs > 0) {
        skewMs = serverTimeMs - Date.now();
      }
    },
    now: function () { return Date.now() + skewMs; },

    // All examination times are published in Indian Standard Time.
    ist: function (ms, opts) {
      if (!ms) return '—';
      return new Date(ms).toLocaleString('en-IN', Object.assign({
        timeZone: 'Asia/Kolkata', day: '2-digit', month: 'short', year: 'numeric',
        hour: '2-digit', minute: '2-digit', hour12: true,
      }, opts || {}));
    },
    istDate: function (ms) {
      if (!ms) return '—';
      return new Date(ms).toLocaleDateString('en-IN', {
        timeZone: 'Asia/Kolkata', weekday: 'short', day: '2-digit', month: 'long', year: 'numeric',
      });
    },
    istTime: function (ms) {
      if (!ms) return '—';
      return new Date(ms).toLocaleTimeString('en-IN', {
        timeZone: 'Asia/Kolkata', hour: '2-digit', minute: '2-digit', hour12: true,
      });
    },

    // mm:ss, or h:mm:ss past an hour.
    clock: function (msLeft) {
      var s = Math.max(0, Math.floor(msLeft / 1000));
      var h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), sec = s % 60;
      var p2 = function (n) { return n < 10 ? '0' + n : '' + n; };
      return h > 0 ? h + ':' + p2(m) + ':' + p2(sec) : p2(m) + ':' + p2(sec);
    },

    split: function (msLeft) {
      var s = Math.max(0, Math.floor(msLeft / 1000));
      return {
        days: Math.floor(s / 86400),
        hours: Math.floor((s % 86400) / 3600),
        minutes: Math.floor((s % 3600) / 60),
        seconds: s % 60,
      };
    },

    // Renders Days : Hours : Minutes : Seconds into a container and ticks it.
    // Returns a stop function. onDone fires once, when the target is reached.
    countdown: function (el, targetMs, onDone) {
      if (!el) return function () {};
      var done = false;
      var pad = function (n) { return n < 10 ? '0' + n : '' + n; };

      function unit(n, label) {
        return '<div class="cd-unit"><div class="n">' + pad(n) + '</div>' +
               '<div class="l">' + label + '</div></div>';
      }
      function tick() {
        var left = targetMs - QuizTime.now();
        if (left <= 0) {
          el.innerHTML = unit(0, 'Days') + unit(0, 'Hrs') + unit(0, 'Min') + unit(0, 'Sec');
          if (!done) { done = true; clearInterval(iv); if (onDone) onDone(); }
          return;
        }
        var t = QuizTime.split(left);
        el.innerHTML = unit(t.days, 'Days') + unit(t.hours, 'Hrs') +
                       unit(t.minutes, 'Min') + unit(t.seconds, 'Sec');
      }
      tick();
      var iv = setInterval(tick, 1000);
      return function () { clearInterval(iv); };
    },
  };

  /* ═══════════════════════════════════════════════════════════════
     UI
     ═══════════════════════════════════════════════════════════════ */
  var BRANDING = {
    event: 'National Postal Week 2026',
    sub: 'Online Quiz Competition',
    theme: 'One Postal Network, Infinite Possibilities',
    org: 'Department of Posts',
    division: 'Karur Division, Karur - 639001',
    window: '9th to 15th October 2026',
  };

  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  var QuizUI = {
    esc: esc,
    branding: BRANDING,

    // ── Theme ────────────────────────────────────────────────────
    // Shares the `theme` key with the rest of the portal, so a participant
    // who set dark mode elsewhere lands in dark mode here too.
    initTheme: function () {
      var saved = null;
      try { saved = localStorage.getItem('theme'); } catch (_) {}
      var dark = saved === 'dark' ||
        (!saved && global.matchMedia && global.matchMedia('(prefers-color-scheme: dark)').matches);
      document.documentElement.classList.toggle('dark', !!dark);
      return !!dark;
    },
    toggleTheme: function () {
      var dark = document.documentElement.classList.toggle('dark');
      try { localStorage.setItem('theme', dark ? 'dark' : 'light'); } catch (_) {}
      var btn = document.getElementById('themeBtn');
      if (btn) btn.innerHTML = dark ? '&#9728;' : '&#127769;';
      return dark;
    },

    // ── Header ───────────────────────────────────────────────────
    // `opts.compact` drops the big event banner — used on the examination
    // screen, where every pixel of vertical space belongs to the question.
    header: function (opts) {
      var o = opts || {};
      var b = Object.assign({}, BRANDING, o.branding || {});
      var banner = o.compact ? '' :
        '<div class="event-band">' +
          '<span class="event-tag"><span class="dot"></span>' + esc(b.window) + '</span>' +
          '<h2 class="event-title">' + esc(b.event) + ' &mdash; ' + esc(b.sub) + '</h2>' +
          '<p class="event-theme">' + esc(b.theme) + '</p>' +
        '</div>';

      var modbar = o.title ?
        '<div class="q-modbar">' +
          '<div class="mi">' + (o.icon || '&#127891;') + '</div>' +
          '<div style="min-width:0">' +
            '<h1>' + esc(o.title) + '</h1>' +
            (o.subtitle ? '<div class="sub">' + esc(o.subtitle) + '</div>' : '') +
          '</div>' +
          '<div class="spacer"></div>' +
          (o.actions || '') +
          '<button class="icon-btn" id="themeBtn" type="button" ' +
            'title="Switch between light and dark theme" aria-label="Switch theme">&#127769;</button>' +
        '</div>' : '';

      return '' +
        '<header class="q-header">' +
          '<div class="q-grain"></div>' +
          '<div class="q-header-in">' +
            // Fixed-width side slots keep the three title lines on the true
            // centre of the header, even though the two logos differ in width.
            '<div class="gov-band">' +
              '<div class="gov-side">' +
                '<img class="gov-crest goi" src="/assets/goi-logo.jpg" ' +
                  'alt="Government of India" />' +
              '</div>' +
              '<div class="gov-titles">' +
                '<div class="gov-l1">Government of India</div>' +
                '<div class="gov-l2">' + esc(b.org) + '</div>' +
                '<div class="gov-l3">' + esc(b.division) + '</div>' +
                '<div class="gov-rule"></div>' +
              '</div>' +
              '<div class="gov-side right">' +
                '<img class="gov-crest post" src="/assets/india-post-logo.png" alt="India Post" />' +
              '</div>' +
            '</div>' +
          '</div>' +
          banner +
          modbar +
        '</header>';
    },

    // ── Footer (mandatory credit) ────────────────────────────────
    // Exact wording and designation as prescribed; phone and email are
    // tappable, and the shimmer is disabled under prefers-reduced-motion.
    footer: function () {
      return '' +
        '<footer class="q-footer">' +
          // Moved down from the hero so the header leads with the event title
          // and theme, and the day's purpose closes the page instead.
          '<div class="wpd">World Post Day &middot; Celebrating India Post&rsquo;s enduring role in ' +
            'connecting citizens and building a modern, inclusive and citizen-centric ' +
            'postal network</div>' +
          '<div class="dev">Developed by Arun Selvaraj, Instructor, WTC Karur</div>' +
          '<div class="contact">For any queries, please call ' +
            '<a href="tel:+917904478591">+91 7904478591</a>' +
            '<span class="sep">/</span>' +
            '<a href="mailto:arun18@live.in">arun18@live.in</a>' +
          '</div>' +
          '<div class="org">' + esc(BRANDING.event) + ' &middot; ' + esc(BRANDING.sub) + '</div>' +
        '</footer>';
    },

    // Mounts header + footer around #app and wires the theme button.
    mount: function (opts) {
      var o = opts || {};
      this.initTheme();
      var host = document.getElementById('shell') || document.body;
      var app = document.getElementById('app');

      var head = document.createElement('div');
      head.innerHTML = this.header(o);
      host.insertBefore(head.firstChild, app || host.firstChild);

      var foot = document.createElement('div');
      foot.innerHTML = this.footer();
      host.appendChild(foot.firstChild);

      if (!document.getElementById('toast')) {
        var t = document.createElement('div');
        t.id = 'toast';
        t.setAttribute('role', 'status');
        t.setAttribute('aria-live', 'polite');
        document.body.appendChild(t);
      }

      var btn = document.getElementById('themeBtn');
      if (btn) {
        if (document.documentElement.classList.contains('dark')) btn.innerHTML = '&#9728;';
        btn.addEventListener('click', function () { QuizUI.toggleTheme(); });
      }
    },

    // ── Toast ────────────────────────────────────────────────────
    toastT: null,
    toast: function (msg, kind) {
      var t = document.getElementById('toast');
      if (!t) { return; }
      t.textContent = msg;
      t.className = 'on' + (kind ? ' ' + kind : '');
      clearTimeout(QuizUI.toastT);
      QuizUI.toastT = setTimeout(function () { t.className = ''; }, kind === 'err' ? 5200 : 3000);
    },
    err: function (e) {
      QuizUI.toast(e && e.message ? e.message : 'Something went wrong.', 'err');
    },

    // ── Pieces ───────────────────────────────────────────────────
    skeleton: function (lines) {
      var out = '';
      for (var i = 0; i < (lines || 3); i++) {
        out += '<div class="skel' + (i === 0 ? ' lg' : (i % 2 ? ' w60' : ' w40')) + '"></div>';
      }
      return out;
    },

    alert: function (kind, icon, html) {
      return '<div class="alert ' + (kind || '') + '"><div class="ai">' + (icon || '&#8505;') +
             '</div><div>' + html + '</div></div>';
    },

    empty: function (icon, title, body) {
      return '<div class="empty"><div class="ei">' + (icon || '&#128230;') + '</div>' +
             '<h3>' + esc(title) + '</h3><p>' + body + '</p></div>';
    },

    badge: function (text, kind) {
      return '<span class="badge ' + (kind || '') + '">' + esc(text) + '</span>';
    },

    statusBadge: function (status) {
      var map = {
        APPROVED: 'ok', PENDING: 'warn', REJECTED: 'err', SUSPENDED: 'err', CANCELLED: 'mute',
        LIVE: 'ok', SCHEDULED: '', DRAFT: 'mute', COMPLETED: '', RESULTS_PUBLISHED: 'ok',
        REGISTRATION_OPEN: 'ok', REGISTRATION_CLOSED: 'warn',
        ACTIVE: 'ok', ARCHIVED: 'mute',
        IN_PROGRESS: 'warn', SUBMITTED: 'ok', AUTO_SUBMITTED: 'ok', EXPIRED: 'err', VOID: 'mute',
      };
      return this.badge(String(status || '').replace(/_/g, ' '), map[status] || '');
    },

    // ── Modal ────────────────────────────────────────────────────
    modal: function (id, title, bodyHtml, footHtml) {
      var el = document.getElementById(id);
      if (!el) {
        el = document.createElement('div');
        el.id = id;
        el.className = 'modal';
        el.setAttribute('role', 'dialog');
        el.setAttribute('aria-modal', 'true');
        document.body.appendChild(el);
        el.addEventListener('click', function (e) {
          if (e.target === el) QuizUI.closeModal(id);
        });
      }
      el.innerHTML =
        '<div class="modal-box">' +
          '<div class="modal-head"><h3>' + esc(title) + '</h3>' +
            '<button class="modal-x" type="button" aria-label="Close">&times;</button></div>' +
          '<div class="modal-body">' + bodyHtml + '</div>' +
          (footHtml ? '<div class="btn-row" style="margin-top:16px">' + footHtml + '</div>' : '') +
        '</div>';
      el.querySelector('.modal-x').addEventListener('click', function () { QuizUI.closeModal(id); });
      el.classList.add('on');
      document.body.style.overflow = 'hidden';
      return el;
    },
    closeModal: function (id) {
      var el = document.getElementById(id);
      if (el) el.classList.remove('on');
      document.body.style.overflow = '';
    },

    // ── Navigation helpers ───────────────────────────────────────
    go: function (path) { global.location.href = path; },

    // Shown when the backend has not been configured yet, so the message is
    // precise rather than a dead button.
    notConfigured: function (detail) {
      return this.alert('warn', '&#9881;',
        '<b>Quiz backend not configured.</b><br>' + esc(detail || '') +
        '<br><br>An administrator must set the environment variables listed in ' +
        '<code>Leave Orders/quiz/DEPLOYMENT.md</code> in the Vercel project, then redeploy.');
    },
  };

  /* ═══════════════════════════════════════════════════════════════
     Export
     ═══════════════════════════════════════════════════════════════ */
  global.QuizAPI = QuizAPI;
  global.QuizUI = QuizUI;
  global.QuizTime = QuizTime;

})(window);
