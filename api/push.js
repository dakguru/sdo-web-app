// api/push.js — Web Push for the installable Karur SDO app.
//
// This is the iPhone's half of the Android app's notification pipeline. Android
// registers an FCM token in app_push_tokens and an Edge Function pushes to it;
// an installed iOS PWA instead registers a Web Push subscription here, and this
// function encrypts and delivers the same two notifications:
//
//     chat       new message in the staff group chat   (Notifications.CHANNEL_CHAT)
//     programme  new Mail Overseer tour programme      (Notifications.CHANNEL_PROGRAMME)
//
// Dispatch is by ?action=, so the whole feature costs one Vercel function.
//
//   GET  ?action=public-key    the VAPID public key, for pushManager.subscribe
//   POST  action=subscribe     store this device's subscription
//   POST  action=unsubscribe   forget a subscription
//   POST  action=notify        fan out a notification about a row that was just written
//   POST  action=test          send a test notification to one subscription
//
// Like the rest of api/, ZERO npm dependencies: Node built-ins and global fetch
// only. Web Push is implemented here directly — VAPID (RFC 8292) request
// signing and aes128gcm (RFC 8291) payload encryption.
//
// Required environment variables (Vercel project settings, never in client JS)
//   SUPABASE_URL
//   SUPABASE_SERVICE_ROLE_KEY
//   VAPID_PUBLIC_KEY     base64url, 65-byte uncompressed P-256 point
//   VAPID_PRIVATE_KEY    base64url, 32-byte P-256 private scalar
//   VAPID_SUBJECT        mailto: or https: contact for the push service

'use strict';

const crypto = require('crypto');

const SUPABASE_URL = process.env.SUPABASE_URL || 'https://hfzbvqpxraeoqmvhtjto.supabase.co';
const SERVICE_KEY = process.env.SUPABASE_SERVICE_ROLE_KEY || '';
const VAPID_PUBLIC = process.env.VAPID_PUBLIC_KEY || '';
const VAPID_PRIVATE = process.env.VAPID_PRIVATE_KEY || '';
const VAPID_SUBJECT = process.env.VAPID_SUBJECT || 'mailto:sdokarur@indiapost.gov.in';

const REST = SUPABASE_URL.replace(/\/+$/, '') + '/rest/v1';
const SUBS_TABLE = 'app_web_push_subs';

// A row older than this is history, not news — refuse to notify about it, so a
// replayed id can't resurrect an old message as a fresh alert.
const MAX_ROW_AGE_MS = 10 * 60 * 1000;

// ───────────────────────────────────────────────────────────────────
//  base64url
// ───────────────────────────────────────────────────────────────────
function b64uToBuf(s) {
  return Buffer.from(String(s).replace(/-/g, '+').replace(/_/g, '/'), 'base64');
}
function bufToB64u(b) {
  return Buffer.from(b).toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

// ───────────────────────────────────────────────────────────────────
//  Supabase (service role — server only)
// ───────────────────────────────────────────────────────────────────
function sbHeaders(extra) {
  return Object.assign(
    {
      apikey: SERVICE_KEY,
      Authorization: 'Bearer ' + SERVICE_KEY,
      'Content-Type': 'application/json',
      Accept: 'application/json',
    },
    extra || {}
  );
}

async function sbSelect(table, query) {
  const res = await fetch(`${REST}/${table}?${query}`, { headers: sbHeaders() });
  if (!res.ok) throw new Error(`select ${table}: ${res.status} ${await res.text()}`);
  return res.json();
}

async function sbUpsert(table, row) {
  const res = await fetch(`${REST}/${table}`, {
    method: 'POST',
    headers: sbHeaders({ Prefer: 'resolution=merge-duplicates,return=minimal' }),
    body: JSON.stringify([row]),
  });
  if (!res.ok) throw new Error(`upsert ${table}: ${res.status} ${await res.text()}`);
}

async function sbDelete(table, filter) {
  await fetch(`${REST}/${table}?${filter}`, {
    method: 'DELETE',
    headers: sbHeaders({ Prefer: 'return=minimal' }),
  }).catch(() => {});
}

// ───────────────────────────────────────────────────────────────────
//  VAPID — signed request identification (RFC 8292)
// ───────────────────────────────────────────────────────────────────

/** The raw 32-byte scalar + 65-byte point VAPID keys, as a signing KeyObject. */
function vapidPrivateKey() {
  const pub = b64uToBuf(VAPID_PUBLIC);
  if (pub.length !== 65 || pub[0] !== 0x04) {
    throw new Error('VAPID_PUBLIC_KEY must be a 65-byte uncompressed P-256 point');
  }
  return crypto.createPrivateKey({
    format: 'jwk',
    key: {
      kty: 'EC',
      crv: 'P-256',
      d: bufToB64u(b64uToBuf(VAPID_PRIVATE)),
      x: bufToB64u(pub.subarray(1, 33)),
      y: bufToB64u(pub.subarray(33, 65)),
    },
  });
}

/** `Authorization: vapid t=<jwt>, k=<public key>` for one push-service origin. */
function vapidHeader(endpoint) {
  const audience = new URL(endpoint).origin;
  const header = bufToB64u(Buffer.from(JSON.stringify({ typ: 'JWT', alg: 'ES256' })));
  const body = bufToB64u(
    Buffer.from(
      JSON.stringify({
        aud: audience,
        exp: Math.floor(Date.now() / 1000) + 12 * 60 * 60,
        sub: VAPID_SUBJECT,
      })
    )
  );
  const signingInput = `${header}.${body}`;
  // ieee-p1363 gives the raw r||s pair JWS wants, instead of OpenSSL's DER.
  const sig = crypto.sign('sha256', Buffer.from(signingInput), {
    key: vapidPrivateKey(),
    dsaEncoding: 'ieee-p1363',
  });
  return `vapid t=${signingInput}.${bufToB64u(sig)}, k=${VAPID_PUBLIC}`;
}

// ───────────────────────────────────────────────────────────────────
//  Payload encryption — aes128gcm (RFC 8291)
// ───────────────────────────────────────────────────────────────────
function hkdfExtract(salt, ikm) {
  return crypto.createHmac('sha256', salt).update(ikm).digest();
}
function hkdfExpand(prk, info, length) {
  const out = crypto.createHmac('sha256', prk).update(Buffer.concat([info, Buffer.from([1])])).digest();
  return out.subarray(0, length);
}

function encryptPayload(plaintext, uaPublicB64u, authSecretB64u) {
  const uaPublic = b64uToBuf(uaPublicB64u);
  const authSecret = b64uToBuf(authSecretB64u);

  const ecdh = crypto.createECDH('prime256v1');
  ecdh.generateKeys();
  const asPublic = ecdh.getPublicKey(); // 65-byte uncompressed point
  const sharedSecret = ecdh.computeSecret(uaPublic);
  const salt = crypto.randomBytes(16);

  // IKM is derived from the shared secret and *both* public keys, which binds
  // the ciphertext to this exact pair of endpoints.
  const prkKey = hkdfExtract(authSecret, sharedSecret);
  const keyInfo = Buffer.concat([Buffer.from('WebPush: info\0'), uaPublic, asPublic]);
  const ikm = hkdfExpand(prkKey, keyInfo, 32);

  const prk = hkdfExtract(salt, ikm);
  const cek = hkdfExpand(prk, Buffer.from('Content-Encoding: aes128gcm\0'), 16);
  const nonce = hkdfExpand(prk, Buffer.from('Content-Encoding: nonce\0'), 12);

  // 0x02 is the last-record delimiter; there is only ever one record here.
  const padded = Buffer.concat([Buffer.from(plaintext, 'utf8'), Buffer.from([2])]);
  const cipher = crypto.createCipheriv('aes-128-gcm', cek, nonce);
  const body = Buffer.concat([cipher.update(padded), cipher.final(), cipher.getAuthTag()]);

  const recordSize = Buffer.alloc(4);
  recordSize.writeUInt32BE(4096, 0);
  return Buffer.concat([salt, recordSize, Buffer.from([asPublic.length]), asPublic, body]);
}

/**
 * Deliver one notification. Resolves to 'sent', or 'gone' when the push service
 * says the subscription no longer exists (the caller then forgets it).
 */
async function sendPush(sub, payloadObj) {
  const encrypted = encryptPayload(JSON.stringify(payloadObj), sub.p256dh, sub.auth);
  const res = await fetch(sub.endpoint, {
    method: 'POST',
    headers: {
      Authorization: vapidHeader(sub.endpoint),
      'Content-Encoding': 'aes128gcm',
      'Content-Type': 'application/octet-stream',
      'Content-Length': String(encrypted.length),
      TTL: '86400',
      Urgency: 'high',
    },
    body: encrypted,
  });
  if (res.status === 404 || res.status === 410) return 'gone';
  if (!res.ok) throw new Error(`push ${res.status}: ${await res.text()}`);
  return 'sent';
}

// ───────────────────────────────────────────────────────────────────
//  Notification content — the same wording the Android app uses
// ───────────────────────────────────────────────────────────────────
// AppNotifier.beatLabel() — the stored beat is the raw MoBeatSeed constant.
function beatLabel(beat) {
  if (beat === 'MO_I') return 'MO I';
  if (beat === 'MO_II') return 'MO II';
  return beat || 'MO';
}

/** Look the row up server-side, so a caller can never dictate what is pushed. */
async function buildNotification(kind, id) {
  if (kind === 'programme') {
    const rows = await sbSelect(
      'app_programmes',
      `id=eq.${encodeURIComponent(id)}&deleted=eq.false&select=id,beat,date,details,author,created_at&limit=1`
    );
    const p = rows[0];
    if (!p) return null;
    const created = Number(p.created_at) || 0;
    if (created && Date.now() - created > MAX_ROW_AGE_MS) return null;
    return {
      kind: 'programme',
      title: `New ${beatLabel(p.beat)} programme`,
      body: `${p.date} — ${p.details}` + (p.author ? `  (by ${p.author})` : ''),
      url: '/mail-overseers',
      author: p.author || null,
      at: created || Date.now(),
    };
  }

  const rows = await sbSelect(
    'app_messages',
    `id=eq.${encodeURIComponent(id)}&deleted=eq.false&select=id,username,display_name,body,created_at&limit=1`
  );
  const m = rows[0];
  if (!m) return null;
  const created = Number(m.created_at) || 0;
  if (created && Date.now() - created > MAX_ROW_AGE_MS) return null;
  return {
    kind: 'chat',
    title: `New message · ${m.display_name || m.username || 'Karur SDO'}`,
    body: m.body || '',
    url: '/messages',
    author: m.username || null,
    at: created || Date.now(),
  };
}

// ───────────────────────────────────────────────────────────────────
//  HTTP plumbing
// ───────────────────────────────────────────────────────────────────
function json(res, status, obj) {
  res.statusCode = status;
  res.setHeader('Content-Type', 'application/json; charset=utf-8');
  res.setHeader('Cache-Control', 'no-store');
  res.end(JSON.stringify(obj));
}

async function readBody(req) {
  if (req.body && typeof req.body === 'object') return req.body;
  const chunks = [];
  for await (const c of req) chunks.push(c);
  if (!chunks.length) return {};
  try {
    return JSON.parse(Buffer.concat(chunks).toString('utf8'));
  } catch (e) {
    throw new Error('Request body must be JSON.');
  }
}

function configError() {
  const missing = [];
  if (!SERVICE_KEY) missing.push('SUPABASE_SERVICE_ROLE_KEY');
  if (!VAPID_PUBLIC) missing.push('VAPID_PUBLIC_KEY');
  if (!VAPID_PRIVATE) missing.push('VAPID_PRIVATE_KEY');
  return missing.length ? 'Push is not configured — missing ' + missing.join(', ') : null;
}

/** Fan out to every subscription except the person who caused the event. */
async function fanOut(notification, skipUsername) {
  const subs = await sbSelect(SUBS_TABLE, 'select=endpoint,p256dh,auth,username&limit=2000');
  const targets = subs.filter((s) => !skipUsername || s.username !== skipUsername);

  let sent = 0;
  let gone = 0;
  const results = await Promise.allSettled(targets.map((s) => sendPush(s, notification)));
  for (let i = 0; i < results.length; i++) {
    const r = results[i];
    if (r.status === 'fulfilled' && r.value === 'sent') sent++;
    else if (r.status === 'fulfilled' && r.value === 'gone') {
      gone++;
      await sbDelete(SUBS_TABLE, `endpoint=eq.${encodeURIComponent(targets[i].endpoint)}`);
    }
  }
  return { sent, gone, candidates: targets.length };
}

module.exports = async function handler(req, res) {
  const action = String((req.query && req.query.action) || '').toLowerCase();

  try {
    if (action === 'public-key') {
      return json(res, 200, { publicKey: VAPID_PUBLIC || null, configured: !configError() });
    }

    const cfgErr = configError();
    if (cfgErr) return json(res, 503, { error: cfgErr });
    if (req.method !== 'POST') return json(res, 405, { error: 'POST required.' });

    const body = await readBody(req);

    if (action === 'subscribe') {
      const s = body.subscription || {};
      const keys = s.keys || {};
      if (!s.endpoint || !keys.p256dh || !keys.auth) {
        return json(res, 400, { error: 'A complete push subscription is required.' });
      }
      const now = Date.now();
      await sbUpsert(SUBS_TABLE, {
        endpoint: s.endpoint,
        p256dh: keys.p256dh,
        auth: keys.auth,
        username: body.username || null,
        user_agent: String(req.headers['user-agent'] || '').slice(0, 300),
        created_at_ms: now,
        updated_at_ms: now,
      });
      return json(res, 200, { ok: true });
    }

    if (action === 'unsubscribe') {
      if (!body.endpoint) return json(res, 400, { error: 'endpoint is required.' });
      await sbDelete(SUBS_TABLE, `endpoint=eq.${encodeURIComponent(body.endpoint)}`);
      return json(res, 200, { ok: true });
    }

    if (action === 'notify') {
      const kind = body.kind === 'programme' ? 'programme' : 'chat';
      if (!body.id) return json(res, 400, { error: 'id is required.' });

      const notification = await buildNotification(kind, body.id);
      // Nothing to say: the row is gone, or too old to be news.
      if (!notification) return json(res, 200, { ok: true, sent: 0, skipped: 'no-such-row' });

      const result = await fanOut(notification, notification.author || body.from || null);
      return json(res, 200, Object.assign({ ok: true }, result));
    }

    if (action === 'test') {
      if (!body.endpoint) return json(res, 400, { error: 'endpoint is required.' });
      const rows = await sbSelect(
        SUBS_TABLE,
        `endpoint=eq.${encodeURIComponent(body.endpoint)}&select=endpoint,p256dh,auth&limit=1`
      );
      if (!rows[0]) return json(res, 404, { error: 'That subscription is not registered.' });
      const outcome = await sendPush(rows[0], {
        kind: 'chat',
        title: 'Karur SDO',
        body: 'Notifications are working on this iPhone.',
        url: '/profile',
        at: Date.now(),
      });
      if (outcome === 'gone') {
        await sbDelete(SUBS_TABLE, `endpoint=eq.${encodeURIComponent(body.endpoint)}`);
        return json(res, 410, { error: 'This subscription has expired — turn notifications on again.' });
      }
      return json(res, 200, { ok: true });
    }

    return json(res, 400, { error: 'Unknown action.' });
  } catch (err) {
    // Never leak key material or Supabase internals to the client.
    console.error('[push]', action, err && err.message);
    return json(res, 500, { error: 'Notification request failed.' });
  }
};
