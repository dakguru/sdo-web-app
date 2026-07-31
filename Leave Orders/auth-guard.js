/*  auth-guard.js  –  Client-side authentication gate for Karur SDO web app
 *  ────────────────────────────────────────────────────────────────────────
 *  Include this script in the <head> of EVERY protected page (before any
 *  other scripts or stylesheets that render content).
 *
 *  On load it checks localStorage for a valid session token. If the session
 *  is missing or expired, it immediately hides the page and redirects to
 *  /login.
 *
 *  Public API  (global `AuthSession`):
 *    AuthSession.user()      → { username, displayName, role } | null
 *    AuthSession.logout()    → clears session & redirects to /login
 *    AuthSession.isAdmin()   → boolean
 *  ────────────────────────────────────────────────────────────────────── */

(function () {
  'use strict';

  const SESSION_KEY  = 'sdo_session';
  const SESSION_TTL  = 7 * 24 * 60 * 60 * 1000;  // 7 days
  const LOGIN_PATH   = '/login';

  function getSession() {
    try {
      const raw = localStorage.getItem(SESSION_KEY);
      if (!raw) return null;
      const s = JSON.parse(raw);
      if (!s || !s.username || !s.ts) return null;
      if (Date.now() - s.ts > SESSION_TTL) {
        localStorage.removeItem(SESSION_KEY);
        return null;
      }
      return s;
    } catch (_) {
      return null;
    }
  }

  // ── Gate: hide page + redirect if no session ──────────────────
  const session = getSession();
  if (!session) {
    // Hide everything instantly to prevent flash of protected content
    document.documentElement.style.visibility = 'hidden';
    document.documentElement.style.opacity = '0';
    window.location.replace(LOGIN_PATH);
    // Halt script execution on this page
    throw new Error('[auth-guard] No valid session — redirecting to login.');
  }

  // ── Public helper ─────────────────────────────────────────────
  window.AuthSession = {
    /** Returns the logged-in user object, or null. */
    user: function () {
      const s = getSession();
      return s ? { username: s.username, displayName: s.displayName, role: s.role } : null;
    },

    /** Returns true if the current user has the ADMIN role. */
    isAdmin: function () {
      const s = getSession();
      return s ? s.role === 'ADMIN' : false;
    },

    /** Clears the session and redirects to the login page. */
    logout: function () {
      localStorage.removeItem(SESSION_KEY);
      window.location.replace(LOGIN_PATH);
    }
  };
})();
