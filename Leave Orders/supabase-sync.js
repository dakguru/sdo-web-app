/*  supabase-sync.js  –  Supabase connector for Karur SDO web app (Leave Orders pages)
 *  ─────────────────────────────────────────────────────────────────────────────────
 *  Identical API surface to /supabase-sync.js in wwwroot.
 *  This copy is placed in the Leave Orders folder so the self-contained HTML pages
 *  can load it via a relative path:  <script src="supabase-sync.js"></script>
 *
 *  Both copies share the same Supabase credentials and table names.
 *  ────────────────────────────────────────────────────────────── */

const SB = (() => {
  const SUPABASE_URL  = 'https://hfzbvqpxraeoqmvhtjto.supabase.co';
  const SUPABASE_KEY  = 'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImhmemJ2cXB4cmFlb3Ftdmh0anRvIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODQ5NDc4NDUsImV4cCI6MjEwMDUyMzg0NX0.kPcH1_YyWD32syqD2NBKWZpAVRds-SpwPTiXPpi6nIw';
  const REST          = `${SUPABASE_URL}/rest/v1`;
  let _ready = false;

  function headers(extra) {
    return Object.assign({
      'apikey': SUPABASE_KEY, 'Authorization': `Bearer ${SUPABASE_KEY}`,
      'Content-Type': 'application/json', 'Accept': 'application/json',
    }, extra || {});
  }

  async function select(table, query) {
    const url = `${REST}/${table}${query ? '?' + query : ''}`;
    const res = await fetch(url, { headers: headers() });
    if (!res.ok) throw new Error(`SB.select ${table}: ${res.status} ${await res.text()}`);
    return res.json();
  }

  async function upsert(table, rows) {
    const url = `${REST}/${table}`;
    const res = await fetch(url, {
      method: 'POST',
      headers: headers({ 'Prefer': 'resolution=merge-duplicates,return=minimal' }),
      body: JSON.stringify(Array.isArray(rows) ? rows : [rows]),
    });
    if (!res.ok) throw new Error(`SB.upsert ${table}: ${res.status} ${await res.text()}`);
    return true;
  }

  async function del(table, filter) {
    const url = `${REST}/${table}?${filter}`;
    const res = await fetch(url, { method: 'DELETE', headers: headers({ 'Prefer': 'return=minimal' }) });
    if (!res.ok) throw new Error(`SB.del ${table}: ${res.status} ${await res.text()}`);
    return true;
  }

  async function getDataset(type) {
    const rows = await select('app_datasets', `dataset_id=eq.${encodeURIComponent(type)}&select=data,count,uploaded_by,uploaded_at_ms`);
    if (!rows.length) return null;
    try { return { data: JSON.parse(rows[0].data), count: rows[0].count, uploadedBy: rows[0].uploaded_by, uploadedAt: rows[0].uploaded_at_ms }; }
    catch (e) { console.error(`SB.getDataset parse error for ${type}`, e); return null; }
  }

  async function putDataset(type, dataObj, uploadedBy) {
    const dataStr = JSON.stringify(dataObj);
    const count = Array.isArray(dataObj) ? dataObj.length : (typeof dataObj === 'object' ? Object.keys(dataObj).length : 0);
    return upsert('app_datasets', { dataset_id: type, data: dataStr, count, uploaded_by: uploadedBy || 'web', uploaded_at_ms: Date.now() });
  }

  async function getAllDatasets() {
    const map = {};
    const rows = await select('app_datasets', 'select=dataset_id,data,count,uploaded_by,uploaded_at_ms');
    for (const r of rows) { try { map[r.dataset_id] = { data: JSON.parse(r.data), count: r.count, uploadedBy: r.uploaded_by, uploadedAt: r.uploaded_at_ms }; } catch (e) {} }
    return map;
  }

  async function getPhoneEdits() { return select('app_staff_phones', 'select=target_type,target_id,phone,updated_by,updated_at_ms'); }
  async function putPhoneEdit(tt, ti, phone, by) { return upsert('app_staff_phones', { target_type: tt, target_id: ti, phone: phone||'', updated_by: by||'web', updated_at_ms: Date.now() }); }
  async function getUsers() { return select('app_users', 'select=username,display_name,role,active,created_at,updated_at_ms'); }
  async function getNotes() { return select('app_notes', 'deleted=eq.false&select=*'); }
  async function getFavorites() { return select('app_favorites', 'deleted=eq.false&select=*'); }
  async function getActivity(limit) { return select('app_activity', `select=*&order=created_at.desc&limit=${limit||50}`); }
  async function getMessages(since) { let q='deleted=eq.false&select=*&order=created_at.asc'; if(since)q+=`&created_at=gt.${since}`; return select('app_messages',q); }
  async function postMessage(msg) { return upsert('app_messages', msg); }
  async function ping() { try { return (await fetch(`${REST}/app_prefs?select=key&limit=1`, { headers: headers() })).ok; } catch { return false; } }
  async function init() { if (_ready) return true; try { const ok = await ping(); _ready = ok; if(ok) console.log('%c☁ Supabase connected','color:#4ade80;font-weight:bold'); else console.warn('⚠ Supabase unreachable'); return ok; } catch(e) { _ready=false; return false; } }

  return { init, ping, get ready(){return _ready}, select, upsert, del, getDataset, putDataset, getAllDatasets, getPhoneEdits, putPhoneEdit, getUsers, getNotes, getFavorites, getActivity, getMessages, postMessage, SUPABASE_URL, REST };
})();
