// api/quiz-auth.js — authentication for the Quiz module.
//
// Two separate audiences, two separate cookies, never interchangeable:
//   PARTICIPANT — an employee, signed in with Google (or the admin-enabled
//                 Employee ID fallback). Can only ever reach their own data.
//   ADMIN       — an existing KRRSDO portal user, re-authenticated here
//                 server-side against app_users, then mapped to a quiz role.
//
// Dispatch is by ?action=, so the whole module costs one Vercel function.
//
//   GET  ?action=config         public — client id, fallback flag, branding
//   POST  action=nonce          issue a single-use nonce for Google sign-in
//   POST  action=google         verify a Google ID token, open a session
//   POST  action=employee-id    roster fallback sign-in (off unless enabled)
//   GET  ?action=me             current participant + registration
//   POST  action=logout         end the participant session
//   POST  action=admin-login    portal username + password
//   GET  ?action=admin-me       current admin + quiz role
//   POST  action=admin-logout   end the admin session

'use strict';

const Q = require('./_quizlib');
const crypto = require('crypto');

const NONCE_COOKIE = 'quiz_nonce';

module.exports = async function handler(req, res) {
  try {
    const cfgErr = Q.configError();
    const action = String((req.query && req.query.action) || '').toLowerCase();

    // The config probe must answer even when the backend is half-configured,
    // so the landing page can show a precise message instead of a dead button.
    if (action === 'config') return await configAction(req, res, cfgErr);
    if (action === 'status') return await statusAction(req, res, cfgErr);
    if (cfgErr) throw new Q.AppError(cfgErr, 503, 'NOT_CONFIGURED');

    Q.requireJsonRequest(req);

    switch (action) {
      case 'nonce':        return await nonceAction(req, res);
      case 'google':       return await googleAction(req, res);
      case 'employee-id':  return await employeeIdAction(req, res);
      case 'me':           return await meAction(req, res);
      case 'logout':       return await logoutAction(req, res);
      case 'admin-login':  return await adminLoginAction(req, res);
      case 'admin-me':     return await adminMeAction(req, res);
      case 'admin-logout': return await adminLogoutAction(req, res);
      default:
        throw new Q.AppError('Unknown action.', 404, 'UNKNOWN_ACTION');
    }
  } catch (err) {
    return Q.fail(res, err);
  }
};

// ───────────────────────────────────────────────────────────────────
//  Public configuration
// ───────────────────────────────────────────────────────────────────
async function configAction(req, res, cfgErr) {
  let reg = {}, branding = {};
  if (!cfgErr) {
    try {
      const s = await Q.getSettings();
      reg = s.registration || {};
      branding = s.branding || {};
    } catch (_) { /* fall through with defaults */ }
  }
  return Q.ok(res, {
    googleClientId: Q.GOOGLE_CLIENT_ID || null,
    googleReady: !!Q.GOOGLE_CLIENT_ID,
    employeeIdFallback: reg.allow_employee_id_fallback === true,
    allowedEmailDomains: Array.isArray(reg.allowed_email_domains) ? reg.allowed_email_domains : [],
    branding: {
      event: branding.event || 'National Postal Week 2026',
      theme: branding.theme || 'One Postal Network, Infinite Possibilities',
      org: branding.org || 'Department of Posts',
      division: branding.division || 'Karur Division, Karur - 639001',
      window: branding.window || '9th to 15th October 2026',
    },
    serverTimeMs: Date.now(),
    configured: !cfgErr,
    configError: cfgErr || null,
  });
}

// ───────────────────────────────────────────────────────────────────
//  Public examination status
//
//  Feeds the "live / opens soon" chip on the portal dashboard card. It carries
//  only what is already announced publicly — the title, the status and the
//  window. No question, no answer, no participant, no count.
// ───────────────────────────────────────────────────────────────────
async function statusAction(req, res, cfgErr) {
  if (cfgErr) return Q.ok(res, { configured: false, exams: [], serverTimeMs: Date.now() });
  let exams = [];
  try {
    const rows = await Q.sbSelect('quiz_exams',
      'select=title,status,exam_open_ms,exam_close_ms' +
      '&status=in.(SCHEDULED,REGISTRATION_OPEN,REGISTRATION_CLOSED,LIVE)' +
      '&order=exam_open_ms.asc&limit=10');
    exams = (rows || []).map(e => ({
      title: e.title,
      status: e.status,
      examOpenMs: e.exam_open_ms,
      examCloseMs: e.exam_close_ms,
    }));
  } catch (_) { /* a status chip is never worth a 500 */ }
  return Q.ok(res, { configured: true, exams, serverTimeMs: Date.now() });
}

// ───────────────────────────────────────────────────────────────────
//  Google sign-in nonce
//
//  The page asks for a nonce, hands it to Google Identity Services, and Google
//  embeds it in the ID token. We compare the token's nonce against the cookie,
//  so a token captured elsewhere cannot be replayed into this application.
// ───────────────────────────────────────────────────────────────────
async function nonceAction(req, res) {
  const nonce = crypto.randomBytes(16).toString('base64url');
  const cookie = `${NONCE_COOKIE}=${nonce}; Path=/; HttpOnly; Secure; SameSite=Lax; Max-Age=600`;
  return Q.ok(res, { nonce }, [cookie]);
}

function takeNonce(req) {
  const c = Q.parseCookies(req);
  return c[NONCE_COOKIE] || null;
}

function clearNonce() {
  return `${NONCE_COOKIE}=; Path=/; HttpOnly; Secure; SameSite=Lax; Max-Age=0`;
}

// ───────────────────────────────────────────────────────────────────
//  Google sign-in
//
//  Holding a Google account proves nothing about employment. All this step
//  establishes is "this person controls this Google account". Eligibility is
//  decided later, in quiz-participant.js, against the uploaded roster.
// ───────────────────────────────────────────────────────────────────
async function googleAction(req, res) {
  const body = await Q.readJsonBody(req);
  const credential = body.credential || body.id_token;
  if (!credential) throw new Q.AppError('No Google credential was received.', 400);

  await Q.rateLimit('gsignin:' + (Q.clientIp(req) || 'unknown'), 30, 60 * 1000);

  const expectedNonce = takeNonce(req);
  const claims = await Q.verifyGoogleIdToken(credential, expectedNonce);

  if (!claims.email || !claims.emailVerified) {
    throw new Q.AuthError(
      'Your Google account has no verified email address, so it cannot be used to register. ' +
      'Please sign in with a Google account whose email is verified.', 403);
  }

  // Optional domain restriction. Empty list = any domain, per the spec.
  const settings = await Q.getSettings();
  const domains = (settings.registration || {}).allowed_email_domains;
  if (Array.isArray(domains) && domains.length) {
    const dom = claims.email.split('@')[1] || '';
    const allowed = domains.map(d => String(d).toLowerCase().replace(/^@/, ''));
    if (!allowed.includes(dom)) {
      throw new Q.AuthError(
        'Sign-in is restricted to these email domains: ' + allowed.join(', ') + '.', 403);
    }
  }

  const now = Date.now();
  let identity = await Q.sbOne('quiz_identities', 'select=*&google_sub=' + Q.eq(claims.sub));

  if (identity) {
    // Email and display name can legitimately change on the Google side; the
    // sub is the identity, so we refresh the cosmetic fields and keep the row.
    await Q.sbUpdate('quiz_identities', 'id=' + Q.eq(identity.id), {
      email: claims.email,
      email_verified: claims.emailVerified,
      display_name: claims.name,
      picture_url: claims.picture,
      last_login_ms: now,
    }, { returning: false });
  } else {
    const rows = await Q.sbInsert('quiz_identities', {
      provider: 'google',
      google_sub: claims.sub,
      email: claims.email,
      email_verified: claims.emailVerified,
      display_name: claims.name,
      picture_url: claims.picture,
      created_at_ms: now,
      last_login_ms: now,
    });
    identity = rows[0];
  }

  // A fresh session on every sign-in, so a fixated session id is worthless.
  const sess = await Q.createSession(req, 'PARTICIPANT', { identityId: identity.id });
  const registration = await Q.sbOne('quiz_registrations', 'select=*&identity_id=' + Q.eq(identity.id));

  await Q.logEvent(req, {
    identity_id: identity.id,
    registration_id: registration ? registration.id : null,
    type: 'SIGN_IN_GOOGLE',
    detail: { email: claims.email },
  });

  return Q.ok(res, {
    identity: publicIdentity(identity),
    registration: publicRegistration(registration),
    needsRegistration: !registration,
  }, [Q.sessionCookie('PARTICIPANT', sess.token, Q.PARTICIPANT_TTL_MS), clearNonce()]);
}

// ───────────────────────────────────────────────────────────────────
//  Employee ID fallback sign-in
//
//  Disabled by default. It exists only so the examination can still run if the
//  Google OAuth client is not ready in time. It is DELIBERATELY weaker than
//  Google sign-in: knowledge of an Employee ID plus the matching name is not
//  proof of identity, so every such sign-in is recorded as NOTICE severity and
//  the registration is marked as not identity-verified for admin review.
// ───────────────────────────────────────────────────────────────────
async function employeeIdAction(req, res) {
  const settings = await Q.getSettings();
  if ((settings.registration || {}).allow_employee_id_fallback !== true) {
    throw new Q.AuthError(
      'Employee ID sign-in is not enabled. Please use Continue with Google.', 403);
  }

  const body = await Q.readJsonBody(req);
  const empId = String(body.employee_id || '').trim();
  const typedName = String(body.employee_name || '').trim();

  if (!/^[0-9A-Za-z/-]{3,24}$/.test(empId)) {
    throw new Q.AppError('Please enter a valid Employee ID.', 400, 'BAD_EMP_ID');
  }
  if (typedName.length < 3) {
    throw new Q.AppError('Please enter your name exactly as it appears in the service records.', 400);
  }

  // Tight limit: this endpoint is the only one that accepts a bare Employee ID,
  // so it is the one an attacker would use to walk the roster.
  await Q.rateLimit('empsignin:' + (Q.clientIp(req) || 'unknown'), 8, 60 * 1000);

  const emp = await Q.sbOne('quiz_employees',
    'select=*&employee_id=' + Q.eq(empId) + '&active=is.true');

  // One message for both "no such id" and "name mismatch", so the endpoint
  // cannot be used to confirm which Employee IDs exist.
  const nameOk = emp && normaliseName(emp.employee_name) === normaliseName(typedName);
  if (!emp || !nameOk) {
    await Q.logEvent(req, {
      type: 'EMP_SIGNIN_FAILED', severity: 'SUSPECT',
      detail: { employee_id: empId, reason: emp ? 'name_mismatch' : 'unknown_id' },
    });
    throw new Q.AuthError(
      'We could not match that Employee ID and name against the Karur Division records. ' +
      'Please check both entries, or contact the administrator.', 403);
  }

  const now = Date.now();
  const existing = await Q.sbOne('quiz_registrations', 'select=*&employee_id=' + Q.eq(empId));
  let identity;

  if (existing) {
    // The Employee ID is already claimed — reuse its identity rather than
    // minting a second one, which would trip the one-identity-per-employee
    // constraint and lock the real employee out of their own registration.
    identity = await Q.sbOne('quiz_identities', 'select=*&id=' + Q.eq(existing.identity_id));
    if (identity && identity.provider === 'google') {
      throw new Q.AuthError(
        'This Employee ID is already registered through a Google account. ' +
        'Please sign in with that Google account, or contact the administrator ' +
        'on +91 7904478591 to have it re-assigned.', 409);
    }
  }

  if (!identity) {
    const rows = await Q.sbInsert('quiz_identities', {
      provider: 'employee_id',
      google_sub: null,
      email: null,
      email_verified: false,
      display_name: emp.employee_name,
      created_at_ms: now,
      last_login_ms: now,
    });
    identity = rows[0];
  } else {
    await Q.sbUpdate('quiz_identities', 'id=' + Q.eq(identity.id),
      { last_login_ms: now }, { returning: false });
  }

  const sess = await Q.createSession(req, 'PARTICIPANT', { identityId: identity.id });
  const registration = await Q.sbOne('quiz_registrations', 'select=*&identity_id=' + Q.eq(identity.id));

  await Q.logEvent(req, {
    identity_id: identity.id,
    registration_id: registration ? registration.id : null,
    type: 'SIGN_IN_EMPLOYEE_ID', severity: 'NOTICE',
    detail: { employee_id: empId },
  });

  return Q.ok(res, {
    identity: publicIdentity(identity),
    registration: publicRegistration(registration),
    needsRegistration: !registration,
    prefill: {
      employee_id: emp.employee_id,
      employee_name: emp.employee_name,
      designation: emp.designation,
      office: emp.office,
    },
  }, [Q.sessionCookie('PARTICIPANT', sess.token, Q.PARTICIPANT_TTL_MS)]);
}

// Names in the roster carry double spaces and initials in varying order
// ("ROCHE INBARAJ  M"), so comparison ignores case, punctuation and spacing.
function normaliseName(s) {
  return String(s || '').toUpperCase().replace(/[^A-Z0-9]/g, '');
}

// ───────────────────────────────────────────────────────────────────
//  Session introspection
// ───────────────────────────────────────────────────────────────────
async function meAction(req, res) {
  const s = await Q.readSession(req, 'PARTICIPANT');
  if (!s) return Q.ok(res, { signedIn: false, serverTimeMs: Date.now() });

  const identity = await Q.sbOne('quiz_identities', 'select=*&id=' + Q.eq(s.identity_id));
  const registration = await Q.sbOne('quiz_registrations', 'select=*&identity_id=' + Q.eq(s.identity_id));

  await Q.sbUpdate('quiz_sessions', 'token_hash=' + Q.eq(Q.sha256(s._raw)),
    { last_seen_ms: Date.now() }, { returning: false }).catch(() => {});

  return Q.ok(res, {
    signedIn: true,
    identity: publicIdentity(identity),
    registration: publicRegistration(registration),
    needsRegistration: !registration,
    serverTimeMs: Date.now(),
  });
}

async function logoutAction(req, res) {
  await Q.revokeSession(req, 'PARTICIPANT');
  return Q.ok(res, { signedOut: true }, [Q.clearCookie('PARTICIPANT')]);
}

// ───────────────────────────────────────────────────────────────────
//  Administrator sign-in
//
//  Uses the existing portal credentials, but the PBKDF2 comparison happens
//  here on the server. The portal's browser-side check is fine for pages that
//  hold no secrets; it is not sufficient to guard an answer key.
// ───────────────────────────────────────────────────────────────────
async function adminLoginAction(req, res) {
  const body = await Q.readJsonBody(req);
  const username = String(body.username || '').trim().toLowerCase();
  const password = String(body.password || '');

  await Q.rateLimit('adminlogin:' + (Q.clientIp(req) || 'unknown'), 10, 5 * 60 * 1000);
  if (username) await Q.rateLimit('adminlogin:u:' + username, 10, 5 * 60 * 1000);

  const user = await Q.verifyPortalPassword(username, password);
  if (!user) {
    await Q.logEvent(req, {
      type: 'ADMIN_LOGIN_FAILED', severity: 'SUSPECT', detail: { username },
    });
    throw new Q.AuthError('Invalid username or password.', 401);
  }

  const quizRole = await Q.resolveQuizRole(user);
  if (!Q.roleAtLeast(quizRole, 'QUIZ_VIEWER')) {
    await Q.logEvent(req, {
      type: 'ADMIN_LOGIN_DENIED', severity: 'NOTICE',
      detail: { username, portal_role: user.portalRole },
    });
    throw new Q.AuthError(
      'Your portal account does not have Quiz module access. ' +
      'Ask a Quiz Administrator to grant you a role.', 403);
  }

  const sess = await Q.createSession(req, 'ADMIN', {
    username: user.username, quizRole,
  });
  await Q.logAudit(req, { username: user.username, role: quizRole }, 'ADMIN_LOGIN', 'session', null, null,
    { portal_role: user.portalRole, quiz_role: quizRole });

  return Q.ok(res, {
    username: user.username,
    displayName: user.displayName,
    portalRole: user.portalRole,
    quizRole,
  }, [Q.sessionCookie('ADMIN', sess.token, Q.ADMIN_TTL_MS)]);
}

async function adminMeAction(req, res) {
  const s = await Q.readSession(req, 'ADMIN');
  if (!s) return Q.ok(res, { signedIn: false, serverTimeMs: Date.now() });
  return Q.ok(res, {
    signedIn: true,
    username: s.username,
    quizRole: s.quiz_role,
    expiresMs: s.expires_ms,
    serverTimeMs: Date.now(),
  });
}

async function adminLogoutAction(req, res) {
  await Q.revokeSession(req, 'ADMIN');
  return Q.ok(res, { signedOut: true }, [Q.clearCookie('ADMIN')]);
}

// ───────────────────────────────────────────────────────────────────
//  Projections — only ever send the participant what they may see.
// ───────────────────────────────────────────────────────────────────
function publicIdentity(i) {
  if (!i) return null;
  return {
    provider: i.provider,
    email: i.email,
    displayName: i.display_name,
    picture: i.picture_url,
  };
}

function publicRegistration(r) {
  if (!r) return null;
  return {
    regCode: r.reg_code,
    employeeId: r.employee_id,
    employeeName: r.employee_name,
    designation: r.designation,
    office: r.office,
    status: r.status,
    rosterVerified: r.roster_verified,
    remarks: r.remarks,
    createdAtMs: r.created_at_ms,
  };
}
