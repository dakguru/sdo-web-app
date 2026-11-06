/*  home-android.js — the Android app's HomeScreen, on the phone web app.
 *  ─────────────────────────────────────────────────────────────────────
 *  A section-for-section port of android-app/.../ui/home/HomeScreen.kt:
 *
 *      hero  ·  Events & Announcements  ·  Today: Birthdays & Retirements
 *      Today's MO Programme  ·  Mail Overseer — Overdue Offices
 *      Quick access  ·  Appearance
 *
 *  Only runs on phones. The desktop homepage is untouched and stays visible.
 *  Data comes from the same Supabase tables the phone app reads, so the two
 *  screens always agree.
 */

(function () {
  'use strict';

  if (!window.KSD || !window.KSD.isPhone()) return;

  var VISIT_CYCLE_MONTHS = 3; // MoScreens.kt: an office falls due 3 months after its last visit

  var host = document.getElementById('ksdHome');
  if (!host) return;

  document.body.classList.add('ksd-home-active');
  host.hidden = false;

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"]/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c];
    });
  }

  function todayIso() {
    var d = new Date();
    return (
      d.getFullYear() +
      '-' + String(d.getMonth() + 1).padStart(2, '0') +
      '-' + String(d.getDate()).padStart(2, '0')
    );
  }

  // ── Skeleton ────────────────────────────────────────────────────────
  var HOME_DATE = new Date().toLocaleDateString('en-IN', {
    weekday: 'short', day: '2-digit', month: 'short', year: 'numeric',
  });

  host.innerHTML =
    '<section class="ksd-hero">' +
      '<h2>Karur Sub Division</h2>' +
      '<p>Staff directory · Office management · Mail Overseer beats</p>' +
      '<div class="ksd-hero-foot">DEPARTMENT OF POSTS · KARUR SUB DIVISION</div>' +
    '</section>' +

    '<section class="ksd-events" id="ksdEvents">' +
      '<div class="ksd-events-head"><span style="font-size:20px">📢</span>' +
        '<div><div class="t">Events &amp; Announcements</div><div class="s">KARUR SUB DIVISION</div></div>' +
      '</div>' +
      '<div id="ksdEventList" style="margin-top:12px;font-size:14.5px">Loading…</div>' +
    '</section>' +

    '<section class="ksd-section">' +
      '<h3>Today · Birthdays &amp; Retirements</h3>' +
      '<a class="ksd-row ksd-ripple" href="/birthdays">' +
        '<span style="font-size:20px">🎂</span>' +
        '<span class="body"><span class="t" id="ksdBdayT">Loading…</span><br><span class="s" id="ksdBdayS"></span></span>' +
        '<span class="chev">›</span>' +
      '</a>' +
      '<a class="ksd-row ksd-ripple" href="/retirements">' +
        '<span style="font-size:20px">🗓️</span>' +
        '<span class="body"><span class="t" id="ksdRetT">Loading…</span><br><span class="s" id="ksdRetS"></span></span>' +
        '<span class="chev">›</span>' +
      '</a>' +
    '</section>' +

    '<section class="ksd-section">' +
      '<h3>Today\'s MO Programme · ' + esc(HOME_DATE) + '</h3>' +
      '<div id="ksdProgramme" style="font-size:12.5px">Loading…</div>' +
    '</section>' +

    '<div class="ksd-rowtitle">Mail Overseer — Overdue Offices</div>' +
    '<div class="ksd-stats">' +
      '<a class="ksd-stat ksd-ripple" href="/mail-overseers">' +
        '<span class="l">MO - I<span class="chev">›</span></span>' +
        '<div class="n" id="ksdMoI">—</div>' +
      '</a>' +
      '<a class="ksd-stat ksd-ripple" href="/mail-overseers">' +
        '<span class="l">MO - II<span class="chev">›</span></span>' +
        '<div class="n" id="ksdMoII">—</div>' +
      '</a>' +
    '</div>' +

    '<section class="ksd-section">' +
      '<h3>Quick access</h3>' +
      '<div class="ksd-quick" id="ksdQuick"></div>' +
    '</section>' +

    '<section class="ksd-section">' +
      '<h3>Appearance</h3>' +
      '<div class="ksd-chips" id="ksdModes"></div>' +
      '<div style="margin-top:14px;font-size:12px;font-weight:600;letter-spacing:.3px;opacity:.7">THEME</div>' +
      '<div class="ksd-swatches" id="ksdSwatches"></div>' +
    '</section>' +

    '<div style="height:12px"></div>';

  // ── Quick access (HomeScreen.kt tiles, pointed at the web routes) ───
  // Accents are design-system colours, so the tiles re-tint with the theme.
  var TILES = [
    ['🏅', 'Delivery Performance', '--lg-green', '/performance'],
    ['📇', 'Staff Directory', '--lg-indigo', '/employees'],
    ['📞', 'Phone Directory', '--lg-teal', '/phone-directory'],
    ['🔁', 'Arrangements', '--lg-blue', '/arrangements'],
    ['✅', 'Cent % Verification', '--lg-mint', '/cent-percent-verification'],
    ['🎂', 'Birthdays', '--lg-purple', '/birthdays'],
    ['🗓️', 'Retirements', '--lg-pink', '/retirements'],
    ['📋', 'Leave Orders', '--lg-orange', '/leave-orders'],
  ];
  document.getElementById('ksdQuick').innerHTML = TILES.map(function (t) {
    return (
      '<a class="ksd-tile ksd-ripple" href="' + t[3] + '" style="--tile-accent:var(' + t[2] + ')">' +
      '<span class="ic">' + t[0] + '</span><span class="lb">' + esc(t[1]) + '</span></a>'
    );
  }).join('');

  // ── Appearance (the Android ThemeController, same chips and swatches) ──
  var modes = [['system', '🌗 System'], ['light', '☀️ Light'], ['dark', '🌙 Dark']];

  function paintAppearance() {
    document.getElementById('ksdModes').innerHTML = modes.map(function (m) {
      return (
        '<button type="button" class="ksd-chip ksd-ripple" data-mode="' + m[0] + '"' +
        ' aria-pressed="' + (window.KSD.theme.mode === m[0]) + '">' + m[1] + '</button>'
      );
    }).join('');

    document.getElementById('ksdSwatches').innerHTML = window.KSD.palettes.map(function (p) {
      var on = window.KSD.theme.palette === p.id;
      return (
        '<button type="button" class="ksd-swatch" data-palette="' + p.id + '" aria-pressed="' + on + '">' +
        '<span class="dot" style="background:' + p.swatch + ';--sw:' + p.swatch + '">' + (on ? '✓' : '') + '</span>' +
        '<span class="nm">' + esc(p.label) + '</span></button>'
      );
    }).join('');
  }
  paintAppearance();

  host.addEventListener('click', function (e) {
    var mode = e.target.closest('[data-mode]');
    if (mode) { window.KSD.theme.setMode(mode.dataset.mode); paintAppearance(); return; }
    var pal = e.target.closest('[data-palette]');
    if (pal) { window.KSD.theme.setPalette(pal.dataset.palette); paintAppearance(); }
  });

  // ── Today's birthdays & retirements ─────────────────────────────────
  // The desktop panels on this page already load and compute these from the
  // DS / GDS / OUT datasets; mirror their result instead of fetching twice.
  function mirrorPeople() {
    var bCount = document.getElementById('bdayCount');
    var rCount = document.getElementById('retireCount');
    if (!bCount || !rCount) return;

    var names = function (listId) {
      var list = document.getElementById(listId);
      if (!list) return [];
      return [].slice.call(list.querySelectorAll('.person-name, .nm, .name'))
        .map(function (n) { return n.textContent.trim(); })
        .filter(Boolean);
    };

    var bn = parseInt(bCount.textContent, 10);
    var rn = parseInt(rCount.textContent, 10);

    if (!isNaN(bn)) {
      var bNames = names('bdayList');
      document.getElementById('ksdBdayT').textContent =
        bn ? (bNames.join(', ') || bn + ' today') : 'No birthdays today';
      document.getElementById('ksdBdayS').textContent =
        bn ? bn + ' birthday' + (bn === 1 ? '' : 's') + ' today · tap to view all' : '';
    }
    if (!isNaN(rn)) {
      var rNames = names('retireList');
      document.getElementById('ksdRetT').textContent =
        rn ? (rNames.join(', ') || rn + ' this month') : 'No retirements this month';
      document.getElementById('ksdRetS').textContent =
        rn ? rn + ' retiring this month · tap to view all' : '';
    }
  }

  var panels = document.querySelector('.page-wrap');
  if (panels) {
    new MutationObserver(mirrorPeople).observe(panels, { childList: true, subtree: true, characterData: true });
  }
  setTimeout(mirrorPeople, 1500);

  // ── Cloud sections ──────────────────────────────────────────────────
  function daysLeftLabel(d) {
    if (d === 0) return 'Today';
    if (d === 1) return 'Tomorrow';
    return d + ' days left';
  }

  async function loadEvents() {
    var box = document.getElementById('ksdEventList');
    try {
      var rows = await SB.select('app_events', 'deleted=eq.false&select=id,date,title,important&limit=50');
      var today = new Date(todayIso());
      var items = rows
        .map(function (e) {
          var date = /^\d{4}-\d{2}-\d{2}$/.test((e.date || '').trim()) ? e.date.trim() : null;
          var days = date ? Math.round((new Date(date) - today) / 86400000) : null;
          return { title: e.title, date: date, days: days, important: !!e.important };
        })
        // Dated events that have passed are history; standing announcements stay.
        .filter(function (i) { return i.days === null || i.days >= 0; })
        .sort(function (a, b) {
          return (b.important - a.important) ||
            ((a.days === null) - (b.days === null)) ||
            ((a.days == null ? 1e9 : a.days) - (b.days == null ? 1e9 : b.days));
        });

      if (!items.length) {
        box.innerHTML =
          '<div>No events or announcements yet.</div>' +
          '<div style="font-size:11px;opacity:.75;margin-top:2px">Admin / ASP / PA users can add them from the Android app.</div>';
        return;
      }
      box.innerHTML = items.map(function (i) {
        var icon = i.important ? '⭐' : i.date === null ? '📌' : '🗓️';
        var when = i.date
          ? new Date(i.date).toLocaleDateString('en-IN', { day: '2-digit', month: 'short', year: 'numeric' })
          : '';
        return (
          '<div class="ksd-event' + (i.important ? ' important' : '') + '">' +
          '<span>' + icon + '</span>' +
          '<span class="body"><span class="title">' + esc(i.title) + '</span>' +
          (when ? '<br><span class="date">' + esc(when) + '</span>' : '') + '</span>' +
          (i.days === null ? '' :
            '<span class="days' + (i.days === 0 ? ' today' : '') + '">' + daysLeftLabel(i.days) + '</span>') +
          '</div>'
        );
      }).join('');
    } catch (err) {
      box.textContent = 'Events are unavailable offline.';
    }
  }

  async function loadProgramme() {
    var box = document.getElementById('ksdProgramme');
    try {
      var rows = await SB.select(
        'app_programmes',
        'deleted=eq.false&date=eq.' + todayIso() + '&select=beat,details,author'
      );
      if (!rows.length) {
        box.textContent = 'No Mail Overseer programme set for today.';
        box.style.color = 'var(--ksd-on-surface-variant)';
        return;
      }
      box.style.color = 'var(--ksd-on-surface)';
      box.innerHTML = rows.map(function (p) {
        var label = p.beat === 'MO_I' ? 'MO I' : p.beat === 'MO_II' ? 'MO II' : p.beat;
        return (
          '<div style="display:flex;gap:10px;padding:5px 0">' +
          '<b style="color:var(--ksd-accent);max-width:120px;flex:none">' + esc(label) + '</b>' +
          '<span style="flex:1"><span style="font-size:14.5px">' + esc(p.details) + '</span>' +
          (p.author ? '<br><span style="opacity:.7">updated by ' + esc(p.author) + '</span>' : '') +
          '</span></div>'
        );
      }).join('');
    } catch (err) {
      box.textContent = 'Programme is unavailable offline.';
    }
  }

  /** Overdue = last visit more than VISIT_CYCLE_MONTHS ago (MoScreens.kt buildRow). */
  async function loadOverdue() {
    if (typeof SEED_MO_I === 'undefined') return;
    try {
      var visitRows = await SB.select('app_mo_visits', 'select=beat,serial_no,visits');
      var overlay = {};
      visitRows.forEach(function (v) {
        overlay[v.beat + '#' + v.serial_no] = String(v.visits || '')
          .split(',').map(function (s) { return s.trim(); }).filter(Boolean);
      });

      var today = todayIso();
      var count = function (beat, seed) {
        return seed.filter(function (row) {
          var visits = overlay[beat + '#' + row[0]] || row[3];
          if (!visits || !visits.length) return false; // never visited is "no visit yet", not overdue
          var last = visits.slice().sort().pop();
          var due = new Date(last);
          due.setMonth(due.getMonth() + VISIT_CYCLE_MONTHS);
          return due.toISOString().slice(0, 10) < today;
        }).length;
      };

      document.getElementById('ksdMoI').textContent = count('MO_I', SEED_MO_I);
      document.getElementById('ksdMoII').textContent = count('MO_II', SEED_MO_II);
    } catch (err) {
      /* offline — the cards keep their placeholder */
    }
  }

  function loadCloud() {
    if (typeof SB === 'undefined') return setTimeout(loadCloud, 400);
    loadEvents();
    loadProgramme();
    loadOverdue();
  }
  loadCloud();
})();
