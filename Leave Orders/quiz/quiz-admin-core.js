/*  quiz-admin-core.js — shared runtime for the Quiz administration pages
 *  ─────────────────────────────────────────────────────────────────────────
 *  Handles the parts every admin page repeats: the server-side sign-in gate,
 *  the tab strip, role checks, Excel export and a small table renderer.
 *
 *  SECURITY NOTE. The gate below is convenience, not protection. Every
 *  /api/quiz-admin action re-checks the admin session cookie and the caller's
 *  quiz role on the server, so hiding a tab here changes nothing about what a
 *  request is allowed to do. The portal's own localStorage session is NOT
 *  accepted for quiz administration: an answer key cannot be guarded by a
 *  check that runs in the browser, so administrators re-enter their existing
 *  portal password once and the server verifies the PBKDF2 hash itself.
 */

(function (global) {
  'use strict';

  var TABS = [
    { href: '/Quiz/admin',           label: 'Overview',      icon: '&#128202;', role: 'QUIZ_VIEWER' },
    { href: '/Quiz/admin/exams',     label: 'Examinations',  icon: '&#128198;', role: 'QUIZ_VIEWER' },
    { href: '/Quiz/admin/questions', label: 'Question Bank', icon: '&#10067;',  role: 'QUIZ_EDITOR' },
    { href: '/Quiz/admin/employees', label: 'Employees',     icon: '&#128101;', role: 'QUIZ_VIEWER' },
    { href: '/Quiz/admin/reports',   label: 'Reports',       icon: '&#128200;', role: 'QUIZ_VIEWER' },
    { href: '/Quiz/admin/settings',  label: 'Settings',      icon: '&#9881;',   role: 'QUIZ_ADMIN' },
  ];

  var RANK = { NONE: 0, QUIZ_VIEWER: 1, QUIZ_EDITOR: 2, QUIZ_ADMIN: 3 };

  var QuizAdmin = {
    actor: null,

    atLeast: function (needed) {
      var have = this.actor ? RANK[this.actor.quizRole] || 0 : 0;
      return have >= (RANK[needed] || 99);
    },

    /* ── Boot: mount chrome, gate on an admin session ──────────── */
    boot: function (opts) {
      var o = opts || {};
      QuizUI.mount({
        title: o.title || 'Quiz Administration',
        subtitle: o.subtitle || 'National Postal Week 2026 — Online Quiz Competition',
        icon: o.icon || '&#9881;',
        actions: '<a class="icon-btn" href="/Quiz" title="Open the participant view">Participant view</a>' +
                 '<button class="icon-btn" id="aOut" type="button" hidden>Sign out</button>'
      });

      var app = document.getElementById('app');
      var host = document.getElementById('adminBody');
      if (!host) {
        host = document.createElement('div');
        host.id = 'adminBody';
        app.appendChild(host);
      }
      host.innerHTML = '<div class="wrap">' + QuizUI.skeleton(3) + '</div>';

      QuizAPI.adminMe().then(function (me) {
        QuizTime.anchor(me.serverTimeMs);
        if (!me.signedIn) { QuizAdmin.renderLogin(host, o); return; }

        QuizAdmin.actor = me;
        var out = document.getElementById('aOut');
        if (out) {
          out.hidden = false;
          out.addEventListener('click', function () {
            QuizAPI.adminLogout().then(function () { location.reload(); });
          });
        }

        if (o.requireRole && !QuizAdmin.atLeast(o.requireRole)) {
          host.innerHTML = '<div class="wrap">' + QuizAdmin.tabs(o.active) +
            '<div class="card">' + QuizUI.alert('err', '&#128274;',
              '<b>You do not have access to this page.</b><br>Your quiz role is <b>' +
              QuizUI.esc(me.quizRole) + '</b>; this page needs <b>' +
              QuizUI.esc(o.requireRole.replace('QUIZ_', '').toLowerCase()) + '</b> rights or higher. ' +
              'Ask a Quiz Administrator to adjust your role.') + '</div></div>';
          return;
        }

        host.innerHTML = '<div class="wrap">' + QuizAdmin.tabs(o.active) +
          '<div id="pageBody"></div></div>';
        if (o.onReady) o.onReady(me, document.getElementById('pageBody'));
      }).catch(function (e) {
        host.innerHTML = '<div class="wrap"><div class="card">' +
          QuizUI.alert('err', '&#9888;', '<b>Could not reach the quiz server.</b><br>' +
            QuizUI.esc(e.message)) + '</div></div>';
      });
    },

    /* ── Sign-in form ──────────────────────────────────────────── */
    renderLogin: function (host, o) {
      host.innerHTML =
        '<div class="wrap-narrow">' +
          '<div class="card glass" style="max-width:420px;margin:0 auto">' +
            '<div class="card-head">' +
              '<div class="card-ic">&#128272;</div>' +
              '<div><div class="card-t">Administrator Sign-in</div>' +
              '<div class="card-s">Use your existing KRRSDO portal account</div></div>' +
            '</div>' +
            QuizUI.alert('', '&#8505;',
              'The Quiz module verifies your password <b>on the server</b>, separately from the ' +
              'portal dashboard, because this module holds examination answer keys. ' +
              'Your portal username and password are unchanged.') +
            '<form id="aForm" novalidate>' +
              '<div class="field">' +
                '<label for="aUser">Username</label>' +
                '<input class="input" id="aUser" autocomplete="username" autocapitalize="none" ' +
                  'spellcheck="false" required />' +
              '</div>' +
              '<div class="field">' +
                '<label for="aPass">Password</label>' +
                '<input class="input" id="aPass" type="password" autocomplete="current-password" required />' +
              '</div>' +
              '<div id="aErr"></div>' +
              '<button class="btn block" type="submit" id="aBtn">Sign in</button>' +
            '</form>' +
          '</div>' +
        '</div>';

      document.getElementById('aForm').addEventListener('submit', function (e) {
        e.preventDefault();
        var u = document.getElementById('aUser').value.trim();
        var p = document.getElementById('aPass').value;
        if (!u || !p) { QuizUI.toast('Enter your username and password.', 'err'); return; }

        var b = document.getElementById('aBtn');
        b.disabled = true; b.textContent = 'Signing in…';

        QuizAPI.auth('admin-login', null, { username: u, password: p })
          .then(function () { location.reload(); })
          .catch(function (err) {
            b.disabled = false; b.textContent = 'Sign in';
            document.getElementById('aErr').innerHTML =
              QuizUI.alert('err', '&#9888;', QuizUI.esc(err.message));
          });
      });
      document.getElementById('aUser').focus();
    },

    /* ── Tab strip ─────────────────────────────────────────────── */
    tabs: function (active) {
      var self = this;
      return '<nav class="tabs" aria-label="Quiz administration">' +
        TABS.filter(function (t) { return self.atLeast(t.role); }).map(function (t) {
          var on = t.href === active || (active && t.href.indexOf(active) === 0 && t.href === active);
          return '<a href="' + t.href + '" class="' + (on ? 'on' : '') + '">' +
            t.icon + ' ' + QuizUI.esc(t.label) + '</a>';
        }).join('') +
        '<span style="flex:1"></span>' +
        '<a href="#" class="" id="roleChip" style="pointer-events:none">' +
          (this.actor ? QuizUI.esc(this.actor.username) + ' · ' +
            QuizUI.esc(String(this.actor.quizRole).replace('QUIZ_', '')) : '') +
        '</a>' +
      '</nav>';
    },

    /* ── Excel export (SheetJS, already used across this portal) ── */
    exportXlsx: function (filename, sheetName, columns, rows) {
      if (!global.XLSX) {
        QuizUI.toast('Excel library is still loading — try again in a moment.', 'err');
        return;
      }
      var aoa = [columns].concat(rows);
      var ws = XLSX.utils.aoa_to_sheet(aoa);
      // Column widths sized to the content, capped so one long question does
      // not produce a 500-character column.
      ws['!cols'] = columns.map(function (c, i) {
        var max = String(c).length;
        rows.forEach(function (r) {
          var v = r[i] == null ? '' : String(r[i]);
          if (v.length > max) max = v.length;
        });
        return { wch: Math.min(60, Math.max(10, max + 2)) };
      });
      var wb = XLSX.utils.book_new();
      XLSX.utils.book_append_sheet(wb, ws, (sheetName || 'Sheet1').slice(0, 31));
      XLSX.writeFile(wb, filename);
    },

    /* ── Read an uploaded .xlsx/.csv into rows of objects ──────── */
    readSheet: function (file) {
      return new Promise(function (resolve, reject) {
        if (!global.XLSX) return reject(new Error('Excel library has not loaded yet.'));
        var fr = new FileReader();
        fr.onerror = function () { reject(new Error('That file could not be read.')); };
        fr.onload = function () {
          try {
            var wb = XLSX.read(new Uint8Array(fr.result), { type: 'array' });
            var ws = wb.Sheets[wb.SheetNames[0]];
            // raw:false + defval keeps Employee IDs as the text they are, so
            // leading zeros are never eaten by a numeric cast.
            var rows = XLSX.utils.sheet_to_json(ws, { defval: '', raw: false });
            resolve({ rows: rows, sheetName: wb.SheetNames[0], headers: headersOf(ws) });
          } catch (e) {
            reject(new Error('That file is not a readable spreadsheet.'));
          }
        };
        fr.readAsArrayBuffer(file);
      });
    },

    /* ── Simple table renderer ─────────────────────────────────── */
    table: function (columns, rows, opts) {
      var o = opts || {};
      if (!rows || !rows.length) {
        return QuizUI.empty(o.emptyIcon || '&#128203;', o.emptyTitle || 'Nothing to show',
          o.emptyBody || 'There are no records matching the current filters.');
      }
      return '<div class="tbl-wrap"><table class="tbl"><thead><tr>' +
        columns.map(function (c) { return '<th>' + QuizUI.esc(c) + '</th>'; }).join('') +
        '</tr></thead><tbody>' +
        rows.map(function (r) {
          return '<tr>' + r.map(function (cell, i) {
            var wrap = o.wrapCols && o.wrapCols.indexOf(i) >= 0;
            var num = o.numCols && o.numCols.indexOf(i) >= 0;
            return '<td class="' + (wrap ? 'wrap ' : '') + (num ? 'num' : '') + '">' +
              (cell == null ? '' : (o.html ? cell : QuizUI.esc(cell))) + '</td>';
          }).join('') + '</tr>';
        }).join('') +
        '</tbody></table></div>';
    },

    /* ── Readiness banners before content is uploaded ──────────── */
    readinessBanner: function (readiness) {
      if (!readiness) return '';
      var out = '';
      if (!readiness.directoryImported) {
        out += QuizUI.alert('warn', '&#128193;',
          '<b>Employee Directory Pending Import</b><br>No employees have been imported yet, so ' +
          'nobody can register. Go to <a href="/Quiz/admin/employees">Employees</a> and upload the ' +
          'divisional employee list.');
      }
      if (!readiness.questionBankUploaded) {
        out += QuizUI.alert('warn', '&#10067;',
          '<b>Question Bank Not Yet Uploaded</b><br>There are no active questions, so no examination ' +
          'can be published. Go to <a href="/Quiz/admin/questions">Question Bank</a> to add or ' +
          'import questions.');
      }
      if (!readiness.examScheduled) {
        out += QuizUI.alert('warn', '&#128198;',
          '<b>Examination Not Yet Scheduled</b><br>Create and publish an examination in ' +
          '<a href="/Quiz/admin/exams">Examinations</a> for it to appear on employee dashboards.');
      }
      return out;
    },

    /* ── Date helpers for the scheduling form ──────────────────── */
    // <input type="datetime-local"> has no timezone. The administrator thinks
    // in IST, so we convert explicitly in both directions rather than letting
    // the browser's own zone decide what "10:00" meant.
    msToLocalInput: function (ms) {
      if (!ms) return '';
      var d = new Date(ms);
      var ist = new Date(d.getTime() + (330 + d.getTimezoneOffset()) * 60000);
      var p = function (n) { return n < 10 ? '0' + n : '' + n; };
      return ist.getFullYear() + '-' + p(ist.getMonth() + 1) + '-' + p(ist.getDate()) +
        'T' + p(ist.getHours()) + ':' + p(ist.getMinutes());
    },
    localInputToMs: function (v) {
      if (!v) return null;
      var m = String(v).match(/^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})/);
      if (!m) return null;
      // Treat the typed value as IST (UTC+05:30) and convert to epoch ms.
      return Date.UTC(+m[1], +m[2] - 1, +m[3], +m[4], +m[5]) - (330 * 60000);
    },
  };

  function headersOf(ws) {
    var out = [];
    try {
      var range = XLSX.utils.decode_range(ws['!ref']);
      for (var c = range.s.c; c <= range.e.c; c++) {
        var cell = ws[XLSX.utils.encode_cell({ r: range.s.r, c: c })];
        out.push(cell ? String(cell.v).trim() : '');
      }
    } catch (_) {}
    return out;
  }

  global.QuizAdmin = QuizAdmin;
})(window);
