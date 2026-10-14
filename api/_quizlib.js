// api/_quizlib.js — shared server-side library for the Quiz module.
//
// Filenames under api/ that begin with an underscore are NOT routed by Vercel,
// so this file is a plain module required by the api/quiz-*.js functions. Like
// the rest of this repo it has ZERO npm dependencies: only Node built-ins and
// the global fetch available on the Vercel Node runtime.
//
// What lives here
//   • Supabase REST access using the SERVICE-ROLE key (server only)
//   • Google ID token verification against Google's live JWKS
//   • Session issue / verify / revoke, backed by public.quiz_sessions
//   • Portal (app_users) password verification for quiz administrators
//   • Rate limiting, audit + event logging, request helpers
//
// Required environment variables (set in the Vercel project, never in client JS)
//   SUPABASE_URL                e.g. https://hfzbvqpxraeoqmvhtjto.supabase.co
//   SUPABASE_SERVICE_ROLE_KEY   service_role key — grants full DB access
//   QUIZ_SESSION_SECRET         long random string, signs session cookies
//   GOOGLE_CLIENT_ID            OAuth 2.0 Web client id (the audience we accept)

'use strict';

const crypto = require('crypto');

// ───────────────────────────────────────────────────────────────────
//  Configuration
// ───────────────────────────────────────────────────────────────────
const SUPABASE_URL = process.env.SUPABASE_URL || 'https://hfzbvqpxraeoqmvhtjto.supabase.co';
const SERVICE_KEY  = process.env.SUPABASE_SERVICE_ROLE_KEY || '';
const SESSION_SECRET = process.env.QUIZ_SESSION_SECRET || '';
const GOOGLE_CLIENT_ID = process.env.GOOGLE_CLIENT_ID || '';

const REST = SUPABASE_URL.replace(/\/+$/, '') + '/rest/v1';

const PARTICIPANT_COOKIE = 'quiz_ps';
const ADMIN_COOKIE       = 'quiz_as';
const PARTICIPANT_TTL_MS = 12 * 60 * 60 * 1000;   // 12 hours
const ADMIN_TTL_MS       = 8 * 60 * 60 * 1000;    // 8 hours

// A configuration problem must fail loudly at the edge, not silently mis-behave
// halfway through an examination.
function configError() {
  const missing = [];
  if (!SERVICE_KEY)    missing.push('SUPABASE_SERVICE_ROLE_KEY');
  if (!SESSION_SECRET) missing.push('QUIZ_SESSION_SECRET');
  return missing.length
    ? 'Quiz backend is not configured — missing ' + missing.join(', ') +
      ' in the Vercel project environment.'
    : null;
}

// ───────────────────────────────────────────────────────────────────
//  Supabase REST (service role — bypasses RLS)
// ───────────────────────────────────────────────────────────────────
function sbHeaders(extra) {
  return Object.assign({
    apikey: SERVICE_KEY,
    Authorization: 'Bearer ' + SERVICE_KEY,
    'Content-Type': 'application/json',
    Accept: 'application/json',
  }, extra || {});
}

async function sbSelect(table, query) {
  const res = await fetch(`${REST}/${table}${query ? '?' + query : ''}`, { headers: sbHeaders() });
  if (!res.ok) throw new DbError(`select ${table}`, res.status, await res.text());
  return res.json();
}

// Single row or null. Avoids the caller having to index [0] everywhere.
async function sbOne(table, query) {
  const rows = await sbSelect(table, query + '&limit=1');
  return rows && rows.length ? rows[0] : null;
}

async function sbInsert(table, rows, opts) {
  const o = opts || {};
  const prefer = [o.returning === false ? 'return=minimal' : 'return=representation'];
  if (o.onConflict) prefer.push('resolution=merge-duplicates');
  const q = o.onConflict ? '?on_conflict=' + encodeURIComponent(o.onConflict) : '';
  const res = await fetch(`${REST}/${table}${q}`, {
    method: 'POST',
    headers: sbHeaders({ Prefer: prefer.join(',') }),
    body: JSON.stringify(Array.isArray(rows) ? rows : [rows]),
  });
  if (!res.ok) throw new DbError(`insert ${table}`, res.status, await res.text());
  return o.returning === false ? true : res.json();
}

async function sbUpdate(table, filter, patch, opts) {
  const o = opts || {};
  const res = await fetch(`${REST}/${table}?${filter}`, {
    method: 'PATCH',
    headers: sbHeaders({ Prefer: o.returning === false ? 'return=minimal' : 'return=representation' }),
    body: JSON.stringify(patch),
  });
  if (!res.ok) throw new DbError(`update ${table}`, res.status, await res.text());
  return o.returning === false ? true : res.json();
}

async function sbDelete(table, filter) {
  const res = await fetch(`${REST}/${table}?${filter}`, {
    method: 'DELETE',
    headers: sbHeaders({ Prefer: 'return=minimal' }),
  });
  if (!res.ok) throw new DbError(`delete ${table}`, res.status, await res.text());
  return true;
}

async function sbCount(table, filter) {
  const q = 'select=*&limit=1' + (filter ? '&' + filter : '');
  const res = await fetch(`${REST}/${table}?${q}`, {
    headers: sbHeaders({ Prefer: 'count=exact', 'Range-Unit': 'items', Range: '0-0' }),
  });
  if (!res.ok) throw new DbError(`count ${table}`, res.status, await res.text());
  const m = (res.headers.get('content-range') || '').match(/\/(\d+)\s*$/);
  return m ? +m[1] : 0;
}

class DbError extends Error {
  constructor(op, status, body) {
    super(`${op}: ${status} ${String(body).slice(0, 400)}`);
    this.name = 'DbError';
    this.status = status;
  }
}

// ───────────────────────────────────────────────────────────────────
//  Schema capability probe
//
//  A deployment and a database migration cannot land at the same instant.
//  Between them the code is ahead of the schema, and a column it writes does
//  not exist yet — which, unguarded, means a participant who submits in that
//  window has their attempt closed but no result written. That is not an
//  acceptable failure mode for an examination.
//
//  So the features that depend on migration_v22 ask first, once per warm
//  lambda, and degrade rather than fail. The answer is cached per table; a
//  false is re-probed after a short interval so the module heals itself as
//  soon as the migration is run, with no redeploy.
// ───────────────────────────────────────────────────────────────────
function isMissingColumn(err) {
  return err instanceof DbError && err.status === 400 &&
    /does not exist|42703|PGRST204|could not find .* column/i.test(err.message);
}

const _cols = {};                       // table -> { ready, at }
const COLS_RETRY_MS = 30 * 1000;        // how soon to re-check a "not yet"

async function columnsReady(table) {
  const hit = _cols[table];
  const now = Date.now();
  if (hit && (hit.ready || now - hit.at < COLS_RETRY_MS)) return hit.ready;
  try {
    await sbSelect(table, 'select=category,unit&limit=1');
    _cols[table] = { ready: true, at: now };
  } catch (e) {
    if (!isMissingColumn(e)) throw e;    // a real outage must not read as "not migrated"
    _cols[table] = { ready: false, at: now };
  }
  return _cols[table].ready;
}

// Drop the category/unit keys from a row when the schema cannot hold them yet.
// The rest of the row — the marks, which are what matter — still writes.
function withoutCategory(row) {
  const out = Object.assign({}, row);
  delete out.category;
  delete out.unit;
  return out;
}

// PostgREST filter value escaping. Values containing commas, dots or parentheses
// break the filter grammar unless quoted.
function eq(value) {
  return 'eq.' + encodeURIComponent(String(value));
}

// ───────────────────────────────────────────────────────────────────
//  Settings cache (per warm lambda invocation)
// ───────────────────────────────────────────────────────────────────
let _settingsCache = null;
let _settingsAt = 0;
const SETTINGS_TTL = 20 * 1000;

async function getSettings(force) {
  const now = Date.now();
  if (!force && _settingsCache && now - _settingsAt < SETTINGS_TTL) return _settingsCache;
  const rows = await sbSelect('quiz_settings', 'select=key,value');
  const out = {};
  for (const r of rows || []) out[r.key] = r.value;
  _settingsCache = out;
  _settingsAt = now;
  return out;
}

async function setSetting(key, value, actor) {
  await sbInsert('quiz_settings',
    { key, value, updated_by: actor || null, updated_at_ms: Date.now() },
    { onConflict: 'key', returning: false });
  _settingsCache = null;
}

// ───────────────────────────────────────────────────────────────────
//  Google ID token verification
//
//  We verify the token ourselves rather than calling Google's tokeninfo
//  endpoint: no extra round trip per request, and no dependence on a
//  deprecated debugging endpoint. Checks performed, all of them required:
//    signature (RS256 against the live JWKS), iss, aud, exp, iat, nonce,
//  and email_verified before the email is ever trusted.
// ───────────────────────────────────────────────────────────────────
const JWKS_URL = 'https://www.googleapis.com/oauth2/v3/certs';
let _jwks = null;
let _jwksAt = 0;
const JWKS_TTL = 60 * 60 * 1000;

async function googleJwks(force) {
  const now = Date.now();
  if (!force && _jwks && now - _jwksAt < JWKS_TTL) return _jwks;
  const res = await fetch(JWKS_URL);
  if (!res.ok) throw new Error('Could not fetch Google signing keys (' + res.status + ')');
  const body = await res.json();
  _jwks = body.keys || [];
  _jwksAt = now;
  return _jwks;
}

function b64urlToBuf(s) {
  return Buffer.from(String(s).replace(/-/g, '+').replace(/_/g, '/'), 'base64');
}

async function verifyGoogleIdToken(idToken, expectedNonce) {
  if (!GOOGLE_CLIENT_ID) {
    throw new AuthError('Google Sign-In is not configured — GOOGLE_CLIENT_ID is missing in the Vercel environment.', 503);
  }
  const parts = String(idToken || '').split('.');
  if (parts.length !== 3) throw new AuthError('Malformed Google token.', 401);

  let header, payload;
  try {
    header  = JSON.parse(b64urlToBuf(parts[0]).toString('utf8'));
    payload = JSON.parse(b64urlToBuf(parts[1]).toString('utf8'));
  } catch (_) {
    throw new AuthError('Malformed Google token.', 401);
  }
  if (header.alg !== 'RS256') throw new AuthError('Unsupported token algorithm.', 401);

  // Signature. A rotated key id is retried once against a freshly fetched JWKS.
  let keys = await googleJwks(false);
  let jwk = keys.find(k => k.kid === header.kid);
  if (!jwk) {
    keys = await googleJwks(true);
    jwk = keys.find(k => k.kid === header.kid);
  }
  if (!jwk) throw new AuthError('Unknown Google signing key.', 401);

  const pub = crypto.createPublicKey({ key: jwk, format: 'jwk' });
  const ok = crypto.verify('RSA-SHA256',
    Buffer.from(parts[0] + '.' + parts[1], 'utf8'), pub, b64urlToBuf(parts[2]));
  if (!ok) throw new AuthError('Google token signature is invalid.', 401);

  // Claims.
  const iss = payload.iss;
  if (iss !== 'accounts.google.com' && iss !== 'https://accounts.google.com') {
    throw new AuthError('Unexpected token issuer.', 401);
  }
  const aud = Array.isArray(payload.aud) ? payload.aud : [payload.aud];
  if (!aud.includes(GOOGLE_CLIENT_ID)) throw new AuthError('Token was not issued for this application.', 401);

  const nowSec = Math.floor(Date.now() / 1000);
  const SKEW = 120;
  if (!payload.exp || payload.exp + SKEW < nowSec) throw new AuthError('Google sign-in has expired — please try again.', 401);
  if (payload.iat && payload.iat - SKEW > nowSec)   throw new AuthError('Google token is not yet valid.', 401);
  if (!payload.sub) throw new AuthError('Google token carries no subject.', 401);

  // Replay protection: the nonce we planted in the sign-in request must come back.
  if (expectedNonce) {
    if (!payload.nonce || payload.nonce !== expectedNonce) {
      throw new AuthError('Sign-in could not be verified — please try again.', 401);
    }
  }

  const emailVerified = payload.email_verified === true || payload.email_verified === 'true';
  return {
    sub: String(payload.sub),
    email: payload.email ? String(payload.email).toLowerCase() : null,
    emailVerified,
    name: payload.name || null,
    picture: payload.picture || null,
  };
}

// ───────────────────────────────────────────────────────────────────
//  Sessions
//
//  The cookie carries  <random-token>.<hmac>  — the HMAC makes a forged or
//  tampered cookie cheap to reject without a database round trip, and the
//  database row (keyed by SHA-256 of the token) is what actually authorises,
//  so a session can be revoked server-side at any moment.
// ───────────────────────────────────────────────────────────────────
function hmac(value) {
  return crypto.createHmac('sha256', SESSION_SECRET).update(value).digest('base64url');
}
function sha256(value) {
  return crypto.createHash('sha256').update(value).digest('hex');
}

function signToken(raw) { return raw + '.' + hmac(raw); }

function unsignToken(signed) {
  const s = String(signed || '');
  const i = s.lastIndexOf('.');
  if (i < 1) return null;
  const raw = s.slice(0, i), mac = s.slice(i + 1);
  const expect = hmac(raw);
  // Lengths are fixed here, but compare via digests so nothing leaks either way.
  const a = crypto.createHash('sha256').update(mac).digest();
  const b = crypto.createHash('sha256').update(expect).digest();
  if (!crypto.timingSafeEqual(a, b)) return null;
  return raw;
}

async function createSession(req, kind, fields) {
  const raw = crypto.randomBytes(32).toString('base64url');
  const now = Date.now();
  const ttl = kind === 'ADMIN' ? ADMIN_TTL_MS : PARTICIPANT_TTL_MS;
  await sbInsert('quiz_sessions', {
    token_hash: sha256(raw),
    kind,
    identity_id: fields.identityId || null,
    username: fields.username || null,
    quiz_role: fields.quizRole || null,
    created_ms: now,
    expires_ms: now + ttl,
    last_seen_ms: now,
    ip: clientIp(req),
    user_agent: (req.headers['user-agent'] || '').slice(0, 300),
  }, { returning: false });
  return { token: signToken(raw), expiresMs: now + ttl };
}

async function readSession(req, kind) {
  const cookies = parseCookies(req);
  const signed = cookies[kind === 'ADMIN' ? ADMIN_COOKIE : PARTICIPANT_COOKIE];
  const raw = unsignToken(signed);
  if (!raw) return null;
  const row = await sbOne('quiz_sessions',
    'select=*&token_hash=' + eq(sha256(raw)) + '&kind=' + eq(kind));
  if (!row || row.revoked) return null;
  if (row.expires_ms < Date.now()) return null;
  row._raw = raw;
  return row;
}

async function revokeSession(req, kind) {
  const cookies = parseCookies(req);
  const raw = unsignToken(cookies[kind === 'ADMIN' ? ADMIN_COOKIE : PARTICIPANT_COOKIE]);
  if (!raw) return;
  await sbUpdate('quiz_sessions', 'token_hash=' + eq(sha256(raw)),
    { revoked: true }, { returning: false });
}

function sessionCookie(kind, token, maxAgeMs) {
  const name = kind === 'ADMIN' ? ADMIN_COOKIE : PARTICIPANT_COOKIE;
  const parts = [
    `${name}=${token}`,
    'Path=/',
    'HttpOnly',
    'Secure',
    'SameSite=Lax',
    'Max-Age=' + Math.floor((maxAgeMs || 0) / 1000),
  ];
  return parts.join('; ');
}

function clearCookie(kind) {
  const name = kind === 'ADMIN' ? ADMIN_COOKIE : PARTICIPANT_COOKIE;
  return `${name}=; Path=/; HttpOnly; Secure; SameSite=Lax; Max-Age=0`;
}

function parseCookies(req) {
  const out = {};
  const raw = req.headers.cookie || '';
  for (const bit of raw.split(';')) {
    const i = bit.indexOf('=');
    if (i < 0) continue;
    out[bit.slice(0, i).trim()] = decodeURIComponent(bit.slice(i + 1).trim());
  }
  return out;
}

// ───────────────────────────────────────────────────────────────────
//  Quiz administrator authentication
//
//  Reuses the existing portal credentials in app_users, but verifies the
//  PBKDF2 hash HERE, on the server. The portal's own login verifies in the
//  browser, which is fine for pages holding no secrets — it is not good
//  enough to guard an answer key, so the quiz admin API re-authenticates.
//  Parameters mirror Leave Orders/login.html exactly: PBKDF2-HMAC-SHA256,
//  256-bit output, base64 salt, iteration count stored per user.
// ───────────────────────────────────────────────────────────────────
async function verifyPortalPassword(username, password) {
  const u = String(username || '').trim().toLowerCase();
  if (!u || !password) return null;
  const row = await sbOne('app_users',
    'select=username,password_hash,salt,iterations,display_name,role,active&username=' + eq(u));
  if (!row || !row.active) return null;

  const expected = Buffer.from(String(row.password_hash), 'base64');
  const salt = Buffer.from(String(row.salt), 'base64');
  const derived = crypto.pbkdf2Sync(String(password), salt, row.iterations | 0, 32, 'sha256');
  if (derived.length !== expected.length) return null;
  if (!crypto.timingSafeEqual(derived, expected)) return null;

  return { username: row.username, displayName: row.display_name, portalRole: row.role };
}

// A portal login does not by itself confer quiz privileges. An explicit grant
// in quiz_admin_roles wins; otherwise the configurable role_map translates the
// portal role; anything unmapped gets nothing.
async function resolveQuizRole(portalUser) {
  const grant = await sbOne('quiz_admin_roles',
    'select=quiz_role&username=' + eq(portalUser.username));
  if (grant) return grant.quiz_role;
  const settings = await getSettings();
  const map = settings.role_map || {};
  return map[String(portalUser.portalRole || '').toUpperCase()] || 'NONE';
}

const ROLE_RANK = { NONE: 0, QUIZ_VIEWER: 1, QUIZ_EDITOR: 2, QUIZ_ADMIN: 3 };

function roleAtLeast(role, needed) {
  return (ROLE_RANK[role] || 0) >= (ROLE_RANK[needed] || 99);
}

// Guard for admin endpoints. Throws AuthError, so a handler can simply await it.
async function requireAdmin(req, needed) {
  const s = await readSession(req, 'ADMIN');
  if (!s) throw new AuthError('Please sign in to the Quiz administration module.', 401);
  if (!roleAtLeast(s.quiz_role, needed || 'QUIZ_VIEWER')) {
    throw new AuthError('Your quiz role does not permit this action.', 403);
  }
  return { username: s.username, role: s.quiz_role, session: s };
}

// Guard for participant endpoints.
async function requireParticipant(req) {
  const s = await readSession(req, 'PARTICIPANT');
  if (!s) throw new AuthError('Please sign in to continue.', 401);
  const identity = await sbOne('quiz_identities', 'select=*&id=' + eq(s.identity_id));
  if (!identity) throw new AuthError('Your sign-in is no longer valid.', 401);
  const registration = await sbOne('quiz_registrations',
    'select=*&identity_id=' + eq(s.identity_id));
  return { session: s, identity, registration };
}

class AuthError extends Error {
  constructor(message, status) {
    super(message);
    this.name = 'AuthError';
    this.status = status || 401;
  }
}

class AppError extends Error {
  constructor(message, status, code) {
    super(message);
    this.name = 'AppError';
    this.status = status || 400;
    this.code = code || null;
  }
}

// ───────────────────────────────────────────────────────────────────
//  Rate limiting
//
//  Fixed window, one row per bucket. Employee ID lookup is the endpoint that
//  matters: without a limit, 782 sequential ids would dump the whole roster.
// ───────────────────────────────────────────────────────────────────
async function rateLimit(key, maxHits, windowMs) {
  const now = Date.now();
  const bucket = `${key}:${Math.floor(now / windowMs)}`;
  const hits = await sbRpc('quiz_rate_hit', {
    p_bucket: bucket,
    p_expires_ms: now + windowMs * 2,
  });
  if (Number(hits) > maxHits) {
    throw new AppError('Too many attempts. Please wait a minute and try again.', 429, 'RATE_LIMIT');
  }
  return Number(hits);
}

async function sbRpc(fn, args) {
  const res = await fetch(`${REST}/rpc/${fn}`, {
    method: 'POST',
    headers: sbHeaders(),
    body: JSON.stringify(args || {}),
  });
  if (!res.ok) throw new DbError(`rpc ${fn}`, res.status, await res.text());
  const text = await res.text();
  if (!text) return null;
  try { return JSON.parse(text); } catch (_) { return text; }
}

// ───────────────────────────────────────────────────────────────────
//  Logging
// ───────────────────────────────────────────────────────────────────
async function logEvent(req, row) {
  try {
    await sbInsert('quiz_events', Object.assign({
      created_ms: Date.now(),
      ip: clientIp(req),
      user_agent: (req.headers['user-agent'] || '').slice(0, 300),
      severity: 'INFO',
    }, row), { returning: false });
  } catch (e) {
    // Never let an audit write failure break the participant's examination.
    console.error('[quiz] logEvent failed:', e.message);
  }
}

async function logAudit(req, actor, action, entity, entityId, before, after) {
  try {
    await sbInsert('quiz_audit', {
      actor: actor && actor.username ? actor.username : String(actor || 'system'),
      actor_role: actor && actor.role ? actor.role : null,
      action, entity: entity || null, entity_id: entityId ? String(entityId) : null,
      before_json: before || null, after_json: after || null,
      ip: clientIp(req), created_ms: Date.now(),
    }, { returning: false });
  } catch (e) {
    console.error('[quiz] logAudit failed:', e.message);
  }
}

// ───────────────────────────────────────────────────────────────────
//  Request / response helpers
// ───────────────────────────────────────────────────────────────────
function clientIp(req) {
  const xf = req.headers['x-forwarded-for'];
  if (xf) return String(xf).split(',')[0].trim().slice(0, 60);
  return (req.socket && req.socket.remoteAddress) || null;
}

async function readJsonBody(req) {
  if (req.body && typeof req.body === 'object') return req.body;
  const chunks = [];
  let size = 0;
  for await (const c of req) {
    size += c.length;
    if (size > 6 * 1024 * 1024) throw new AppError('Request body is too large.', 413);
    chunks.push(c);
  }
  if (!chunks.length) return {};
  const text = Buffer.concat(chunks).toString('utf8');
  try { return JSON.parse(text); }
  catch (_) { throw new AppError('Request body is not valid JSON.', 400); }
}

function json(res, status, payload, cookies) {
  if (cookies && cookies.length) res.setHeader('Set-Cookie', cookies);
  res.statusCode = status;
  res.setHeader('Content-Type', 'application/json; charset=utf-8');
  res.setHeader('Cache-Control', 'no-store, no-cache, must-revalidate, private');
  res.setHeader('X-Content-Type-Options', 'nosniff');
  res.setHeader('Referrer-Policy', 'same-origin');
  res.end(JSON.stringify(payload));
}

function ok(res, payload, cookies) { json(res, 200, Object.assign({ ok: true }, payload || {}), cookies); }

function fail(res, err) {
  const status = err && err.status ? err.status : 500;
  const isOurs = err instanceof AuthError || err instanceof AppError;
  if (!isOurs) console.error('[quiz] unhandled:', err && err.stack ? err.stack : err);
  json(res, status, {
    ok: false,
    error: isOurs ? err.message : 'Something went wrong on the server. Please try again.',
    code: (err && err.code) || null,
  });
}

// State-changing requests must carry this header. A cross-site form post
// cannot set a custom header, which together with SameSite=Lax cookies is
// sufficient CSRF protection for a same-origin JSON API.
function requireJsonRequest(req) {
  if (req.method === 'GET' || req.method === 'HEAD') return;
  if (String(req.headers['x-quiz-request'] || '') !== '1') {
    throw new AppError('Invalid request.', 400, 'BAD_REQUEST');
  }
}

// Unbiased Fisher-Yates using rejection-sampled random integers. Math.random
// would be fine visually, but question order is an integrity property.
function secureShuffle(arr) {
  const a = arr.slice();
  for (let i = a.length - 1; i > 0; i--) {
    const j = randomInt(i + 1);
    const t = a[i]; a[i] = a[j]; a[j] = t;
  }
  return a;
}

function randomInt(maxExclusive) {
  if (maxExclusive <= 1) return 0;
  const range = 0x100000000;
  const limit = range - (range % maxExclusive);
  let v;
  do { v = crypto.randomBytes(4).readUInt32BE(0); } while (v >= limit);
  return v % maxExclusive;
}

// Scoring. The single place the marking formula lives, so the live submit path
// (quiz-participant) and the expiry sweep and recalculation (quiz-admin) cannot
// drift apart and score the same attempt two different ways.
//
//   Total = (correct x positive) - (wrong x negative) + (unanswered x unanswered)
//
// The policy comes off the ATTEMPT, not from current settings, so a settings
// change can never retroactively rescore someone. `answers` only counts rows
// that carry a chosen option: a served-but-skipped question is unanswered.
function computeScore(attempt, answers) {
  let correct = 0, wrong = 0;
  for (const a of answers || []) {
    if (!a.chosen_slot) continue;
    if (a.is_correct) correct++; else wrong++;
  }
  const total = attempt.question_count;
  const attempted = correct + wrong;
  const unanswered = Math.max(0, total - attempted);

  const pos = Number(attempt.positive_marks);
  const neg = Number(attempt.negative_marks);
  const unm = Number(attempt.unanswered_marks || 0);

  const positiveEarned = correct * pos;
  const negativeDeducted = wrong * neg;
  const score = positiveEarned - negativeDeducted + unanswered * unm;
  const maxMarks = total * pos;

  return {
    attempted, correct, wrong, unanswered,
    positive_earned: positiveEarned,
    negative_deducted: negativeDeducted,
    score,
    max_marks: maxMarks,
    percentage: maxMarks > 0 ? Math.round((score / maxMarks) * 10000) / 100 : 0,
  };
}

// ───────────────────────────────────────────────────────────────────
//  Staff category and unit / sub division
//
//  Prizes are awarded separately to Departmental officials and to GDS, so the
//  category is not decoration — it decides which of the two prize lists an
//  employee competes in. The roster spells it in whatever way the source
//  system did ("GDS", "Gramin Dak Sevak", "DS", "Departmental"), so it is
//  normalised ONCE, here, and every other file imports this function. An
//  unrecognised value deliberately becomes null rather than a guess: it then
//  shows as "Unspecified" and is corrected by hand, instead of silently
//  competing in the wrong category.
// ───────────────────────────────────────────────────────────────────
const CATEGORIES = ['DEPARTMENTAL', 'GDS'];
const CATEGORY_LABELS = { DEPARTMENTAL: 'Departmental', GDS: 'GDS' };

function normCategory(value) {
  const k = String(value == null ? '' : value).toLowerCase().replace(/[^a-z]/g, '');
  if (!k) return null;
  if (k.includes('gds') || k.includes('gramindaksevak') || k.includes('extradepartmental')) return 'GDS';
  if (k.includes('departmental') || ['ds', 'dept', 'deptl', 'regular'].includes(k)) return 'DEPARTMENTAL';
  return null;
}

function categoryLabel(code) {
  return CATEGORY_LABELS[String(code || '').toUpperCase()] || 'Unspecified';
}

// Unit / Sub Division. The roster is mostly consistent, but the Divisional
// Office appears as both "DO Karur" and "KARUR DO", which would otherwise
// split one unit into two rows on every unit-wise count. Known spellings are
// mapped to a canonical label; anything unknown keeps the words the roster
// used and is only tidied for whitespace, so a new unit needs no code change.
const UNIT_ALIASES = {
  'do karur': 'DO Karur',
  'karur do': 'DO Karur',
  'divisional office karur': 'DO Karur',
  'divisional office': 'DO Karur',
  'karur ho': 'Karur H.O',
  'karur h o': 'Karur H.O',
  'kulittalai ho': 'Kulittalai H.O',
  'kulittalai h o': 'Kulittalai H.O',
  'kulithalai ho': 'Kulittalai H.O',
  'kulithalai h o': 'Kulittalai H.O',
  'karur sub division': 'Karur Sub Division',
  'karur subdivision': 'Karur Sub Division',
  'kulithalai sub division': 'Kulithalai Sub Division',
  'kulithalai subdivision': 'Kulithalai Sub Division',
  'kulittalai sub division': 'Kulithalai Sub Division',
  'kulittalai subdivision': 'Kulithalai Sub Division',
  'manaparai sub division': 'Manaparai Sub Division',
  'manaparai subdivision': 'Manaparai Sub Division',
  'aravakurichi sub division': 'Aravakurichi Sub Division',
  'aravakurichi subdivision': 'Aravakurichi Sub Division',
};

function normUnit(value) {
  const tidy = String(value == null ? '' : value).replace(/\s+/g, ' ').trim();
  if (!tidy) return null;
  const key = tidy.toLowerCase().replace(/[._-]/g, ' ').replace(/\s+/g, ' ').trim();
  return UNIT_ALIASES[key] || tidy;
}

// Normalised digest of a question, for duplicate detection on import.
function questionHash(text) {
  const norm = String(text || '').toLowerCase().replace(/\s+/g, ' ')
    .replace(/[^a-z0-9 ]/g, '').trim();
  return crypto.createHash('sha256').update(norm).digest('hex').slice(0, 32);
}

// IST is the published examination timezone. Server stores epoch ms; this is
// only for human-readable strings in exports and logs.
function istString(ms) {
  if (!ms) return '';
  return new Date(ms).toLocaleString('en-IN', {
    timeZone: 'Asia/Kolkata', day: '2-digit', month: 'short', year: 'numeric',
    hour: '2-digit', minute: '2-digit', hour12: true,
  });
}

module.exports = {
  // config
  SUPABASE_URL, GOOGLE_CLIENT_ID, configError,
  PARTICIPANT_COOKIE, ADMIN_COOKIE, PARTICIPANT_TTL_MS, ADMIN_TTL_MS,
  // db
  sbSelect, sbOne, sbInsert, sbUpdate, sbDelete, sbCount, sbRpc, eq, DbError,
  getSettings, setSetting,
  // auth
  verifyGoogleIdToken, createSession, readSession, revokeSession,
  sessionCookie, clearCookie, parseCookies,
  verifyPortalPassword, resolveQuizRole, roleAtLeast, requireAdmin, requireParticipant,
  AuthError, AppError, ROLE_RANK,
  // infra
  rateLimit, logEvent, logAudit,
  clientIp, readJsonBody, json, ok, fail, requireJsonRequest,
  secureShuffle, randomInt, questionHash, istString, sha256, computeScore,
  // category / unit
  CATEGORIES, normCategory, categoryLabel, normUnit,
  // schema capability
  isMissingColumn, columnsReady, withoutCategory,
};
