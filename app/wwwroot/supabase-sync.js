/*  supabase-sync.js  –  Supabase connector for Karur SDO web app
 *  ─────────────────────────────────────────────────────────────
 *  Provides read/write access to the same Supabase project the Android app
 *  already uses.  Targets the `app_datasets` table for bulk data sync and
 *  individual tables (`app_favorites`, `app_notes`, etc.) for user-layer data.
 *
 *  Usage:
 *    <script src="/supabase-sync.js"></script>
 *    await SB.init();                        // call once on page load
 *    const emps = await SB.getDataset('DS'); // fetch DS dataset
 *    await SB.putDataset('DS', rows, 'web'); // upload new DS dataset
 *
 *  The module exposes a global `SB` object.
 *  ────────────────────────────────────────────────────────────── */

const SB = (() => {
  // ── Supabase credentials (same as Android app's local.properties) ──
  const SUPABASE_URL  = 'https://hfzbvqpxraeoqmvhtjto.supabase.co';
  const SUPABASE_KEY  = 'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImhmemJ2cXB4cmFlb3Ftdmh0anRvIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODQ5NDc4NDUsImV4cCI6MjEwMDUyMzg0NX0.kPcH1_YyWD32syqD2NBKWZpAVRds-SpwPTiXPpi6nIw';
  const REST          = `${SUPABASE_URL}/rest/v1`;

  let _ready = false;

  // ── Low-level PostgREST helpers ──────────────────────────────

  function headers(extra) {
    return Object.assign({
      'apikey':        SUPABASE_KEY,
      'Authorization': `Bearer ${SUPABASE_KEY}`,
      'Content-Type':  'application/json',
      'Accept':        'application/json',
    }, extra || {});
  }

  /** SELECT rows from a table. `query` is appended as PostgREST query string. */
  async function select(table, query) {
    const url = `${REST}/${table}${query ? '?' + query : ''}`;
    const res = await fetch(url, { headers: headers() });
    if (!res.ok) throw new Error(`SB.select ${table}: ${res.status} ${await res.text()}`);
    return res.json();
  }

  /** UPSERT (insert-or-update) rows into a table. */
  async function upsert(table, rows) {
    const url = `${REST}/${table}`;
    const res = await fetch(url, {
      method: 'POST',
      headers: headers({
        'Prefer': 'resolution=merge-duplicates,return=minimal',
      }),
      body: JSON.stringify(Array.isArray(rows) ? rows : [rows]),
    });
    if (!res.ok) throw new Error(`SB.upsert ${table}: ${res.status} ${await res.text()}`);
    return true;
  }

  /** DELETE rows matching a PostgREST filter. */
  async function del(table, filter) {
    const url = `${REST}/${table}?${filter}`;
    const res = await fetch(url, {
      method: 'DELETE',
      headers: headers({ 'Prefer': 'return=minimal' }),
    });
    if (!res.ok) throw new Error(`SB.del ${table}: ${res.status} ${await res.text()}`);
    return true;
  }

  // ── Dataset helpers (app_datasets table) ────────────────────
  // The Android app stores whole-dataset snapshots in `app_datasets`:
  //   type:          'DS' | 'GDS' | 'OUT' | 'TEL' | 'OFFICES' | 'ARR'
  //   payload:       JSON string of the full dataset
  //   count:         number of records
  //   uploaded_by:   username who uploaded
  //   uploaded_at_ms: epoch ms

  /** Fetch a dataset by type, returns parsed payload or null. */
  async function getDataset(type) {
    const rows = await select('app_datasets', `type=eq.${encodeURIComponent(type)}&select=payload,count,uploaded_by,uploaded_at_ms`);
    if (!rows.length) return null;
    const row = rows[0];
    try {
      return {
        data:        JSON.parse(row.payload),
        count:       row.count,
        uploadedBy:  row.uploaded_by,
        uploadedAt:  row.uploaded_at_ms,
      };
    } catch (e) {
      console.error(`SB.getDataset: failed to parse payload for ${type}`, e);
      return null;
    }
  }

  /** Upload a dataset snapshot. */
  async function putDataset(type, data, uploadedBy) {
    const payload = JSON.stringify(data);
    const count   = Array.isArray(data) ? data.length : (typeof data === 'object' ? Object.keys(data).length : 0);
    return upsert('app_datasets', {
      type,
      payload,
      count,
      uploaded_by:   uploadedBy || 'web',
      uploaded_at_ms: Date.now(),
    });
  }

  /** Fetch ALL datasets at once (for bootstrap). */
  async function getAllDatasets() {
    const rows = await select('app_datasets', 'select=type,payload,count,uploaded_by,uploaded_at_ms');
    const map = {};
    for (const row of rows) {
      try {
        map[row.type] = {
          data:       JSON.parse(row.payload),
          count:      row.count,
          uploadedBy: row.uploaded_by,
          uploadedAt: row.uploaded_at_ms,
        };
      } catch (e) {
        console.warn(`SB.getAllDatasets: skipping ${row.type}`, e);
      }
    }
    return map;
  }

  // ── Staff phone edits (app_staff_phones) ────────────────────

  async function getPhoneEdits() {
    return select('app_staff_phones', 'select=target_type,target_id,phone,updated_by,updated_at_ms');
  }

  async function putPhoneEdit(targetType, targetId, phone, updatedBy) {
    return upsert('app_staff_phones', {
      target_type:   targetType,
      target_id:     targetId,
      phone:         phone || '',
      updated_by:    updatedBy || 'web',
      updated_at_ms: Date.now(),
    });
  }

  // ── User-layer tables ───────────────────────────────────────

  async function getUsers() {
    return select('app_users', 'select=username,display_name,role,active,created_at,updated_at_ms');
  }

  async function getNotes() {
    return select('app_notes', 'deleted=eq.false&select=*');
  }

  async function getFavorites() {
    return select('app_favorites', 'deleted=eq.false&select=*');
  }

  async function getActivity(limit) {
    return select('app_activity', `select=*&order=created_at.desc&limit=${limit || 50}`);
  }

  // ── Chat messages ───────────────────────────────────────────

  async function getMessages(since) {
    let q = 'deleted=eq.false&select=*&order=created_at.asc';
    if (since) q += `&created_at=gt.${since}`;
    return select('app_messages', q);
  }

  async function postMessage(msg) {
    return upsert('app_messages', msg);
  }

  // ── Connectivity check ─────────────────────────────────────

  async function ping() {
    try {
      const r = await fetch(`${REST}/app_prefs?select=key&limit=1`, { headers: headers() });
      return r.ok;
    } catch { return false; }
  }

  // ── Init ────────────────────────────────────────────────────

  async function init() {
    if (_ready) return true;
    try {
      const ok = await ping();
      _ready = ok;
      if (ok) console.log('%c☁ Supabase connected', 'color:#4ade80;font-weight:bold');
      else    console.warn('⚠ Supabase unreachable — working offline');
      return ok;
    } catch (e) {
      console.warn('⚠ Supabase init failed:', e.message);
      _ready = false;
      return false;
    }
  }

  // ── Public API ──────────────────────────────────────────────

  return {
    init,
    ping,
    get ready() { return _ready; },

    // Low-level
    select, upsert, del,

    // Datasets (app_datasets)
    getDataset, putDataset, getAllDatasets,

    // Staff phones (app_staff_phones)
    getPhoneEdits, putPhoneEdit,

    // User layer
    getUsers, getNotes, getFavorites, getActivity,

    // Chat
    getMessages, postMessage,

    // Constants
    SUPABASE_URL, REST,
  };
})();
